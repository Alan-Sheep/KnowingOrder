package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 回环HTTP验证真实传输契约；响应为测试数据，不安装模型、不计作真实Embedding效果。 */
class LocalEmbeddingClientTest {
    private HttpServer server;
    private String url;
    private final AtomicInteger calls = new AtomicInteger();
    private String body = "{\"model\":\"synthetic:1\",\"embeddings\":[[0.3,0.4]]}";
    private int status = 200;
    private long delayMs;
    private String request;
    private final ObjectMapper json = new ObjectMapper();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            calls.incrementAndGet(); request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { Thread.sleep(delayMs); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    private LocalEmbeddingClient client() { return new LocalEmbeddingClient(json, true, url, "synthetic:1"); }
    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(3)); }
    @Test void sendsBatchWithoutTruncationAndReturnsImmutableFiniteVectors() throws Exception {
        var vectors = client().embed(List.of("虚构餐补制度"), deadline());
        assertThat(vectors.get(0).values()).containsExactly(0.3, 0.4);
        assertThat(json.readTree(request).path("truncate").booleanValue()).isFalse();
        assertThat(json.readTree(request).path("input").get(0).asText()).isEqualTo("虚构餐补制度");
        assertThatThrownBy(() -> vectors.get(0).values().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void disabledOrMissingModelNeverCallsServer() {
        assertThatThrownBy(() -> new LocalEmbeddingClient(json, false, url, "synthetic:1").embed(List.of("文本"), deadline()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LocalEmbeddingClient(json, true, url, "").embed(List.of("文本"), deadline()))
                .isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"https://example.com", "http://localhost:11434", "http://127.0.0.1/api", "http://user:secret@127.0.0.1", "http://127.0.0.1?x=1"})
    void rejectsNonLocalOrAmbiguousAddresses(String endpoint) {
        assertThatThrownBy(() -> new LocalEmbeddingClient(json, true, endpoint, "synthetic:1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThat(calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"", "{}", "{\"model\":\"other\",\"embeddings\":[[1]]}",
            "{\"model\":\"synthetic:1\",\"embeddings\":[]}", "{\"model\":\"synthetic:1\",\"embeddings\":[[0,0]]}",
            "{\"model\":\"synthetic:1\",\"embeddings\":[[\"1\"]]}", "{\"model\":\"synthetic:1\",\"embeddings\":[[]]}",
            "{\"model\":\"synthetic:1\",\"embeddings\":[[1e300]]}", "{\"model\":\"synthetic:1\",\"embeddings\":[[1],[1]]}"})
    void rejectsEmptyMalformedWrongModelAndBadDimensions(String response) {
        body = response;
        assertThatThrownBy(() -> client().embed(List.of("文本"), deadline())).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void upstreamFailureNeverRetriesOrLeaksBody(int code) {
        status = code; body = "SECRET_RESPONSE_TOKEN";
        assertThatThrownBy(() -> client().embed(List.of("文本"), deadline()))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("SECRET");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void badInputNeverStartsHttp() {
        assertThatThrownBy(() -> client().embed(List.of(), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client().embed(List.of("x".repeat(1201)), deadline())).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isZero();
    }
    @Test void inconsistentBatchDimensionsAreRejected() {
        body = "{\"model\":\"synthetic:1\",\"embeddings\":[[1,2],[1]]}";
        assertThatThrownBy(() -> client().embed(List.of("文本一", "文本二"), deadline()))
                .isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void oversizedResponseIsStoppedWithoutRetry() {
        body = " ".repeat(2 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> client().embed(List.of("文本"), deadline()))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining(body);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void sharedDeadlineStopsSlowServiceWithoutRetry() {
        delayMs = 1500;
        var client = client();
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.embed(List.of("文本"), TaskDeadline.start(Duration.ofMillis(150))))
                .isInstanceOf(BusinessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"a\u0000b", "a\u200bb", "\uD800"})
    void invalidCharactersNeverLeaveProcess(String text) {
        assertThatThrownBy(() -> client().embed(List.of(text), deadline())).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isZero();
    }
}
