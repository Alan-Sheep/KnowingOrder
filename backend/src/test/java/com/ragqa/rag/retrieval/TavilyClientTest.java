package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.config.RagApiProperties;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 主动注入故障验证有限尝试和安全提示，不访问付费 API，不使用真实凭据。 */
@Timeout(30)
class TavilyClientTest {

    private static final String KEY = "test-only-placeholder";
    private static final String FORMAT_MESSAGE = "搜索服务响应格式异常，请稍后重试";
    private static final String RESULT = """
            {"results":[{"title":"测试来源","url":"https://example.com/article","content":"测试正文","score":0.8}]}
            """;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsBoundedSearchParametersAndKeepsCredentialOnlyInHeader() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uri.set(exchange.getRequestURI().toString());
            payload.set(json.readTree(exchange.getRequestBody()));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            respond(exchange, 200, RESULT);
        })) {
            var results = fixture.search.search("  测试查询  ");
            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("测试来源");
            assertThat(auth.get()).isEqualTo("Bearer " + KEY);
            assertThat(uri.get()).isEqualTo("/search");
            assertThat(payload.get().get("query").asText()).isEqualTo("测试查询");
            assertThat(payload.get().get("search_depth").asText()).isEqualTo("basic");
            assertThat(payload.get().get("max_results").asInt()).isEqualTo(5);
            for (String field : new String[]{"include_answer", "include_raw_content", "include_images", "auto_parameters"}) {
                assertThat(payload.get().get(field).asBoolean()).isFalse();
            }
            assertThat(payload.get().toString()).doesNotContain(KEY, "api_key");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankQueryBeforeCallingProvider(String query) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, RESULT))) {
            assertThatThrownBy(() -> fixture.search.search(query)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("500 字");
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @Test
    void queryLimitCountsUnicodeCodePoints() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, RESULT))) {
            assertThat(fixture.search.search("问".repeat(500))).hasSize(1);
            assertThat(fixture.search.search("😀".repeat(500))).hasSize(1);
            assertThatThrownBy(() -> fixture.search.search("😀".repeat(501)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("500 字");
            assertThatThrownBy(() -> fixture.search.search("问".repeat(1001)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("500 字");
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "key\r\nunsafe", "凭据"})
    void missingOrUnsafeKeyNeverReachesNetwork(String key) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, RESULT))) {
            fixture.settings.getSearch().setApiKey(key);
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索尚未配置，请联系管理员");
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @Test
    void explicitlyEmptyResultsAreNotRetried() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, "{\"results\":[]}"))) {
            assertThat(fixture.search.search("没有资料的问题")).isEmpty();
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"results\":null}", "{\"results\":{}}",
            "{\"results\":[{}]}", "<html>upstream-private-marker</html>",
            "{\"results\":[]} {\"extra\":\"upstream-private-marker\"}"})
    void malformedResponseIsNotRetriedAndCannotLeakParserContext(String body) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, body))) {
            Throwable failure = catchThrowable(() -> fixture.search.search("问题"));
            assertThat(failure).isInstanceOf(BusinessException.class).hasMessage(FORMAT_MESSAGE).hasNoCause();
            assertThat(stackTrace(failure)).doesNotContain(KEY, "upstream-private-marker");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void wrongContentTypeIsNotTreatedAsSearchSuccess() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            respond(exchange, 200, RESULT);
        })) {
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage(FORMAT_MESSAGE);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422, 432, 433, 402, 500, 302})
    void permanentHttpFailureDoesNotRetryOrEchoProviderBody(int status) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, status,
                "{\"detail\":\"upstream-private-marker:" + KEY + "\"}"))) {
            Throwable failure = catchThrowable(() -> fixture.search.search("问题"));
            assertThat(failure).isInstanceOf(BusinessException.class).hasNoCause();
            assertThat(stackTrace(failure)).doesNotContain(KEY, "upstream-private-marker");
            assertThat(((BusinessException) failure).getCode()).isNotIn(401, 403);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void temporaryFailureCanRecoverOnSecondAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange -> respond(exchange,
                attempts.incrementAndGet() == 1 ? 503 : 200, RESULT))) {
            assertThat(fixture.search.search("问题")).hasSize(1);
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void continuousTemporaryFailureStopsAfterTwoAttempts(int status) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, status, "{}"))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索暂不可用，请稍后重试");
            assertThat(fixture.requests).hasValue(2);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2500);
        }
    }

    @ParameterizedTest
    @MethodSource("unusableRetryAfter")
    void unknownOrExcessiveRateLimitWaitFailsQuickly(String retryAfter) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            respond(exchange, 429, "{}");
        })) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索繁忙，请稍后重试");
            assertThat(fixture.requests).hasValue(1);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
        }
    }

    static Stream<String> unusableRetryAfter() {
        return Stream.of(null, "-1", "invalid", "60", "99999999999999999999999999999999",
                ZonedDateTime.now(java.time.ZoneOffset.UTC).plusHours(1).format(DateTimeFormatter.RFC_1123_DATE_TIME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "Wed, 21 Oct 2015 07:28:00 GMT"})
    void rateLimitWithZeroWaitStillUsesBackoffAndStopsAfterSecondResponse(String retryAfter) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
            respond(exchange, 429, "{}");
        })) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索繁忙，请稍后重试");
            assertThat(fixture.requests).hasValue(2);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(190L, 2500L);
        }
    }

    @Test
    void retryAfterSecondsAreRespectedBeforeRecovering() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Long> first = new AtomicReference<>();
        AtomicReference<Long> second = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                first.set(System.nanoTime());
                exchange.getResponseHeaders().set("Retry-After", "1");
                respond(exchange, 429, "{}");
            } else {
                second.set(System.nanoTime());
                respond(exchange, 200, RESULT);
            }
        })) {
            assertThat(fixture.search.search("问题")).hasSize(1);
            assertThat(fixture.requests).hasValue(2);
            assertThat(TimeUnit.NANOSECONDS.toMillis(second.get() - first.get())).isGreaterThanOrEqualTo(990);
        }
    }

    @Test
    void stalledBodyTimesOutWithoutRepeatingFullWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(2, TimeUnit.SECONDS);
                exchange.getResponseBody().write('}');
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(250), 4096)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索超时，请稍后重试");
            assertThat(fixture.requests).hasValue(1);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
        } finally {
            release.countDown();
        }
    }

    @Test
    void oversizedBodyDoesNotTriggerAnotherPaidAttempt() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, RESULT), Duration.ofSeconds(2), 16)) {
            assertThatThrownBy(() -> fixture.search.search("问题")).isInstanceOf(BusinessException.class).hasNoCause();
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void connectionFailureRetriesButTlsFailureDoesNot() {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger attempts = new AtomicInteger();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> switch (attempts.incrementAndGet()) {
                    case 1 -> CompletableFuture.failedFuture(new ConnectException("upstream-private-marker"));
                    case 2 -> completedResponse(200, null, RESULT);
                    default -> CompletableFuture.failedFuture(new SSLHandshakeException("upstream-private-marker"));
                });
        TavilyClient client = mockSearch(http);

        assertThat(client.search("问题")).hasSize(1);
        Throwable failure = catchThrowable(() -> client.search("问题"));
        assertThat(failure).isInstanceOf(BusinessException.class).hasNoCause();
        assertThat(stackTrace(failure)).doesNotContain("upstream-private-marker");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void interruptBeforeCallPreventsNetworkAndKeepsFlag() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, RESULT))) {
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> fixture.search.search("问题"))
                        .isInstanceOf(BusinessException.class).hasMessage("本次搜索已取消");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(fixture.requests).hasValue(0);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void interruptAfterFirstResponsePreventsRetry() {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger attempts = new AtomicInteger();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            Thread.currentThread().interrupt();
            return completedResponse(503, null, "{}");
        });
        TavilyClient client = mockSearch(http);
        try {
            assertThatThrownBy(() -> client.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("本次搜索已取消");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(attempts).hasValue(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptDuringBackoffStopsRetryAndPreservesFlag() throws Exception {
        CountDownLatch firstResponse = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        AtomicInteger attempts = new AtomicInteger();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            firstResponse.countDown();
            return completedResponse(429, "2", "{}");
        });
        TavilyClient client = mockSearch(http);
        Thread caller = new Thread(() -> {
            failure.set(catchThrowable(() -> client.search("问题")));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "测试-取消重试等待");
        try {
            caller.start();
            assertThat(firstResponse.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            assertThat(caller.getState()).as("先确认调用线程已进入退避等待").isEqualTo(Thread.State.TIMED_WAITING);
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(BusinessException.class).hasMessage("本次搜索已取消");
            assertThat(interrupted.get()).isTrue();
            assertThat(attempts).hasValue(1);
        } finally {
            caller.interrupt();
            caller.join(2000);
        }
    }

    @Test
    void concurrentQueriesDoNotShareRetriesOrFailureState() throws Exception {
        AtomicInteger recoverableCalls = new AtomicInteger();
        AtomicInteger deniedCalls = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (Fixture fixture = new Fixture(exchange -> {
            String query = json.readTree(exchange.getRequestBody()).get("query").asText();
            if (query.equals("查询甲")) {
                respond(exchange, recoverableCalls.incrementAndGet() == 1 ? 503 : 200, RESULT);
            } else {
                deniedCalls.incrementAndGet();
                respond(exchange, 401, "{}");
            }
        })) {
            CountDownLatch start = new CountDownLatch(1);
            var successful = callers.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return fixture.search.search("查询甲");
            });
            var denied = callers.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return catchThrowable(() -> fixture.search.search("查询乙"));
            });
            start.countDown();
            assertThat(successful.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(denied.get(5, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
            assertThat(recoverableCalls).hasValue(2);
            assertThat(deniedCalls).hasValue(1);
        } finally {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    /** 仅模拟网络交换，保留生产传输工厂，预算绑定不能被测试包装器绕过。 */
    private TavilyClient mockSearch(HttpClient http) {
        return mockSearch(http, new TavilyResultMapper());
    }

    private TavilyClient mockSearch(HttpClient http, TavilyResultMapper mapper) {
        RagApiProperties properties = new RagApiProperties();
        properties.getSearch().setApiKey(KEY);
        RestClient rest = RestClient.builder().baseUrl("https://example.com/search")
                .requestFactory(new BoundedJdkClientHttpRequestFactory(http, Duration.ofSeconds(2), 4096)).build();
        return new TavilyClient(rest, properties, mapper, json);
    }

    private static CompletableFuture<HttpResponse<byte[]>> completedResponse(int status, String retryAfter, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        Map<String, List<String>> headers = retryAfter == null
                ? Map.of("Content-Type", List.of("application/json"))
                : Map.of("Content-Type", List.of("application/json"), "Retry-After", List.of(retryAfter));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(response);
    }

    @Test
    void defaultSearchBudgetIncludesRetryAfterWait() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, 429, "{}");
        })) {
            fixture.settings.getSearch().setReadTimeout(Duration.ofMillis(500));
            long started = System.nanoTime();
            assertThatThrownBy(() -> fixture.search.search("问题"))
                    .isInstanceOf(BusinessException.class).hasMessage("联网搜索超时，请稍后重试");
            assertThat(fixture.requests).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900));
        }
    }

    @Test
    void nullOrExhaustedSharedBudgetCannotSendRequest() {
        HttpClient http = mock(HttpClient.class);
        TavilyClient search = mockSearch(http);
        assertThatThrownBy(() -> search.search("问题", null))
                .isInstanceOf(NullPointerException.class).hasMessage("任务时限不能为空");
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenReturn(Duration.ZERO);
        Throwable failure = catchThrowable(() -> search.search("问题", deadline));
        assertThat(failure).isInstanceOf(BusinessException.class)
                .hasMessage("联网搜索超时，请稍后重试").hasNoCause();
        assertThat(((BusinessException) failure).getCode()).isEqualTo(503);
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(strings = {RESULT, "{\"results\":[]}"})
    void sharedBudgetPreservesSuccessAndExplicitlyEmptyResults(String body) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, body))) {
            var results = fixture.search.search("问题", TaskDeadline.start(Duration.ofSeconds(5)));
            assertThat(results).hasSize(body.equals(RESULT) ? 1 : 0);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void sharedBudgetBoundsFullBodyWaitAndCancelsInFlightRequest() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        AtomicReference<Duration> actualTimeout = new AtomicReference<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            actualTimeout.set(invocation.<HttpRequest>getArgument(0).timeout().orElseThrow());
            return pending;
        });
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenReturn(Duration.ofMillis(120));
        TavilyClient search = mockSearch(http);
        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> search.search("问题", deadline));
        assertThat(failure).isInstanceOf(BusinessException.class)
                .hasMessage("联网搜索超时，请稍后重试").hasNoCause();
        assertThat(actualTimeout.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(120));
        assertThat(pending).isCancelled();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        org.mockito.Mockito.verify(http).sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void largerSharedBudgetDoesNotExpandTransportLimit() {
        HttpClient http = mock(HttpClient.class);
        AtomicReference<Duration> actualTimeout = new AtomicReference<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            actualTimeout.set(invocation.<HttpRequest>getArgument(0).timeout().orElseThrow());
            return completedResponse(200, null, RESULT);
        });
        assertThat(mockSearch(http).search("问题", TaskDeadline.start(Duration.ofSeconds(10)))).hasSize(1);
        assertThat(actualTimeout.get()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void insufficientBudgetSkipsWholeBackoffAndNeverRetriesEarly(int status) {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenAnswer(invocation -> remaining.get());
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            remaining.set(Duration.ofMillis(100));
            return completedResponse(status, "1", "{\"detail\":\"upstream-private-marker\"}");
        });
        Throwable failure = catchThrowable(() -> mockSearch(http).search("问题", deadline));
        assertThat(failure).isInstanceOf(BusinessException.class)
                .hasMessage("联网搜索超时，请稍后重试").hasNoCause();
        assertThat(stackTrace(failure)).doesNotContain(KEY, "upstream-private-marker");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void secondAttemptUsesSameRemainingBudgetInsteadOfStartingOver() {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        List<Duration> timeouts = new java.util.ArrayList<>();
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenAnswer(invocation -> remaining.get());
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            timeouts.add(invocation.<HttpRequest>getArgument(0).timeout().orElseThrow());
            if (attempts.incrementAndGet() == 1) {
                remaining.set(Duration.ofMillis(700));
                return completedResponse(503, null, "{}");
            }
            return completedResponse(200, null, RESULT);
        });
        assertThat(mockSearch(http).search("问题", deadline)).hasSize(1);
        assertThat(attempts).hasValue(2);
        assertThat(timeouts.get(0)).isGreaterThan(Duration.ofSeconds(1));
        assertThat(timeouts.get(1)).isPositive().isLessThanOrEqualTo(Duration.ofMillis(700));
    }

    @Test
    void budgetExhaustedDuringBackoffPreventsSecondRequest() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CountDownLatch firstResponse = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenAnswer(invocation -> remaining.get());
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            firstResponse.countDown();
            return completedResponse(429, "1", "{}");
        });
        TavilyClient search = mockSearch(http);
        Thread caller = new Thread(() -> failure.set(catchThrowable(() -> search.search("问题", deadline))),
                "测试-退避期间预算到期");
        try {
            caller.start();
            assertThat(firstResponse.await(2, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            assertThat(caller.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            remaining.set(Duration.ZERO);
            caller.join(1800);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(BusinessException.class)
                    .hasMessage("联网搜索超时，请稍后重试");
            assertThat(attempts).hasValue(1);
        } finally {
            caller.interrupt();
            caller.join(2000);
        }
    }

    @Test
    void budgetExhaustedDuringMappingRejectsLateSuccess() {
        HttpClient http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> completedResponse(200, null, RESULT));
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenAnswer(invocation -> remaining.get());
        TavilyResultMapper mapper = mock(TavilyResultMapper.class);
        when(mapper.map(any(JsonNode.class))).thenAnswer(invocation -> {
            remaining.set(Duration.ZERO);
            return List.of();
        });
        assertThatThrownBy(() -> mockSearch(http, mapper).search("问题", deadline))
                .isInstanceOf(BusinessException.class).hasMessage("联网搜索超时，请稍后重试");
    }

    @Test
    void cancellationTakesPrecedenceOverExhaustedBudgetAndKeepsFlag() {
        HttpClient http = mock(HttpClient.class);
        TavilyClient search = mockSearch(http);
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenReturn(Duration.ZERO);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> search.search("问题", deadline))
                    .isInstanceOf(BusinessException.class).hasMessage("本次搜索已取消");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            org.mockito.Mockito.verifyNoInteractions(http);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void concurrentSearchesKeepTheirOwnBudgets() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        TaskDeadline shortBudget = mock(TaskDeadline.class);
        when(shortBudget.remaining()).thenReturn(Duration.ofMillis(300));
        try (Fixture fixture = new Fixture(exchange -> {
            String query = json.readTree(exchange.getRequestBody()).get("query").asText();
            if (query.equals("慢查询")) {
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }
            respond(exchange, 200, RESULT);
        })) {
            var slow = callers.submit(() -> catchThrowable(() -> fixture.search.search("慢查询", shortBudget)));
            var fast = callers.submit(() -> fixture.search.search("快查询", TaskDeadline.start(Duration.ofSeconds(5))));
            assertThat(fast.get(2, TimeUnit.SECONDS)).hasSize(1);
            assertThat(slow.get(2, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class)
                    .hasMessage("联网搜索超时，请稍后重试");
            assertThat(fixture.requests).hasValue(2);
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void respond(HttpExchange exchange, int status, String value) throws IOException {
        try (exchange) {
            if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            // 零长度使用分块响应，避免 HttpServer 把它误当成无正文的固定长度响应。
            exchange.sendResponseHeaders(status, bytes.length == 0 ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicInteger requests = new AtomicInteger();
        private final RagApiProperties settings = new RagApiProperties();
        private final TavilyClient search;

        private Fixture(HttpHandler handler) throws IOException {
            this(handler, Duration.ofSeconds(2), 4096);
        }

        private Fixture(HttpHandler handler, Duration timeout, int maxBytes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/search", exchange -> {
                requests.incrementAndGet();
                // 请求体先消费完，模拟正常 API；凭据不写入服务端日志。
                byte[] payload = exchange.getRequestBody().readAllBytes();
                exchange.setStreams(new java.io.ByteArrayInputStream(payload), null);
                handler.handle(exchange);
            });
            server.start();
            settings.getSearch().setApiKey(KEY);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            RestClient rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/search")
                    .requestFactory(new BoundedJdkClientHttpRequestFactory(http, timeout, maxBytes)).build();
            search = new TavilyClient(rest, settings, new TavilyResultMapper(), json);
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
