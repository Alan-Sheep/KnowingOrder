package com.ragqa.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.params.provider.CsvSource;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 故障注入只访问本地模拟服务，凭据与供应商正文均为虚构标记。 */
@Timeout(30)
class ResponsesClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "fake-model-sensitive-marker";
    private static final String PRIVATE = "upstream-private-marker";
    private static final String FORMAT = "模型服务响应格式异常，请稍后重试";
    private static final String CONFIG = "模型调用配置异常，请联系管理员";
    private static final String TIMEOUT = "模型回答超时，请稍后重试";
    private static final String SUCCESS = """
            {"object":"response","status":"completed","error":null,"output":[
              {"type":"reasoning","summary":[{"text":"upstream-private-marker"}]},
              {"type":"message","role":"assistant","status":"completed","phase":"final_answer",
               "content":[{"type":"output_text","text":"Java"},
                          {"type":"output_text","text":" "},{"type":"output_text","text":"面试"}]}]}
            """;

    @Test
    void sendsOneResponsesRequestWithSeparateRolesAndPreservesTextWhitespace() throws Exception {
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            payload.set(JSON.readTree(exchange.getRequestBody()));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, SUCCESS);
        })) {
            assertThat(generate(fixture.client)).isEqualTo("Java 面试");
            assertThat(auth.get()).isEqualTo("Bearer " + KEY);
            assertThat(uri.get()).isEqualTo("/v1/responses");
            assertThat(payload.get().path("model").asText()).isEqualTo("test-model");
            assertThat(payload.get().at("/input/0/role").asText()).isEqualTo("system");
            assertThat(payload.get().at("/input/0/content").asText()).isEqualTo("服务端规则");
            assertThat(payload.get().at("/input/1/role").asText()).isEqualTo("user");
            assertThat(payload.get().at("/input/1/content").asText()).isEqualTo("用户问题和资料");
            assertThat(payload.get().path("max_output_tokens").asInt()).isEqualTo(2048);
            assertThat(payload.get().path("store").booleanValue()).isFalse();
            assertThat(payload.get().path("stream").booleanValue()).isFalse();
            assertThat(payload.get().toString()).doesNotContain(KEY, "temperature", "tools", "api_key");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void skipsCommentaryAndAcceptsLegacyMessageWithoutOptionalStatusAndPhase() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(SUCCESS);
        ArrayNode output = (ArrayNode) response.path("output");
        ObjectNode commentary = output.get(1).deepCopy();
        commentary.put("phase", "commentary");
        ((ObjectNode) commentary.at("/content/0")).put("text", PRIVATE);
        output.insert(0, commentary);
        ((ObjectNode) output.get(2)).remove(List.of("phase", "status"));
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, response.toString()))) {
            assertThat(generate(fixture.client)).isEqualTo("Java 面试");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankRulesAndInputBeforeSending(String value) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            assertSafe(catchThrowable(() -> fixture.client.generate(value, "问题", 2048, budget())), "输入不能为空");
            assertSafe(catchThrowable(() -> fixture.client.generate("规则", value, 2048, budget())), "输入不能为空");
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4097, Integer.MAX_VALUE})
    void rejectsInvalidOutputLimitBeforeSending(int limit) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            assertSafe(catchThrowable(() -> fixture.client.generate("规则", "问题", limit, budget())), "输出额度");
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @Test
    void combinedInputLimitCountsUnicodeCodePointsAndRejectsOversize() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            assertThat(fixture.client.generate("规", "😀".repeat(15999), 1, budget())).isEqualTo("Java 面试");
            assertSafe(catchThrowable(() -> fixture.client.generate("规", "问".repeat(16000), 4096, budget())),
                    "合计最多为 16000 字");
            assertSafe(catchThrowable(() -> fixture.client.generate("规", "问".repeat(32001), 2048, budget())),
                    "合计最多为 16000 字");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "key\r\nunsafe", "凭据"})
    void invalidKeysNeverReachProvider(String key) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            fixture.settings.getLlm().setApiKey(key);
            assertSafe(catchThrowable(() -> generate(fixture.client)), CONFIG);
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "model\nunsafe"})
    void invalidModelsNeverReachProvider(String model) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            fixture.settings.getLlm().setModel(model);
            assertSafe(catchThrowable(() -> generate(fixture.client)), CONFIG);
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-url", "http://example.com/v1", "https://user:private@example.com/v1",
            "https://example.com/v1?key=private", "https://example.com/v1#private", "https://example.com/v1/responses",
            "https://example.com/v1/responses/", "https://example.com/v1/chat/completions/"})
    void invalidBaseAddressesNeverReachProvider(String baseUrl) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS))) {
            fixture.settings.getLlm().setBaseUrl(baseUrl);
            assertSafe(catchThrowable(() -> generate(fixture.client)), CONFIG);
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"302,503,模型服务暂不可用，请稍后重试", "400,500,模型调用配置异常，请联系管理员",
            "401,500,模型服务暂不可用，请稍后重试", "402,503,模型服务额度暂不可用，请稍后重试",
            "403,500,模型服务暂不可用，请稍后重试", "404,500,模型调用配置异常，请联系管理员",
            "405,500,模型调用配置异常，请联系管理员", "408,503,模型回答超时，请稍后重试",
            "422,500,模型调用配置异常，请联系管理员", "429,503,模型服务繁忙，请稍后重试",
            "500,503,模型服务暂不可用，请稍后重试", "502,503,模型服务暂不可用，请稍后重试",
            "503,503,模型服务暂不可用，请稍后重试", "504,503,模型回答超时，请稍后重试"})
    void httpErrorsNeverRetryOrEchoProviderBody(int status, int code, String message) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, status, "{\"detail\":\"" + PRIVATE + ":" + KEY + "\"}");
        })) {
            Throwable failure = catchThrowable(() -> generate(fixture.client));
            assertSafe(failure, message);
            assertThat(failure).hasMessage(message);
            assertThat(((BusinessException) failure).getCode()).isEqualTo(code);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @MethodSource("malformedResponses")
    void malformedResponsesNeverBecomeSuccessfulAnswers(String body) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, body))) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), FORMAT);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    static Stream<String> malformedResponses() throws Exception {
        var bodies = new java.util.ArrayList<>(List.of("", "null", "[]", "{}", "<html>" + PRIVATE + "</html>",
                SUCCESS + " {}", SUCCESS.replace("\"status\":\"completed\",\"error\"",
                        "\"status\":\"failed\",\"status\":\"completed\",\"error\"")));
        for (String mutation : List.of("对象类型", "未知状态", "状态缺失", "输出缺失", "输出为空", "输出类型",
                "消息为空", "消息类型", "用户角色", "内容缺失", "内容类型", "内容为空", "文本类型", "空白正文",
                "未知内容", "仅推理", "未知阶段", "仅进度")) {
            ObjectNode response = (ObjectNode) JSON.readTree(SUCCESS);
            ObjectNode message = (ObjectNode) response.path("output").get(1);
            ObjectNode content = (ObjectNode) message.path("content").get(0);
            switch (mutation) {
                case "对象类型" -> response.put("object", "chat.completion");
                case "未知状态" -> response.put("status", "unknown");
                case "状态缺失" -> response.remove("status");
                case "输出缺失" -> response.remove("output");
                case "输出为空" -> response.putArray("output");
                case "输出类型" -> response.putObject("output");
                case "消息为空" -> ((ArrayNode) response.path("output")).set(1, JSON.nullNode());
                case "消息类型" -> message.put("type", "function_call");
                case "用户角色" -> message.put("role", "user");
                case "内容缺失" -> message.remove("content");
                case "内容类型" -> message.putObject("content");
                case "内容为空" -> message.putArray("content");
                case "文本类型" -> content.put("text", 123);
                case "空白正文" -> message.putArray("content").addObject().put("type", "output_text").put("text", " \n");
                case "未知内容" -> content.put("type", "unknown");
                case "仅推理" -> ((ArrayNode) response.path("output")).remove(1);
                case "未知阶段" -> message.put("phase", "unknown");
                case "仅进度" -> message.put("phase", "commentary");
                default -> throw new IllegalArgumentException("未知测试变体");
            }
            bodies.add(response.toString());
        }
        return bodies.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "in_progress", "queued", "failed", "cancelled"})
    void unfinishedOrFailedResponseCannotDeliverPartialText(String status) throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(SUCCESS);
        response.put("status", status);
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, response.toString()))) {
            assertSafe(catchThrowable(() -> generate(fixture.client)),
                    status.equals("failed") || status.equals("cancelled") ? "暂不可用" : "未完整生成");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void errorObjectAndIncompleteMessageCannotClaimSuccess() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(SUCCESS);
        response.putObject("error").put("message", PRIVATE);
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, response.toString()))) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), "暂不可用");
            response.putNull("error");
            ((ObjectNode) response.path("output").get(1)).put("status", "incomplete");
            assertSafe(catchThrowable(() -> generate(fixture.client)), "未完整生成");
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @Test
    void explicitRefusalCannotBeDiscardedEvenWhenMixedWithText() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(SUCCESS);
        ((ArrayNode) response.at("/output/1/content")).addObject().put("type", "refusal").put("refusal", PRIVATE);
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, response.toString()))) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), "本次问题暂时无法回答");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json; charset=invalid["})
    void invalidMediaTypeIsSafeAndNotRetried(String type) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", type);
            respond(exchange, 200, SUCCESS);
        })) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), FORMAT);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void missingOrExhaustedDeadlinePreventsSendingAndCancellationPreservesFlag() {
        HttpClient http = mock(HttpClient.class);
        ResponsesClient client = mockClient(http);
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenReturn(Duration.ZERO);
        assertThatThrownBy(() -> client.generate("规则", "问题", 2048, null))
                .isInstanceOf(NullPointerException.class).hasMessage("任务时限不能为空");
        assertSafe(catchThrowable(() -> client.generate("规则", "问题", 2048, deadline)), TIMEOUT);
        try {
            Thread.currentThread().interrupt();
            assertSafe(catchThrowable(() -> client.generate("规则", "问题", 2048, deadline)), "本次回答已取消");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(http);
    }

    @Test
    void remainingBudgetBoundsTransportAndCancelsPendingExchange() {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        AtomicReference<Duration> actual = new AtomicReference<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            actual.set(invocation.<HttpRequest>getArgument(0).timeout().orElseThrow());
            return pending;
        });
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenReturn(Duration.ofMillis(120));
        ResponsesClient client = mockClient(http);
        long started = System.nanoTime();
        assertSafe(catchThrowable(() -> client.generate("规则", "问题", 2048, deadline)), TIMEOUT);
        assertThat(actual.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(120));
        assertThat(pending).isCancelled();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        verify(http).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void interruptedNetworkWaitCancelsOriginalExchangeAndKeepsFlag() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            sent.countDown();
            return pending;
        });
        ResponsesClient client = mockClient(http);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            failure.set(catchThrowable(() -> generate(client)));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "测试-中断模型调用");
        try {
            caller.start();
            assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertSafe(failure.get(), "本次回答已取消");
            assertThat(interrupted.get()).isTrue();
            assertThat(pending).isCancelled();
        } finally {
            caller.interrupt();
            caller.join(2000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"连接", "TLS", "超时"})
    void networkFailuresAreClassifiedWithoutRetryOrOriginalCause(String type) {
        HttpClient http = mock(HttpClient.class);
        IOException cause = switch (type) {
            case "连接" -> new ConnectException(PRIVATE);
            case "TLS" -> new SSLHandshakeException(PRIVATE);
            default -> new HttpTimeoutException(PRIVATE);
        };
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(CompletableFuture.failedFuture(cause));
        assertSafe(catchThrowable(() -> generate(mockClient(http))), type.equals("超时") ? TIMEOUT : "暂不可用");
        verify(http).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void stalledRealResponseBodyTimesOutWithoutAnotherAttempt() throws Exception {
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
            long started = System.nanoTime();
            assertSafe(catchThrowable(() -> generate(fixture.client)), TIMEOUT);
            assertThat(fixture.requests).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        } finally {
            release.countDown();
        }
    }

    @Test
    void oversizedBodyFailsSafelyWithoutRetry() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> respond(exchange, 200, SUCCESS), Duration.ofSeconds(2), 16)) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), "暂不可用");
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @Test
    void concurrentSuccessAndFailureRemainIsolated() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (Fixture fixture = new Fixture(exchange -> {
            String input = JSON.readTree(exchange.getRequestBody()).at("/input/1/content").asText();
            respond(exchange, input.equals("问题甲") ? 200 : 401, input.equals("问题甲") ? SUCCESS : PRIVATE);
        })) {
            var success = callers.submit(() -> fixture.client.generate("规则", "问题甲", 2048, budget()));
            var failure = callers.submit(() -> catchThrowable(() -> fixture.client.generate("规则", "问题乙", 2048, budget())));
            assertThat(success.get(3, TimeUnit.SECONDS)).isEqualTo("Java 面试");
            assertSafe(failure.get(3, TimeUnit.SECONDS), "暂不可用");
            assertThat(fixture.requests).hasValue(2);
        } finally {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TaskDeadline budget() {
        return TaskDeadline.start(Duration.ofSeconds(5));
    }

    @Test
    void exhaustedBudgetRejectsLateNetworkSuccessAndCancelsExchange() {
        HttpClient http = mock(HttpClient.class);
        AtomicReference<Duration> remaining = new AtomicReference<>(Duration.ofSeconds(2));
        TaskDeadline deadline = mock(TaskDeadline.class);
        when(deadline.remaining()).thenAnswer(invocation -> remaining.get());
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")),
                (name, value) -> true));
        when(response.body()).thenReturn(SUCCESS.getBytes(StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation -> {
            remaining.set(Duration.ZERO);
            return exchange;
        });
        ResponsesClient client = mockClient(http);
        assertSafe(catchThrowable(() -> client.generate("规则", "问题", 2048, deadline)), TIMEOUT);
        assertThat(exchange).isCancelled();
        assertThat(exchange.complete(response)).isFalse();
        verify(http).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    @Test
    void missingContentTypeCannotClaimSuccess() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            try (exchange) {
                byte[] bytes = SUCCESS.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        })) {
            assertSafe(catchThrowable(() -> generate(fixture.client)), FORMAT);
            assertThat(fixture.requests).hasValue(1);
        }
    }

    private static String generate(ResponsesClient client) {
        return client.generate("服务端规则", "用户问题和资料", 2048, budget());
    }

    private static void assertSafe(Throwable failure, String message) {
        assertThat(failure).isInstanceOf(BusinessException.class).hasMessageContaining(message).hasNoCause();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(KEY, PRIVATE, "用户问题和资料", "服务端规则");
    }

    private static RagApiProperties settings() {
        RagApiProperties properties = new RagApiProperties();
        properties.getLlm().setBaseUrl("https://example.com/v1");
        properties.getLlm().setApiKey(KEY);
        properties.getLlm().setModel("test-model");
        return properties;
    }

    private static ResponsesClient mockClient(HttpClient http) {
        RestClient rest = RestClient.builder().baseUrl("https://example.com/v1")
                .requestFactory(new BoundedJdkClientHttpRequestFactory(http, Duration.ofSeconds(2), 4096)).build();
        return new ResponsesClient(rest, settings(), JSON);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicInteger requests = new AtomicInteger();
        private final RagApiProperties settings = settings();
        private final ResponsesClient client;

        private Fixture(HttpHandler handler) throws IOException {
            this(handler, Duration.ofSeconds(2), 4096);
        }

        private Fixture(HttpHandler handler, Duration timeout, int maxBytes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/responses", exchange -> {
                requests.incrementAndGet();
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                exchange.setStreams(new java.io.ByteArrayInputStream(bytes), null);
                handler.handle(exchange);
            });
            server.start();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            // 仅测试传输使用本地 HTTP；对外配置仍按生产规则校验 HTTPS 基础地址。
            RestClient rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .requestFactory(new BoundedJdkClientHttpRequestFactory(http, timeout, maxBytes)).build();
            client = new ResponsesClient(rest, settings, JSON);
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
