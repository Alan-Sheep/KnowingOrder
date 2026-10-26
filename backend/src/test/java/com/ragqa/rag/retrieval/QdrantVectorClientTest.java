package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/** 回环HTTP故障测试；不连接开发Qdrant、不调用模型，不证明真实语义召回效果。 */
class QdrantVectorClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Index index = new Index("ko_contract_unit", "synthetic:v1", "a".repeat(64), 2);
    private final Scope scope = new Scope(1, 2, 3, 4, "00000000-0000-0000-0000-000000000001");
    private final Vector vector = new Vector("synthetic:v1", List.of(3.0, 4.0));
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private ExecutorService workers;
    private QdrantVectorClient client;
    private String url;
    private volatile Function<Request, Reply> handler;

    record Request(String method, String uri, JsonNode body) { }
    record Reply(int status, String type, String body, long delayMs) { }

    @BeforeEach void start() throws Exception {
        workers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("status", "completed"));
        server.createContext("/", exchange -> {
            try {
                byte[] input = exchange.getRequestBody().readAllBytes();
                var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        input.length == 0 ? json.nullNode() : json.readTree(input));
                requests.add(request);
                Reply reply = handler.apply(request);
                if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                if (reply.type() != null) exchange.getResponseHeaders().set("Content-Type", reply.type());
                if (reply.status() >= 300 && reply.status() < 400) exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* 超时测试主动关闭连接，服务端不再写正文。 */ }
            finally { exchange.close(); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new QdrantVectorClient(json, true, url);
    }

    @AfterEach void stop() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Test void createBindsModelRevisionDimensionAndCosine() {
        handler = request -> ok(true);
        client.create(index, deadline());
        assertThat(requests).hasSize(1);
        Request request = requests.get(0);
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.uri()).isEqualTo("/collections/ko_contract_unit");
        assertThat(request.body().at("/vectors/size").intValue()).isEqualTo(2);
        assertThat(request.body().at("/vectors/distance").textValue()).isEqualTo("Cosine");
        assertThat(request.body().at("/metadata/revision").textValue()).isEqualTo(index.revision());
        assertThat(request.body().at("/metadata/model").textValue()).isEqualTo(index.model());
    }

    @Test void inspectDistinguishesMissingFromBrokenService() {
        assertThat(client.inspect(index, deadline())).isTrue();
        handler = request -> reply(404, "SECRET_NOT_FOUND");
        assertThat(client.inspect(index, deadline())).isFalse();
        handler = request -> reply(503, "SECRET_DOWN");
        assertThatThrownBy(() -> client.inspect(index, deadline())).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test void disabledClientNeverStartsHttp() {
        var disabled = new QdrantVectorClient(json, false, url);
        assertThatThrownBy(() -> disabled.create(index, deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> disabled.inspect(index, deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> disabled.query(index, scope, vector, 0, 1, deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> disabled.read(index, scope, List.of(1L), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> disabled.upsert(index, scope, List.of(point(1)), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> disabled.deleteCollection(index, deadline())).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"https://127.0.0.1:6333", "http://localhost:6333", "http://example.com",
            "http://user:SECRET@127.0.0.1:6333", "http://127.0.0.1:6333/api", "http://127.0.0.1?x=1", "http://127.0.0.1#x"})
    void rejectsPublicCredentialedOrAmbiguousEndpoints(String endpoint) {
        assertThatThrownBy(() -> new QdrantVectorClient(json, true, endpoint)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET");
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"../collections", "real_business", "ko_x/y", "ko_x?x=1", "ko_", "ko_X"})
    void rejectsUnownedOrUnsafeCollectionNames(String collection) {
        assertThatThrownBy(() -> new Index(collection, index.model(), index.revision(), 2)).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @Test void rejectsInvalidIndexAndScopeFields() {
        assertThatThrownBy(() -> new Index(index.collection(), "", index.revision(), 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Index(index.collection(), index.model(), "mutable-tag", 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Index(index.collection(), index.model(), index.revision(), 2049)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Scope(0, 2, 3, 4, scope.generation())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Scope(1, 2, 3, 4, "not-uuid")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.inspect(null, deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.inspect(index, null)).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"model", "revision", "distance", "size", "named", "missing"})
    void refusesMismatchedExistingCollectionBeforeMutation(String change) {
        ObjectNode result = json.valueToTree(info());
        ObjectNode metadata = (ObjectNode) result.at("/config/metadata");
        ObjectNode vectors = (ObjectNode) result.at("/config/params/vectors");
        switch (change) {
            case "model", "revision" -> metadata.put(change, "other");
            case "distance" -> vectors.put("distance", "Dot");
            case "size" -> vectors.put("size", "2");
            case "named" -> ((ObjectNode) result.at("/config/params")).set("vectors", json.createObjectNode().set("named", vectors));
            case "missing" -> ((ObjectNode) result.path("config")).remove("metadata");
        }
        handler = request -> ok(result);
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(point(1)), deadline()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        assertThat(requests).hasSize(1).allMatch(request -> request.method().equals("GET"));
    }

    @Test void upsertNormalizesAndSendsOnlyLocatorsWithStableIds() {
        client.upsert(index, scope, List.of(point(7)), deadline());
        JsonNode first = requests.get(1).body().path("points").get(0);
        assertThat(first.path("vector").get(0).doubleValue()).isEqualTo(0.6);
        assertThat(first.path("vector").get(1).doubleValue()).isEqualTo(0.8);
        assertThat(first.at("/payload/chunk_id").textValue()).isEqualTo("7");
        assertThat(first.path("payload").has("content")).isFalse();
        assertThat(first.at("/payload/vector_sha256").textValue()).isEqualTo(QdrantVectorClient.vectorSha256(vector));
        assertThat(requests.get(1).uri()).endsWith("/points?wait=true&ordering=strong");
        client.upsert(index, scope, List.of(point(7)), deadline());
        assertThat(requests.get(3).body().path("points").get(0).path("id")).isEqualTo(first.path("id"));
        client.upsert(index, new Scope(9, 2, 3, 4, scope.generation()), List.of(point(7)), deadline());
        assertThat(requests.get(5).body().path("points").get(0).path("id")).isNotEqualTo(first.path("id"));
    }

    @Test void hugeAndSubnormalVectorsBecomeFiniteNonzeroUnitVectors() {
        for (double value : new double[] {Double.MAX_VALUE, Double.MIN_VALUE}) {
            client.upsert(index, scope, List.of(new Point(1, "b".repeat(64), new Vector(index.model(), List.of(value, value)))), deadline());
        }
        for (int i : new int[] {1, 3}) {
            JsonNode values = requests.get(i).body().path("points").get(0).path("vector");
            assertThat(values.get(0).doubleValue()).isCloseTo(Math.sqrt(0.5), within(1e-15));
        }
    }

    @Test void validatesWholeBatchBeforeAnyHttpIncludingInvalidTail() {
        var batch = new ArrayList<Point>();
        batch.add(point(1)); batch.add(new Point(2, "b".repeat(64), new Vector(index.model(), List.of(0.0, 0.0))));
        assertThatThrownBy(() -> client.upsert(index, scope, batch, deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(point(1), point(1)), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.upsert(index, scope, Collections.nCopies(17, point(1)), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.upsert(index, null, List.of(point(1)), deadline())).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @Test void invalidVectorsNeverLeaveProcess() {
        for (Vector bad : List.of(new Vector("other", List.of(1.0, 2.0)), new Vector(index.model(), List.of(1.0)),
                new Vector(index.model(), List.of(Double.NaN, 1.0)), new Vector(index.model(), List.of(Double.POSITIVE_INFINITY, 1.0)))) {
            assertThatThrownBy(() -> client.query(index, scope, bad, 0, 2, deadline())).isInstanceOf(BusinessException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test void queryUsesMandatoryFilterAndReturnsImmutableLocatedCandidates() {
        JsonNode row = seed();
        ((ObjectNode) row).put("score", 0.8);
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("points", List.of(row)));
        var hits = client.query(index, scope, vector, 0.7, 3, deadline());
        assertThat(hits).extracting(QdrantVectorClient.Hit::chunkId).containsExactly(7L);
        JsonNode body = requests.get(1).body();
        assertThat(body.at("/filter/must")).hasSize(7);
        assertThat(body.at("/filter/must").findValuesAsText("key"))
                .containsExactlyInAnyOrder("enterprise_id", "space_id", "document_id", "version_id", "generation", "model", "model_revision");
        assertThat(body.path("with_vector").booleanValue()).isFalse();
        assertThat(body.path("score_threshold").doubleValue()).isEqualTo(0.7);
        assertThatThrownBy(() -> hits.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void missingCollectionNeverPretendsNoEvidenceOrCreatesAutomatically() {
        handler = request -> reply(404, "missing");
        assertThatThrownBy(() -> client.query(index, scope, vector, 0, 2, deadline()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        assertThat(requests).hasSize(1).allMatch(request -> request.method().equals("GET"));
    }

    @Test void emptyResultsAreValidButMissingPointsFieldIsNot() {
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("points", List.of()));
        assertThat(client.query(index, scope, vector, -1, 24, deadline())).isEmpty();
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of());
        assertThatThrownBy(() -> client.query(index, scope, vector, 0, 1, deadline())).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"enterprise_id", "space_id", "document_id", "version_id", "generation", "model", "model_revision", "chunk_id", "content_sha256", "vector_sha256", "id", "score", "duplicate", "limit"})
    void rejectsForeignTamperedAndDuplicateHits(String field) {
        ObjectNode row = (ObjectNode) seed(); row.put("score", 0.8);
        if (field.equals("id")) row.put("id", "00000000-0000-0000-0000-000000000000");
        else if (field.equals("score")) row.put("score", "0.8");
        else if (!NON_PAYLOAD_FIELDS.contains(field)) ((ObjectNode) row.path("payload")).put(field, "wrong");
        List<JsonNode> rows = field.equals("duplicate") || field.equals("limit") ? List.of(row, row) : List.of(row);
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("points", rows));
        assertThatThrownBy(() -> client.query(index, scope, vector, 0, field.equals("limit") ? 1 : 3, deadline()))
                .isInstanceOf(BusinessException.class);
        assertThat(requests).hasSize(2);
    }
    private static final java.util.Set<String> NON_PAYLOAD_FIELDS = java.util.Set.of("duplicate", "limit");

    @ParameterizedTest @ValueSource(doubles = {-2, 2, 0.4})
    void rejectsOutOfRangeAndBelowThresholdScores(double score) {
        ObjectNode row = (ObjectNode) seed(); row.put("score", score);
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("points", List.of(row)));
        assertThatThrownBy(() -> client.query(index, scope, vector, 0.7, 3, deadline())).isInstanceOf(BusinessException.class);
    }

    @Test void readOnlyAcceptsRequestedScopedIdsAndKeepsMissingEntriesMissing() {
        JsonNode row = seed();
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(List.of(row));
        var found = client.read(index, scope, List.of(7L, 8L), deadline());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).chunkId()).isEqualTo(7);
        assertThat(found.get(0).vectorSha256()).isEqualTo(QdrantVectorClient.vectorSha256(vector));
        assertThat(requests.get(1).body().path("ids")).hasSize(2);
        assertThatThrownBy(() -> client.read(index, scope, List.of(8L), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> found.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void validatesReadAndQueryLimitsBeforeHttp() {
        assertThatThrownBy(() -> client.read(index, scope, List.of(1L, 1L), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.read(index, scope, List.of(-1L), deadline())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.read(index, scope, List.of(), deadline())).isInstanceOf(BusinessException.class);
        for (int limit : new int[] {0, 25}) assertThatThrownBy(() -> client.query(index, scope, vector, 0, limit, deadline())).isInstanceOf(BusinessException.class);
        for (double threshold : new double[] {-2, 2, Double.NaN}) assertThatThrownBy(() -> client.query(index, scope, vector, threshold, 1, deadline())).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {301, 307, 401, 403, 404, 409, 429, 500, 503})
    void statusFailuresDoNotRetryFollowRedirectsOrExposeRemoteBody(int code) {
        handler = request -> reply(code, "SECRET_BODY_TOKEN");
        assertThatThrownBy(() -> client.create(index, deadline())).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("SECRET");
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{\"status\":\"ok\",\"result\":{}} trailing",
            "{\"status\":\"ok\",\"status\":\"ok\",\"result\":true}", "{\"status\":{},\"result\":true}"})
    void malformedJsonWritesRemainUnconfirmed(String body) {
        handler = request -> reply(200, body);
        assertThatThrownBy(() -> client.create(index, deadline())).isInstanceOf(BusinessException.class).hasMessageContaining("尚未确认");
        assertThat(requests).hasSize(1);
    }

    @Test void nonCompletedUpsertIsNotSuccess() {
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(Map.of("status", "acknowledged"));
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(point(1)), deadline()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未确认");
        assertThat(requests).hasSize(2);
    }

    @Test void wrongContentTypeAndOversizedBodiesAreStopped() {
        handler = request -> new Reply(200, "text/html", "SECRET_HTML", 0);
        assertThatThrownBy(() -> client.create(index, deadline())).isInstanceOf(BusinessException.class).hasMessageNotContaining("SECRET");
        handler = request -> reply(200, " ".repeat(256 * 1024 + 1));
        assertThatThrownBy(() -> client.create(index, deadline())).isInstanceOf(BusinessException.class).hasMessageContaining("尚未确认");
        assertThat(requests).hasSize(2);
    }

    @Test void sharedDeadlineCoversCollectionCheckAndMutationTogether() {
        client.inspect(index, deadline()); requests.clear();
        handler = request -> new Reply(200, "application/json", request.method().equals("GET") ? ok(info()).body() : ok(true).body(),
                request.method().equals("GET") ? 100 : 1000);
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(point(1)), TaskDeadline.start(Duration.ofMillis(400))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未确认");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).hasSize(2);
    }

    @Test void slowInspectionPreventsFollowingMutation() {
        handler = request -> new Reply(200, "application/json", ok(info()).body(), 800);
        assertThatThrownBy(() -> client.upsert(index, scope, List.of(point(1)), TaskDeadline.start(Duration.ofMillis(200))))
                .isInstanceOf(BusinessException.class);
        assertThat(requests).hasSize(1);
    }

    @Test void expiredDeadlineNeverStartsHttp() {
        TaskDeadline expired = TaskDeadline.start(Duration.ofNanos(1));
        assertThatThrownBy(() -> client.inspect(index, expired)).isInstanceOf(BusinessException.class);
        assertThat(requests).isEmpty();
    }

    @Test void timedOutWriteIsUnknownAndNeverRepeated() {
        handler = request -> new Reply(200, "application/json", ok(true).body(), 800);
        assertThatThrownBy(() -> client.create(index, TaskDeadline.start(Duration.ofMillis(200))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未确认");
        assertThat(requests).hasSize(1);
    }

    @Test void deletionChecksOwnershipMetadataBeforeCallingDelete() {
        handler = request -> request.method().equals("GET") ? ok(info()) : ok(true);
        client.deleteCollection(index, deadline());
        assertThat(requests).extracting(Request::method).containsExactly("GET", "DELETE");
    }

    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(4)); }
    private Point point(long id) { return new Point(id, "b".repeat(64), vector); }
    private Map<String, Object> info() {
        return Map.of("config", Map.of("params", Map.of("vectors", Map.of("size", 2, "distance", "Cosine")),
                "metadata", Map.of("schema", "knowingorder-vector-v1", "model", index.model(), "revision", index.revision())));
    }
    private Reply ok(Object result) {
        try { return reply(200, json.writeValueAsString(Map.of("status", "ok", "result", result))); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }
    private Reply reply(int status, String body) { return new Reply(status, "application/json", body, 0); }
    private JsonNode seed() {
        client.upsert(index, scope, List.of(point(7)), deadline());
        JsonNode row = requests.get(1).body().path("points").get(0).deepCopy();
        requests.clear();
        return row;
    }
}
