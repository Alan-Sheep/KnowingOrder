package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.config.RagApiProperties;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.QueryRewriteContract;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 阶段预算计算与真实回环HTTP故障注入；不读取配置密钥，也不访问模型服务或企业资料。 */
@Timeout(15)
class TaskDeadlineStageTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-18T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AtomicLong ticks = new AtomicLong(100);

    @Test
    void fiveSecondStageAfterSevenSecondsKeepsOriginalParentOrigin() {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        advance(Duration.ofSeconds(7));
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));

        assertThat(stage.receivedAt()).isEqualTo(parent.receivedAt()).isEqualTo(RECEIVED);
        assertThat(stage.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(12));
        assertThat(stage.remaining()).isEqualTo(Duration.ofSeconds(5));
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(13));
        assertThat(parent.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(20));
        advance(Duration.ofSeconds(2));
        assertThat(stage.remaining()).isEqualTo(Duration.ofSeconds(3));
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void parentWithTwoSecondsLeftDoesNotGiveStageFiveFreshSeconds() {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        advance(Duration.ofSeconds(18));
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));

        assertThat(stage.remaining()).isEqualTo(Duration.ofSeconds(2));
        assertThat(stage.deadlineAt()).isEqualTo(parent.deadlineAt());
        advance(Duration.ofSeconds(2));
        assertThat(stage.remaining()).isZero();
        assertThat(parent.remaining()).isZero();
    }

    @Test
    void stageExpiryDoesNotConsumeOrResetRemainingParentBudget() {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));
        advance(Duration.ofSeconds(5));

        assertThat(stage.remaining()).isZero();
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> stage.timeoutFor(Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class).hasNoCause();
        assertThatThrownBy(() -> stage.limitedTo(Duration.ofSeconds(5)))
                .isInstanceOf(BusinessException.class).hasNoCause();
    }

    @Test
    void nestedStagesCanTightenButCannotRenewExistingStage() {
        TaskDeadline stage = parent(Duration.ofSeconds(20)).limitedTo(Duration.ofSeconds(5));
        advance(Duration.ofSeconds(2));
        TaskDeadline longer = stage.limitedTo(Duration.ofSeconds(10));
        TaskDeadline shorter = stage.limitedTo(Duration.ofSeconds(1));

        assertThat(longer.remaining()).isEqualTo(Duration.ofSeconds(3));
        assertThat(longer.deadlineAt()).isEqualTo(stage.deadlineAt());
        assertThat(shorter.remaining()).isEqualTo(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(1));
        assertThat(shorter.remaining()).isZero();
        assertThat(stage.remaining()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void siblingStagesKeepIndependentCapsWithoutReservingParentQuota() {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        TaskDeadline first = parent.limitedTo(Duration.ofSeconds(5));
        advance(Duration.ofSeconds(2));
        TaskDeadline second = parent.limitedTo(Duration.ofSeconds(1));
        advance(Duration.ofSeconds(1));

        assertThat(first.remaining()).isEqualTo(Duration.ofSeconds(2));
        assertThat(second.remaining()).isZero();
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(17));
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void rejectsInvalidStageLimits(Duration limit) {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        assertThatThrownBy(() -> parent.limitedTo(limit))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("stageLimit 必须为正时长");
    }

    static Stream<Duration> invalidLimits() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1));
    }

    @Test
    void hugeStageLimitIsComparedBeforeNanosecondConversion() {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(stage.remaining()).isEqualTo(Duration.ofSeconds(20));
        assertThat(stage.deadlineAt()).isEqualTo(parent.deadlineAt());
    }

    @Test
    void lastNanosecondIsNotRoundedUpAndNoStageCanStartAfterExpiry() {
        TaskDeadline parent = parent(Duration.ofMillis(1));
        advance(Duration.ofNanos(999_999));
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));
        assertThat(stage.remaining()).isEqualTo(Duration.ofNanos(1));
        advance(Duration.ofNanos(1));
        assertThat(stage.remaining()).isZero();
        assertThatThrownBy(() -> parent.limitedTo(Duration.ofSeconds(5)))
                .isInstanceOf(BusinessException.class).hasNoCause();
    }

    @Test
    void signedTickerWrapDoesNotRenewStage() {
        ticks.set(Long.MAX_VALUE - 3);
        TaskDeadline parent = parent(Duration.ofNanos(20));
        advance(Duration.ofNanos(5));
        TaskDeadline stage = parent.limitedTo(Duration.ofNanos(4));
        advance(Duration.ofNanos(3));
        assertThat(stage.remaining()).isEqualTo(Duration.ofNanos(1));
        assertThat(parent.remaining()).isEqualTo(Duration.ofNanos(12));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 20, 21})
    void invalidTickerOrExpiredParentCannotProduceUsableStage(long elapsedSeconds) {
        TaskDeadline parent = parent(Duration.ofSeconds(20));
        advance(Duration.ofSeconds(elapsedSeconds));
        assertThatThrownBy(() -> parent.limitedTo(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage()).isEqualTo("本次处理已超时，请缩小问题范围后重试");
                    assertThat(error.getCause()).isNull();
                });
    }

    @ParameterizedTest(name = "{0}：父预算{1}ms，阶段{2}ms，覆盖完整HTTP等待")
    @CsvSource({"HEADERS,10000,700", "BODY,10000,700", "DRIP,10000,700", "BODY,700,5000"})
    void actualHttpCallTimesOutForEarlierStageOrParentDeadline(String mode, long parentMillis,
            long stageMillis) throws Exception {
        try (Loopback fixture = new Loopback(mode)) {
            TaskDeadline parent = TaskDeadline.start(Duration.ofMillis(parentMillis));
            TaskDeadline stage = parent.limitedTo(Duration.ofMillis(stageMillis));
            long started = System.nanoTime();
            assertThatThrownBy(() -> fixture.call(stage))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getCode()).isEqualTo(503);
                        assertThat(error.getMessage()).isEqualTo("模型回答超时，请稍后重试");
                        assertThat(error.getCause()).isNull();
                    });
            // 700ms是缩短后的故障注入预算；调度留余量，不能等到客户端4秒传输上限。
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(fixture.requests).hasValue(1);
            if (!mode.equals("HEADERS")) {
                assertThat(fixture.headersSent.getCount()).as("实际进入正文阶段").isZero();
            }
            if (mode.equals("DRIP")) {
                assertThat(fixture.chunks.get()).isGreaterThanOrEqualTo(2);
                assertThat(fixture.disconnected.await(2, TimeUnit.SECONDS))
                        .as("本地服务观察到连接取消").isTrue();
            }
            if (parentMillis > stageMillis) assertThat(parent.remaining()).isPositive();
        }
    }

    @Test
    void expiredStageRejectsBeforeSendingAnyHttpRequest() throws Exception {
        try (Loopback fixture = new Loopback("SUCCESS")) {
            TaskDeadline stage = parent(Duration.ofSeconds(20)).limitedTo(Duration.ofSeconds(5));
            advance(Duration.ofSeconds(5));
            assertThatThrownBy(() -> fixture.call(stage)).isInstanceOf(BusinessException.class)
                    .hasMessage("模型回答超时，请稍后重试").hasNoCause();
            assertThat(fixture.requests).hasValue(0);
        }
    }

    @Test
    void completedLocalResponsePassesActualTemplateParserAndContract() throws Exception {
        try (Loopback fixture = new Loopback("SUCCESS")) {
            TaskDeadline parent = TaskDeadline.start(Duration.ofSeconds(10));
            TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(3));
            JsonNode result = fixture.call(stage);
            assertThat(QueryRewriteContract.alternativeQuery("餐补如何申请？", result))
                    .isEqualTo("加班餐补申请流程");
            assertThat(fixture.requests).hasValue(1);
            assertThat(stage.deadlineAt()).isBefore(parent.deadlineAt());
        }
    }

    private TaskDeadline parent(Duration budget) {
        return TaskDeadline.start(budget, Clock.fixed(RECEIVED, ZoneOffset.UTC), ticks::get);
    }

    private void advance(Duration elapsed) {
        ticks.addAndGet(elapsed.toNanos());
    }

    /** 测试专用供应商替身，仅监听回环地址；真实ChatModel和ResponsesClient仍执行。 */
    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch headersSent = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger chunks = new AtomicInteger();
        private final ChatModel model;

        private Loopback(String mode) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(worker);
            server.createContext("/v1/responses", exchange -> {
                requests.incrementAndGet();
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    if (mode.equals("HEADERS")) release.await(5, TimeUnit.SECONDS);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    if (mode.equals("BODY") || mode.equals("DRIP")) {
                        do {
                            exchange.getResponseBody().write(' ');
                            exchange.getResponseBody().flush();
                            headersSent.countDown();
                            chunks.incrementAndGet();
                        } while (mode.equals("DRIP") && !release.await(40, TimeUnit.MILLISECONDS));
                        if (mode.equals("BODY")) release.await(5, TimeUnit.SECONDS);
                    }
                    exchange.getResponseBody().write(SUCCESS.getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (IOException error) {
                    disconnected.countDown();
                }
            });
            server.start();
            RagApiProperties properties = new RagApiProperties();
            properties.getLlm().setBaseUrl("https://model-fixture.invalid/v1");
            properties.getLlm().setApiKey("stage-test-fake-key");
            properties.getLlm().setModel("stage-test-fake-model");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            // 只有测试客户端连接本地HTTP；不读取或改变应用的真实HTTPS供应商配置。
            RestClient rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .requestFactory(new BoundedJdkClientHttpRequestFactory(http, Duration.ofSeconds(4), 4096)).build();
            model = new ChatModel(new ResponsesClient(rest, properties, JSON), new PromptRegistry(), JSON);
        }

        private JsonNode call(TaskDeadline stage) {
            // 1000仅为测试夹具输出额度；正式改写器及生产额度尚未接入。
            return model.call("rewrite", Map.of("originalQuestion", "餐补如何申请？"), 1000, stage);
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            server.stop(0);
            worker.shutdownNow();
            assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }

        private static final String SUCCESS = """
                {"object":"response","status":"completed","output":[
                  {"type":"message","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"{\\\"alternativeQuery\\\":\\\"加班餐补申请流程\\\"}"}]}]}
                """;
    }
}
