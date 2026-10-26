package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.LocalEmbeddingClient.ModelSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/** 元数据协议与故障注入；不安装模型、不推理，不将快照等同于执行时权重锁。 */
class LocalEmbeddingModelSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String digest = "a".repeat(64);
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private ExecutorService workers;
    private String url;
    private volatile Function<Request, Reply> handler;

    record Request(String method, String path, JsonNode body) { }
    record Reply(int status, String type, String body, long delayMs) { }

    @BeforeEach void start() throws Exception {
        workers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        handler = request -> ok(request.path().equals("/api/tags") ? tags("synthetic:1", digest) : show());
        server.createContext("/", exchange -> {
            try {
                byte[] input = exchange.getRequestBody().readAllBytes();
                var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        input.length == 0 ? json.nullNode() : json.readTree(input));
                requests.add(request);
                Reply reply = handler.apply(request);
                if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
                if (reply.type() != null) exchange.getResponseHeaders().set("Content-Type", reply.type());
                if (reply.status() == 302) exchange.getResponseHeaders().set("Location", "/must-not-follow");
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* 超时用例主动断连，禁止继续输出正文。 */ }
            finally { exchange.close(); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }

    private LocalEmbeddingClient client() { return new LocalEmbeddingClient(json, true, url, "synthetic:1"); }
    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(3)); }
    private ObjectNode tags(String name, String revision) {
        return json.valueToTree(Map.of("models", List.of(Map.of("name", name, "model", name, "digest", revision))));
    }
    private ObjectNode show() {
        return json.valueToTree(Map.of("capabilities", List.of("embedding"), "model_info",
                Map.of("general.architecture", "bert", "bert.embedding_length", 768)));
    }
    private Reply ok(Object value) {
        try { return new Reply(200, "application/json", json.writeValueAsString(value), 0); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }
    private void rejects(Runnable call, int code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code)).hasMessageNotContaining("SECRET");
    }

    @Test void readsInstalledRevisionWithoutEmbeddingPullOrPrivateText() {
        ModelSnapshot snapshot = client().snapshot(deadline());
        assertThat(snapshot).isEqualTo(new ModelSnapshot("synthetic:1", digest, 768));
        assertThat(snapshot.toString()).isEqualTo("ModelSnapshot[dimensions=768]");
        assertThat(requests).extracting(Request::path).containsExactly("/api/tags", "/api/show", "/api/tags");
        assertThat(requests).extracting(Request::method).containsExactly("GET", "POST", "GET");
        assertThat(requests.get(1).body()).isEqualTo(json.valueToTree(Map.of("model", "synthetic:1", "verbose", false)));
        assertThat(requests.get(0).body().isNull()).isTrue();
    }

    @Test void acceptsExplicitlyEmptyRemoteMarkersAndEmptyChildManifests() {
        ObjectNode info = show();
        info.put("remote_host", "").put("remote_model", "").putArray("manifests");
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        assertThat(client().snapshot(deadline()).dimensions()).isEqualTo(768);
    }

    @Test void omittedTagMatchesOnlyExactLatestNameAndKeepsConfiguredSpelling() {
        handler = request -> ok(request.path().equals("/api/tags") ? tags("team/synthetic:latest", digest) : show());
        var configured = new LocalEmbeddingClient(json, true, url, "team/synthetic");
        assertThat(configured.snapshot(deadline()).model()).isEqualTo("team/synthetic");
        assertThat(requests.get(1).body().path("model").textValue()).isEqualTo("team/synthetic");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2048})
    void acceptsNativeDimensionBounds(int dimension) {
        ObjectNode info = show(); ((ObjectNode) info.path("model_info")).put("bert.embedding_length", dimension);
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        assertThat(client().snapshot(deadline()).dimensions()).isEqualTo(dimension);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "bad name", "x\nSECRET", "x?token=SECRET"})
    void invalidConfigurationNeverStartsHttp(String name) {
        rejects(() -> new LocalEmbeddingClient(json, true, url, name).snapshot(deadline()), 503);
        assertThat(requests).isEmpty();
    }

    @Test void disabledNullOrOversizedModelNeverStartsHttp() {
        rejects(() -> new LocalEmbeddingClient(json, false, url, "synthetic:1").snapshot(deadline()), 503);
        rejects(() -> new LocalEmbeddingClient(json, true, url, null).snapshot(deadline()), 503);
        rejects(() -> new LocalEmbeddingClient(json, true, url, "x".repeat(129)).snapshot(deadline()), 503);
        assertThat(requests).isEmpty();
    }

    @Test void nullBudgetAndExpiredBudgetNeverStartHttp() {
        var configured = client();
        rejects(() -> configured.snapshot(null), 400);
        AtomicLong time = new AtomicLong();
        TaskDeadline expired = TaskDeadline.start(Duration.ofSeconds(1), Clock.systemUTC(), time::get);
        time.set(TimeUnit.SECONDS.toNanos(1));
        rejects(() -> configured.snapshot(expired), 503);
        assertThat(requests).isEmpty();
    }

    @Test void interruptedThreadIsPreservedWithoutHttp() {
        var configured = client();
        Thread.currentThread().interrupt();
        try {
            rejects(() -> configured.snapshot(deadline()), 503);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(requests).isEmpty();
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(strings = {"{\"models\":[]}", "{}", "{\"models\":null}",
            "{\"models\":[null]}", "{\"models\":[{}]}", "{\"models\":[{\"name\":1}]}",
            "{\"models\":[{\"name\":\"synthetic:10\",\"model\":\"synthetic:10\",\"digest\":\"SECRET\"}]}"})
    void missingOrMalformedModelIsNotDownloaded(String body) {
        handler = request -> new Reply(200, "application/json", body, 0);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"", "SECRET", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void rejectsMalformedRevisionWithoutCallingShow(String revision) {
        handler = request -> ok(tags("synthetic:1", revision));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void duplicateMatchingModelsAreAmbiguous() {
        ObjectNode info = tags("synthetic:1", digest);
        ((com.fasterxml.jackson.databind.node.ArrayNode) info.path("models")).add(info.path("models").get(0).deepCopy());
        handler = request -> ok(info);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void nameAndModelMustAgree() {
        ObjectNode info = tags("synthetic:1", digest);
        ((ObjectNode) info.path("models").get(0)).put("model", "SECRET:other");
        handler = request -> ok(info);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void unrelatedModelRevisionDoesNotChangeSelectedSnapshot() {
        ObjectNode info = tags("synthetic:1", digest);
        ((com.fasterxml.jackson.databind.node.ArrayNode) info.path("models"))
                .add(tags("other:1", "b".repeat(64)).path("models").get(0));
        handler = request -> ok(request.path().equals("/api/tags") ? info : show());
        assertThat(client().snapshot(deadline()).revision()).isEqualTo(digest);
    }

    @ParameterizedTest @ValueSource(strings = {"remote_host", "remote_model"})
    void remoteTagsNeverReachShow(String field) {
        ObjectNode info = tags("synthetic:1", digest);
        ((ObjectNode) info.path("models").get(0)).put(field, "SECRET_REMOTE");
        handler = request -> ok(info);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"remote_host", "remote_model"})
    void remoteShowNeverReachesSecondTags(String field) {
        ObjectNode info = show(); info.put(field, "SECRET_REMOTE");
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "1", "false", "{}", "[]"})
    void invalidRemoteMarkerIsNotTreatedAsLocal(String value) throws Exception {
        ObjectNode info = show(); info.set("remote_host", json.readTree(value));
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"capabilities\":[]}", "{\"capabilities\":[\"completion\"]}",
            "{\"capabilities\":[1,\"embedding\"]}", "{\"capabilities\":[\"embedding\"],\"model_info\":{}}",
            "{\"capabilities\":[\"embedding\"],\"model_info\":{\"general.architecture\":\"../SECRET\"}}"})
    void unknownCapabilityAndMetadataNeverGuessDimensions(String body) {
        handler = request -> request.path().equals("/api/show") ? new Reply(200, "application/json", body, 0)
                : ok(tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "2049", "2147483648", "1.5", "\"768\"", "null"})
    void invalidNativeDimensionsAreNotCoercedOrTruncated(String dimension) throws Exception {
        ObjectNode info = show(); ((ObjectNode) info.path("model_info")).set("bert.embedding_length", json.readTree(dimension));
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(2);
    }

    @Test void childManifestModelIsRejectedUntilRunnerIdentityIsSupported() {
        ObjectNode info = show(); info.putArray("manifests").addObject().put("digest", "b".repeat(64));
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
    }

    @Test void excessiveInstalledModelsDoNotCreateUnboundedSelectionWork() {
        ObjectNode info = tags("synthetic:1", digest);
        var models = (com.fasterxml.jackson.databind.node.ArrayNode) info.path("models");
        for (int i = 0; i < 256; i++) models.add(tags("other:" + i, digest).path("models").get(0));
        handler = request -> ok(info);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void excessiveCapabilitiesDoNotBypassMetadataBounds() {
        ObjectNode info = show();
        var capabilities = (com.fasterxml.jackson.databind.node.ArrayNode) info.path("capabilities");
        for (int i = 0; i < 32; i++) capabilities.add("other");
        handler = request -> ok(request.path().equals("/api/show") ? info : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(2);
    }

    @Test void snapshotCanBindExistingIndexContractWithoutGuessingRevision() {
        var snapshot = client().snapshot(deadline());
        var index = new com.ragqa.rag.retrieval.QdrantVectorClient.Index("ko_snapshot_unit",
                snapshot.model(), snapshot.revision(), snapshot.dimensions());
        assertThat(index.revision()).isEqualTo(digest);
        assertThat(index.dimensions()).isEqualTo(768);
    }

    @Test void manuallyConstructedSnapshotValidatesShapeButIsNotAuthorizationProof() {
        assertThatThrownBy(() -> new ModelSnapshot(null, digest, 768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSnapshot("synthetic:1", null, 768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSnapshot("synthetic:1", "SECRET", 768))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
        assertThatThrownBy(() -> new ModelSnapshot("synthetic:1", digest, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSnapshot("synthetic:1", digest, 2049)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void modelRevisionChangedDuringInspectionIsConflict() {
        handler = request -> ok(request.path().equals("/api/show") ? show()
                : tags("synthetic:1", requests.size() == 3 ? "b".repeat(64) : digest));
        rejects(() -> client().snapshot(deadline()), 409);
        assertThat(requests).hasSize(3);
    }

    @Test void disappearingModelCannotReturnOldSnapshot() {
        handler = request -> ok(request.path().equals("/api/show") ? show()
                : requests.size() == 3 ? Map.of("models", List.of()) : tags("synthetic:1", digest));
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(3);
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 403, 404, 429, 500, 503})
    void firstCallFailureNeverRetriesRedirectsOrLeaksBody(int status) {
        handler = request -> new Reply(status, "application/json", "SECRET_UPSTREAM", 0);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void laterFailureNeverReturnsPartialSnapshot(int failAt) {
        handler = request -> requests.size() == failAt ? new Reply(403, "application/json", "SECRET", 0)
                : ok(request.path().equals("/api/tags") ? tags("synthetic:1", digest) : show());
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(failAt);
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "[]", "{} {}", "{\"models\":[],\"models\":[]}"})
    void invalidJsonNeverProducesSnapshot(String body) {
        handler = request -> new Reply(200, "application/json", body, 0);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void wrongMediaTypeAndOversizedResponseAreBounded() {
        handler = request -> new Reply(200, "text/html", "SECRET", 0);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
        requests.clear();
        handler = request -> new Reply(200, "application/json", " ".repeat(2 * 1024 * 1024 + 1), 0);
        rejects(() -> client().snapshot(deadline()), 503);
        assertThat(requests).hasSize(1);
    }

    @Test void allThreeCallsShareParentBudgetWithoutRenewal() {
        AtomicLong ticker = new AtomicLong();
        TaskDeadline parent = TaskDeadline.start(Duration.ofSeconds(2), Clock.systemUTC(), ticker::get);
        handler = request -> {
            ticker.addAndGet(TimeUnit.MILLISECONDS.toNanos(1100));
            return ok(request.path().equals("/api/tags") ? tags("synthetic:1", digest) : show());
        };
        rejects(() -> client().snapshot(parent), 503);
        assertThat(requests).hasSize(2);
        assertThat(parent.remaining()).isZero();
    }

    @Test void metadataStageIsFiveSecondsTotalNotFiveSecondsPerCall() {
        AtomicLong ticker = new AtomicLong();
        TaskDeadline parent = TaskDeadline.start(Duration.ofSeconds(20), Clock.systemUTC(), ticker::get);
        handler = request -> {
            ticker.addAndGet(TimeUnit.SECONDS.toNanos(3));
            return ok(request.path().equals("/api/tags") ? tags("synthetic:1", digest) : show());
        };
        rejects(() -> client().snapshot(parent), 503);
        assertThat(requests).hasSize(2);
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(14));
    }

    @Test void slowBodyStopsWaitingUnderParentBudget() {
        handler = request -> new Reply(200, "application/json", ok(tags("synthetic:1", digest)).body(), 1500);
        var configured = client();
        long started = System.nanoTime();
        rejects(() -> configured.snapshot(TaskDeadline.start(Duration.ofMillis(180))), 503);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(requests.size()).isLessThanOrEqualTo(1);
    }

    @Test void concurrentSnapshotsDoNotShareIntermediateState() throws Exception {
        var configured = client();
        var callers = Executors.newFixedThreadPool(4);
        try {
            var results = callers.invokeAll(java.util.stream.IntStream.range(0, 4)
                    .<java.util.concurrent.Callable<ModelSnapshot>>mapToObj(i -> () -> configured.snapshot(deadline())).toList());
            for (var result : results) assertThat(result.get()).isEqualTo(new ModelSnapshot("synthetic:1", digest, 768));
        } finally { callers.shutdownNow(); }
        assertThat(requests).hasSize(12);
    }
}
