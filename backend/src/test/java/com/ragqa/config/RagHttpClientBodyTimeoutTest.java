package com.ragqa.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 回归用例：响应头及时返回，但正文停顿时也必须受超时保护。 */
@Timeout(30)
class RagHttpClientBodyTimeoutTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responseBodyMustNotBypassConfiguredTimeout(boolean modelClient) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch headersSent = new CountDownLatch(1);
        server.setExecutor(workers);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                headersSent.countDown();
                // 超过配置的 300ms 后才继续正文，模拟上游只返回开头就停住。
                releaseBody.await(1500, TimeUnit.MILLISECONDS);
                exchange.getResponseBody().write(body, 1, body.length - 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            RagApiProperties properties = new RagApiProperties();
            properties.getSearch().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            properties.getSearch().setReadTimeout(Duration.ofMillis(300));
            properties.getLlm().setBaseUrl(properties.getSearch().getEndpoint());
            properties.getLlm().setReadTimeout(Duration.ofMillis(300));
            RagHttpClientConfig config = new RagHttpClientConfig();
            RestClient client = modelClient ? config.llmRestClient(RestClient.builder(), properties)
                    : config.searchRestClient(RestClient.builder(), properties);

            long start = System.nanoTime();
            Throwable failure = catchThrowable(() -> client.post().retrieve().body(String.class));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(requests).hasValue(1);
            assertThat(headersSent.getCount()).as("必须实际进入正文读取阶段").isZero();
            assertThat(failure)
                    .as("正文必须受 300ms 超时保护；实际等待 %s ms，超时异常为 %s", elapsedMillis, failure)
                    .isInstanceOf(ResourceAccessException.class);
            // 给调度与 CI 环境留余量，但不容许完整等待模拟服务的 1500ms。
            assertThat(elapsedMillis).isLessThan(1200);
        } finally {
            releaseBody.countDown();
            server.stop(0);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void continuousSmallChunksCannotResetDeadlineAndConnectionIsCancelled() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        try (Fixture fixture = new Fixture(dripping(disconnected, chunks), Duration.ofMillis(500), 8192)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.client.get().retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
            assertThat(chunks.get()).as("持续接收小数据也不能延长时限").isGreaterThanOrEqualTo(2);
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).as("超时后服务端应观察到连接取消").isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedResponseIsRejectedForFixedLengthAndChunkedTransfer(boolean chunked) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, chunked ? 0 : 64);
                exchange.getResponseBody().write(new byte[64]);
            }
        }, Duration.ofSeconds(2), 32)) {
            assertThatThrownBy(() -> fixture.client.get().retrieve().body(byte[].class))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseMessage("外部服务响应超过大小限制");
        }
    }

    @Test
    void responseAtExactSizeLimitAndJsonRequestRemainUsable() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestHeader = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestHeader.set(exchange.getRequestHeaders().getFirst("X-Test"));
            respond(exchange, "成功");
        }, Duration.ofSeconds(2), 6)) {
            assertThat(fixture.client.post().contentType(MediaType.APPLICATION_JSON).header("X-Test", "value")
                    .body(Map.of("query", "测试")).retrieve().body(String.class)).isEqualTo("成功");
            assertThat(requestBody.get()).isEqualTo("{\"query\":\"测试\"}");
            assertThat(requestHeader.get()).isEqualTo("value");
        }
    }

    @Test
    void truncatedResponseFailsAndNextRequestCanSucceed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange -> {
            if (requests.incrementAndGet() == 1) {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 64);
                    exchange.getResponseBody().write(new byte[8]);
                }
            } else {
                respond(exchange, "ok");
            }
        }, Duration.ofSeconds(1), 128)) {
            assertThatThrownBy(() -> fixture.client.get().retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(fixture.client.get().retrieve().body(String.class)).isEqualTo("ok");
            assertThat(requests).hasValue(2);
        }
    }

    @Test
    void interruptPreservesFlagAndStopsRealResponseTransmission() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CountDownLatch firstChunk = new CountDownLatch(1);
        HttpHandler handler = dripping(disconnected, chunks);
        try (Fixture fixture = new Fixture(exchange -> {
            firstChunk.countDown();
            handler.handle(exchange);
        }, Duration.ofSeconds(5), 8192)) {
            Thread caller = new Thread(() -> {
                failure.set(catchThrowable(() -> fixture.client.get().retrieve().body(String.class)));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }, "测试-可取消的调用");
            try {
                caller.start();
                assertThat(firstChunk.await(2, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(2000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(ResourceAccessException.class)
                        .hasRootCauseInstanceOf(InterruptedException.class);
                assertThat(interruptPreserved).isTrue();
                assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                caller.interrupt();
                caller.join(2000);
            }
        }
    }

    @Test
    void slowAndFastConcurrentRequestsKeepIndependentState() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture(exchange -> {
            if ("slow".equals(exchange.getRequestHeaders().getFirst("X-Mode"))) {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('o');
                    exchange.getResponseBody().flush();
                    slowStarted.countDown();
                    releaseSlow.await(2, TimeUnit.SECONDS);
                    exchange.getResponseBody().write('k');
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            } else {
                respond(exchange, "ok");
            }
        }, Duration.ofMillis(500), 64)) {
            var slow = caller.submit(() -> catchThrowable(() -> fixture.client.get().header("X-Mode", "slow")
                    .retrieve().body(String.class)));
            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.client.get().retrieve().body(String.class)).isEqualTo("ok");
            assertThat(slow.get(2, TimeUnit.SECONDS)).isInstanceOf(ResourceAccessException.class);
        } finally {
            releaseSlow.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void deadlineCancelsOriginalExchangeRatherThanOnlyStoppingWait() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);
        RestClient client = RestClient.builder().baseUrl("https://example.com")
                .requestFactory(new BoundedJdkClientHttpRequestFactory(http, Duration.ofMillis(50), 64)).build();

        assertThatThrownBy(() -> client.get().retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(exchange.isCancelled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jdkTimeoutBeforeWaitUsesSameSafeTimeoutClassification(boolean connectionTimeout) {
        HttpClient http = mock(HttpClient.class);
        String upstreamMessage = "不应传播的上游地址与凭据信息";
        HttpTimeoutException timeout = connectionTimeout
                ? new HttpConnectTimeoutException(upstreamMessage) : new HttpTimeoutException(upstreamMessage);
        CompletableFuture<HttpResponse<byte[]>> exchange = CompletableFuture.failedFuture(timeout);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageNotContaining(upstreamMessage)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class)
                .rootCause().hasMessage("外部服务响应超时").hasNoCause();
        verify(http).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void nonTimeoutFailureKeepsItsOriginalClassification() {
        HttpClient http = mock(HttpClient.class);
        IOException failure = new IOException("测试网络故障");
        CompletableFuture<HttpResponse<byte[]>> exchange = CompletableFuture.failedFuture(failure);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class).hasRootCause(failure);
        verify(http).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void exhaustedRequestBudgetPreventsActualSend(long nanos) {
        HttpClient http = mock(HttpClient.class);
        RestClient client = budgetClient(http, Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofNanos(nanos)))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        verifyNoInteractions(http);
    }

    @Test
    void serializationCanExhaustBudgetBeforeSend() {
        HttpClient http = mock(HttpClient.class);
        RestClient client = budgetClient(http, Duration.ofSeconds(5));
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.post().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, remaining::get))
                .body(output -> {
                    remaining.set(Duration.ZERO);
                    output.write("测试请求".getBytes(StandardCharsets.UTF_8));
                }).retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        verifyNoInteractions(http);
    }

    @Test
    void requestBudgetShortensWaitAndCancelsOriginalFuture() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);
        RestClient client = budgetClient(http, Duration.ofSeconds(5));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofMillis(50)))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
        assertThat(exchange.isCancelled()).isTrue();
        assertThat(sentTimeout(http)).isPositive().isLessThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void hugeRequestBudgetCannotEnlargeConfiguredTransferLimit() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);

        assertThatThrownBy(() -> budgetClient(http, Duration.ofMillis(50)).get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request,
                        () -> Duration.ofSeconds(Long.MAX_VALUE)))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class);

        assertThat(sentTimeout(http)).isPositive().isLessThanOrEqualTo(Duration.ofMillis(50));
        assertThat(exchange.isCancelled()).isTrue();
    }

    @Test
    void nanosecondBudgetIsNeverRoundedUpToMillisecond() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofNanos(1)))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class);

        assertThat(sentTimeout(http)).isEqualTo(Duration.ofNanos(1));
        assertThat(exchange.isCancelled()).isTrue();
    }

    @Test
    void budgetFailureAfterSendAlsoCancelsOriginalFuture() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request,
                        () -> reads.incrementAndGet() == 1 ? Duration.ofSeconds(2) : null))
                .retrieve().body(String.class)).isInstanceOf(NullPointerException.class)
                .hasMessage("剩余预算不能为空");
        assertThat(exchange.isCancelled()).isTrue();
    }

    @Test
    void unsupportedOrRepeatedBindingIsRejectedBeforeSend() {
        assertThatThrownBy(() -> BoundedJdkClientHttpRequestFactory.useRemainingBudget(
                mock(ClientHttpRequest.class), () -> Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("当前 HTTP 请求不支持任务预算绑定");
        HttpClient http = mock(HttpClient.class);
        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().httpRequest(request -> {
            BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofSeconds(1));
            BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofSeconds(2));
        }).retrieve().body(String.class)).isInstanceOf(IllegalStateException.class)
                .hasMessage("同一 HTTP 请求不能重复绑定任务预算");
        verifyNoInteractions(http);
    }

    @Test
    void missingBudgetSourceOrValueCannotSendRequest() {
        HttpClient http = mock(HttpClient.class);
        RestClient client = budgetClient(http, Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, null))
                .retrieve().body(String.class)).isInstanceOf(NullPointerException.class)
                .hasMessage("剩余预算计算器不能为空");
        assertThatThrownBy(() -> client.get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> null))
                .retrieve().body(String.class)).isInstanceOf(NullPointerException.class)
                .hasMessage("剩余预算不能为空");
        verifyNoInteractions(http);
    }

    @Test
    void budgetExhaustedDuringSendCancelsExchangeBeforeWaiting() {
        HttpClient http = mock(HttpClient.class);
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
                    remaining.set(Duration.ZERO);
                    return exchange;
                });

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, remaining::get))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(exchange.isCancelled()).isTrue();
    }

    @Test
    void alreadyCompletedResponseCannotBypassFinalBudgetCheck() {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        CompletableFuture<HttpResponse<byte[]>> exchange = CompletableFuture.completedFuture(response);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(exchange);
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> budgetClient(http, Duration.ofSeconds(5)).get().httpRequest(request ->
                BoundedJdkClientHttpRequestFactory.useRemainingBudget(request,
                        () -> reads.incrementAndGet() < 3 ? Duration.ofSeconds(2) : Duration.ZERO))
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(reads).hasValue(3);
        verifyNoInteractions(response);
    }

    @Test
    void requestBudgetStopsRealDrippingBodyAndReleasesConnection() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        try (Fixture fixture = new Fixture(dripping(disconnected, chunks), Duration.ofSeconds(5), 8192)) {
            var deadline = com.ragqa.rag.chat.TaskDeadline.start(Duration.ofMillis(500));
            long started = System.nanoTime();

            assertThatThrownBy(() -> fixture.client.get().httpRequest(request ->
                    BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, deadline::remaining))
                    .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
            assertThat(chunks.get()).isGreaterThanOrEqualTo(2);
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousRequestsUseSeparateBudgetsOnSharedClient() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture(exchange -> {
            if ("/slow".equals(exchange.getRequestURI().getPath())) {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('o');
                    exchange.getResponseBody().flush();
                    slowStarted.countDown();
                    releaseSlow.await(2, TimeUnit.SECONDS);
                    exchange.getResponseBody().write('k');
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            } else {
                respond(exchange, "ok");
            }
        }, Duration.ofSeconds(5), 64)) {
            var deadline = com.ragqa.rag.chat.TaskDeadline.start(Duration.ofMillis(500));
            var slow = caller.submit(() -> catchThrowable(() -> fixture.client.get().uri("/slow")
                    .httpRequest(request -> BoundedJdkClientHttpRequestFactory
                            .useRemainingBudget(request, deadline::remaining)).retrieve().body(String.class)));
            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.client.get().uri("/fast").httpRequest(request ->
                    BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, () -> Duration.ofSeconds(2)))
                    .retrieve().body(String.class)).isEqualTo("ok");
            assertThat(slow.get(2, TimeUnit.SECONDS)).isInstanceOf(ResourceAccessException.class);
            // 未绑定预算的请求仍按工厂原配置运行，不继承上一请求的预算。
            assertThat(fixture.client.get().uri("/fast").retrieve().body(String.class)).isEqualTo("ok");
        } finally {
            releaseSlow.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RestClient budgetClient(HttpClient http, Duration timeout) {
        return RestClient.builder().baseUrl("https://example.com")
                .requestFactory(new BoundedJdkClientHttpRequestFactory(http, timeout, 64)).build();
    }

    private static Duration sentTimeout(HttpClient http) {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        return request.getValue().timeout().orElseThrow();
    }

    private static HttpHandler dripping(CountDownLatch disconnected, AtomicInteger chunks) {
        return exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (int index = 0; index < 100; index++) {
                    exchange.getResponseBody().write(new byte[64]);
                    exchange.getResponseBody().flush();
                    chunks.incrementAndGet();
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            } catch (IOException exception) {
                // 本测试主动让客户端取消，通过服务端写失败验证连接确实停止传输。
                disconnected.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void respond(HttpExchange exchange, String value) throws IOException {
        try (exchange) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /** 每个用例持有独立服务；真实 HTTP 客户端跨本用例内的请求复用。 */
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final RestClient client;

        private Fixture(HttpHandler handler, Duration timeout, int maxBytes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", handler);
            server.start();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(new BoundedJdkClientHttpRequestFactory(http, timeout, maxBytes)).build();
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
