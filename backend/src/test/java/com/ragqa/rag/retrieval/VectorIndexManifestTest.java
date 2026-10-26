package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/** 索引预期清单及回环HTTP读取故障测试；不写开发Qdrant、不调用模型，不证明READY发布。 */
class VectorIndexManifestTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Index index = new Index("ko_manifest_unit", "synthetic:v1", "a".repeat(64), 2);
    private final Scope scope = new Scope(1, 2, 3, 4, "00000000-0000-0000-0000-000000000001");
    private final String sourceHash = "b".repeat(64);
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<Integer> batches = new CopyOnWriteArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private HttpServer server;
    private ExecutorService workers;
    private QdrantVectorClient client;
    private volatile VectorIndexManifest manifest;
    private volatile UnaryOperator<List<Map<String, Object>>> alter = rows -> rows;
    private volatile int responseStatus = 200;

    @BeforeEach void start() throws Exception {
        workers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                methods.add(method + " " + exchange.getRequestURI());
                Object result;
                if (method.equals("GET")) {
                    result = Map.of("config", Map.of("params", Map.of("vectors", Map.of("size", 2, "distance", "Cosine")),
                            "metadata", Map.of("schema", "knowingorder-vector-v1", "model", index.model(), "revision", index.revision())));
                } else {
                    reads.incrementAndGet();
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    batches.add(body.path("ids").size());
                    var byId = new HashMap<String, Map<String, Object>>();
                    for (var expected : manifest.expected()) {
                        String name = index.collection() + ":" + index.revision() + ":1:2:3:4:" + scope.generation() + ":" + expected.chunkId();
                        String id = java.util.UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
                        var payload = new HashMap<String, Object>();
                        payload.putAll(Map.of("enterprise_id", "1", "space_id", "2", "document_id", "3", "version_id", "4",
                                "generation", scope.generation(), "model", index.model(), "model_revision", index.revision(),
                                "chunk_id", Long.toString(expected.chunkId()), "content_sha256", expected.contentSha256(),
                                "vector_sha256", expected.vectorSha256()));
                        byId.put(id, new HashMap<>(Map.of("id", id, "payload", payload)));
                    }
                    var rows = new ArrayList<Map<String, Object>>();
                    for (JsonNode id : body.path("ids")) if (byId.containsKey(id.textValue())) rows.add(byId.get(id.textValue()));
                    result = alter.apply(rows);
                }
                byte[] bytes = json.writeValueAsBytes(Map.of("status", "ok", "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) { /* 客户端中止后不继续写响应。 */ }
            finally { exchange.close(); }
        });
        server.start();
        client = new QdrantVectorClient(json, true, "http://127.0.0.1:" + server.getAddress().getPort());
        manifest = capture(points(17));
    }

    @AfterEach void stop() {
        Thread.interrupted();
        server.stop(0);
        workers.shutdownNow();
    }

    @Test void capturesIndependentCountAndOriginalVectorDigestWithoutKeepingVectors() throws Exception {
        var inputs = new ArrayList<>(points(2));
        Collections.reverse(inputs);
        var captured = capture(inputs);
        inputs.clear();
        assertThat(captured.index()).isEqualTo(index);
        assertThat(captured.scope()).isEqualTo(scope);
        assertThat(captured.documentRevision()).isEqualTo(7);
        assertThat(captured.sourceSha256()).isEqualTo(sourceHash);
        assertThat(captured.expected()).extracting(QdrantVectorClient.Stored::chunkId).containsExactly(1L, 2L);
        byte[] values = ByteBuffer.allocate(16).putDouble(3).putDouble(4).array();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values));
        assertThat(captured.expected().get(0).vectorSha256()).isEqualTo(hash);
        assertThatThrownBy(() -> captured.expected().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(captured.toString()).isEqualTo("VectorIndexManifest[chunks=2]");
        assertThat(methods).isEmpty();
    }

    @Test void digestIsStableAcrossInputOrdering() {
        var input = new ArrayList<>(points(17));
        String before = capture(input).sha256();
        Collections.reverse(input);
        assertThat(capture(input).sha256()).isEqualTo(before).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void everyIdentityAndContentChangeChangesDigest(int field) {
        Index changedIndex = index;
        Scope changedScope = scope;
        long revision = 7;
        String source = sourceHash;
        var input = new ArrayList<>(points(2));
        switch (field) {
            case 0 -> changedIndex = new Index("ko_other", index.model(), index.revision(), 2);
            case 1 -> { changedIndex = new Index(index.collection(), "other:v1", index.revision(), 2);
                input.replaceAll(p -> new Point(p.chunkId(), p.contentSha256(), new Vector("other:v1", p.vector().values()))); }
            case 2 -> changedIndex = new Index(index.collection(), index.model(), "c".repeat(64), 2);
            case 3 -> changedScope = new Scope(9, 2, 3, 4, scope.generation());
            case 4 -> changedScope = new Scope(1, 9, 3, 4, scope.generation());
            case 5 -> changedScope = new Scope(1, 2, 9, 4, scope.generation());
            case 6 -> changedScope = new Scope(1, 2, 3, 9, scope.generation());
            case 7 -> changedScope = new Scope(1, 2, 3, 4, "00000000-0000-0000-0000-000000000009");
            case 8 -> revision = 8;
            case 9 -> source = "c".repeat(64);
            case 10 -> input.set(0, new Point(1, "c".repeat(64), vector()));
            case 11 -> input.set(0, new Point(1, sourceHash, new Vector(index.model(), List.of(4.0, 3.0))));
            default -> throw new AssertionError();
        }
        assertThat(VectorIndexManifest.capture(changedIndex, changedScope, revision, source, 2, input).sha256())
                .isNotEqualTo(capture(points(2)).sha256());
    }

    @Test void countIdsAndDimensionsAreAlsoBound() {
        assertThat(capture(points(1)).sha256()).isNotEqualTo(capture(points(2)).sha256());
        var changedId = List.of(new Point(Long.MAX_VALUE, sourceHash, vector()));
        assertThat(capture(changedId).sha256()).isNotEqualTo(capture(points(1)).sha256());
        var wider = new Index(index.collection(), index.model(), index.revision(), 3);
        assertThat(VectorIndexManifest.capture(wider, scope, 7, sourceHash, 1,
                List.of(new Point(1, sourceHash, new Vector(index.model(), List.of(3.0, 4.0, 0.0))))).sha256())
                .isNotEqualTo(capture(points(1)).sha256());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "a", "A000000000000000000000000000000000000000000000000000000000000000", "../unsafe"})
    void rejectsInvalidSourceOrChunkHash(String hash) {
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, hash, 1, points(1)));
        invalid(() -> capture(List.of(new Point(1, hash, vector()))));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
    void rejectsInvalidExpectedCounts(int count) {
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, sourceHash, count, points(1)));
    }

    @Test void rejectsPartialDuplicateNullAndWrongRevisionInputsBeforeAnyIo() {
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, sourceHash, 17, points(16)));
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, sourceHash, 1, points(2)));
        invalid(() -> VectorIndexManifest.capture(null, scope, 7, sourceHash, 1, points(1)));
        invalid(() -> VectorIndexManifest.capture(index, null, 7, sourceHash, 1, points(1)));
        invalid(() -> VectorIndexManifest.capture(index, scope, 0, sourceHash, 1, points(1)));
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, sourceHash, 1, null));
        invalid(() -> capture(java.util.Arrays.asList((Point) null)));
        invalid(() -> capture(List.of(points(1).get(0), points(1).get(0))));
        invalid(() -> capture(List.of(new Point(0, sourceHash, vector()))));
        invalid(() -> capture(List.of(new Point(1, sourceHash, null))));
        invalid(() -> capture(List.of(new Point(1, sourceHash, new Vector("wrong", List.of(3.0, 4.0))))));
        invalid(() -> capture(List.of(new Point(1, sourceHash, new Vector(index.model(), List.of(3.0))))));
        assertThat(methods).isEmpty();
    }

    @ParameterizedTest @ValueSource(doubles = {0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsZeroOrNonFiniteVectors(double value) {
        invalid(() -> capture(List.of(new Point(1, sourceHash, new Vector(index.model(), List.of(value, 0.0))))));
    }

    @Test void countMustStillMatchAfterDefensiveCopy() {
        List<Point> changedDuringCopy = new java.util.AbstractList<>() {
            @Override public Point get(int n) { return points(1).get(0); }
            @Override public int size() { return 1; }
            @Override public Object[] toArray() { return new Object[0]; }
        };
        invalid(() -> VectorIndexManifest.capture(index, scope, 7, sourceHash, 1, changedDuringCopy));
        assertThat(methods).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {1, 16, 17, 32, 1000})
    void checksAllExpectedPointsInBoundedReadOnlyBatches(int count) {
        manifest = capture(points(count));
        AtomicInteger checks = new AtomicInteger();
        var result = manifest.verify(client, deadline(), checks::incrementAndGet);
        int total = (count + 15) / 16;
        assertThat(result.manifestSha256()).isEqualTo(manifest.sha256());
        assertThat(result.checkedChunks()).isEqualTo(count);
        assertThat(result.checkedBatches()).isEqualTo(total);
        assertThat(reads.get()).isEqualTo(total);
        assertThat(checks.get()).isEqualTo(total + 1);
        assertThat(batches).allMatch(size -> size >= 1 && size <= 16);
        assertThat(batches.stream().mapToInt(Integer::intValue).sum()).isEqualTo(count);
        assertThat(methods).allMatch(s -> s.equals("GET /collections/ko_manifest_unit")
                || s.equals("POST /collections/ko_manifest_unit/points"));
    }

    @Test void unorderedStorageRowsAreAcceptedAfterClientCanonicalization() {
        alter = rows -> { Collections.reverse(rows); return rows; };
        assertThat(manifest.verify(client, deadline(), () -> { }).checkedChunks()).isEqualTo(17);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "empty", "duplicate", "content", "vector", "scope", "unknown"})
    void neverAcceptsIncompleteOrWrongRows(String problem) {
        alter = rows -> {
            switch (problem) {
                case "missing" -> rows.remove(0);
                case "empty" -> rows.clear();
                case "duplicate" -> rows.set(1, rows.get(0));
                case "content" -> payload(rows.get(0)).put("content_sha256", "c".repeat(64));
                case "vector" -> payload(rows.get(0)).put("vector_sha256", "c".repeat(64));
                case "scope" -> payload(rows.get(0)).put("enterprise_id", "9");
                case "unknown" -> rows.get(0).put("id", "00000000-0000-0000-0000-000000000099");
                default -> throw new AssertionError();
            }
            return rows;
        };
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> { })).isInstanceOf(BusinessException.class);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test void aMissingLastBatchIsNotHiddenByEarlierSuccessfulReads() {
        alter = rows -> reads.get() == 2 ? List.of() : rows;
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> { })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止发布");
        assertThat(reads.get()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void serviceFailuresDoNotBecomeEmptyEvidenceOrAutomaticRetries(int status) {
        responseStatus = status;
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> { })).isInstanceOf(BusinessException.class);
        assertThat(methods).hasSize(1);
        assertThat(reads.get()).isZero();
    }

    @Test void guardFailureBeforeIoIsPropagatedWithoutWrapping() {
        var revoked = new BusinessException(ResultCode.NOT_FOUND, "测试撤权");
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> { throw revoked; })).isSameAs(revoked);
        assertThat(methods).isEmpty();
    }

    @Test void leaseLossBetweenBatchesStopsFurtherReads() {
        AtomicInteger guards = new AtomicInteger();
        var lost = new BusinessException(ResultCode.CONFLICT, "测试租约失效");
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> {
            if (guards.incrementAndGet() == 2) throw lost;
        })).isSameAs(lost);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test void finalGuardStillRunsAfterAllPointsWereRead() {
        manifest = capture(points(1));
        AtomicInteger guards = new AtomicInteger();
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> {
            if (guards.incrementAndGet() == 2) throw new BusinessException(ResultCode.CONFLICT, "测试最后一刻取消");
        })).isInstanceOf(BusinessException.class).hasMessageContaining("取消");
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test void exhaustedParentBudgetCannotBeRenewedByGuardOrNextBatch() {
        TaskDeadline shortBudget = TaskDeadline.start(Duration.ofMillis(50));
        assertThatThrownBy(() -> manifest.verify(client, shortBudget, () -> {
            while (!shortBudget.remaining().isZero()) Thread.onSpinWait();
        })).isInstanceOf(BusinessException.class);
        assertThat(methods).isEmpty();
    }

    @Test void interruptDoesNotSendHttpAndKeepsInterruptFlag() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> manifest.verify(client, deadline(), () -> { })).isInstanceOf(BusinessException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(methods).isEmpty();
    }

    @Test void missingVerifierDependenciesAreExplicitParameterErrors() {
        invalid(() -> manifest.verify(null, deadline(), () -> { }));
        invalid(() -> manifest.verify(client, null, () -> { }));
        invalid(() -> manifest.verify(client, deadline(), null));
        assertThat(methods).isEmpty();
    }

    @Test void concurrentVerificationDoesNotShareMutableProgress() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            var jobs = IntStream.range(0, 4).<java.util.concurrent.Callable<VectorIndexManifest.Verification>>mapToObj(
                    n -> () -> manifest.verify(client, deadline(), () -> { })).toList();
            for (var result : callers.invokeAll(jobs)) assertThat(result.get().checkedChunks()).isEqualTo(17);
            assertThat(reads.get()).isEqualTo(8);
        } finally { callers.shutdownNow(); }
    }

    private VectorIndexManifest capture(List<Point> points) {
        return VectorIndexManifest.capture(index, scope, 7, sourceHash, points.size(), points);
    }
    private List<Point> points(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(id -> new Point(id, sourceHash, vector())).toList();
    }
    private Vector vector() { return new Vector(index.model(), List.of(3.0, 4.0)); }
    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(30)); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> row) { return (Map<String, Object>) row.get("payload"); }
    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    }
}
