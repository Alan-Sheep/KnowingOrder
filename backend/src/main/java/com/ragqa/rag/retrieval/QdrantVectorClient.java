package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.ResultCode;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Qdrant 1.19本机REST适配器：仅存向量和定位摘要，不存原文；默认关闭且不重试。
 * 范围由上层服务核权后构造，本类不产生授权，也不决定索引就绪、发布或证据充分性。
 */
@Component
public final class QdrantVectorClient {
    private static final Duration CALL_LIMIT = Duration.ofSeconds(5);
    private static final String SCHEMA = "knowingorder-vector-v1";
    private final boolean enabled;
    private final RestClient http;
    private final ObjectReader reader;

    public record Index(String collection, String model, String revision, int dimensions) {
        public Index {
            if (collection == null || !collection.matches("ko_[a-z0-9_]{1,80}") || model == null
                    || model.isBlank() || model.length() > 128 || model.codePoints().anyMatch(Character::isISOControl)
                    || revision == null || !revision.matches("[0-9a-f]{64}") || dimensions < 1 || dimensions > 2048)
                throw invalid("向量集合、模型修订或维度不合法");
        }
    }
    public record Scope(long enterpriseId, long spaceId, long documentId, long versionId, String generation) {
        public Scope {
            if (enterpriseId <= 0 || spaceId <= 0 || documentId <= 0 || versionId <= 0
                    || generation == null || !generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                throw invalid("向量范围或索引代次不合法");
        }
    }
    public record Point(long chunkId, String contentSha256, Vector vector) { }
    public record Stored(long chunkId, String contentSha256, String vectorSha256) { }
    public record Hit(long chunkId, String contentSha256, double score) { }

    public QdrantVectorClient(ObjectMapper json,
            @Value("${knowingorder.qdrant.enabled:false}") boolean enabled,
            @Value("${knowingorder.qdrant.base-url:http://127.0.0.1:6333}") String baseUrl) {
        this.enabled = enabled;
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("本机Qdrant地址格式不正确"); }
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Qdrant只接受本机回环HTTP根地址，不接受公网、凭据或路径");
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(new BoundedJdkClientHttpRequestFactory(
                transport, CALL_LIMIT, 256 * 1024)).build();
        reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /** 只创建，不接管、覆盖或自动重建已有集合；写入不确定时调用inspect核对。 */
    public void create(Index index, TaskDeadline deadline) {
        TaskDeadline stage = stage(index, deadline);
        var metadata = Map.of("schema", SCHEMA, "model", index.model(), "revision", index.revision());
        JsonNode result = call(HttpMethod.PUT, path(index), Map.of("vectors",
                Map.of("size", index.dimensions(), "distance", "Cosine"), "metadata", metadata), stage, true, false);
        if (!result.isBoolean() || !result.booleanValue()) throw uncertain();
    }

    /** 不存在返回false；已存在但模型、修订、维度、距离或命名向量不符时明确拒绝。 */
    public boolean inspect(Index index, TaskDeadline deadline) {
        return inspectWithin(index, stage(index, deadline));
    }

    /** 单批最多16条，全部校验后才发送；wait=true不等于跨MySQL/Qdrant事务。 */
    public void upsert(Index index, Scope scope, List<Point> points, TaskDeadline deadline) {
        TaskDeadline stage = stage(index, deadline);
        if (scope == null || points == null || points.isEmpty() || points.size() > 16) throw invalid("向量批次须为1—16条");
        var ids = new HashSet<Long>();
        var body = new ArrayList<Map<String, Object>>();
        for (Point point : points) {
            if (point == null || point.chunkId() <= 0 || !ids.add(point.chunkId())
                    || point.contentSha256() == null || !point.contentSha256().matches("[0-9a-f]{64}"))
                throw invalid("向量片段编号或摘要不合法，禁止重复片段");
            List<Double> normalized = vector(index, point.vector());
            var payload = new java.util.HashMap<String, Object>(payload(index, scope));
            payload.put("chunk_id", Long.toString(point.chunkId()));
            payload.put("content_sha256", point.contentSha256());
            payload.put("vector_sha256", vectorSha256(point.vector()));
            body.add(Map.of("id", pointId(index, scope, point.chunkId()), "vector", normalized, "payload", payload));
        }
        requireCollection(index, stage);
        JsonNode result = call(HttpMethod.PUT, path(index) + "/points?wait=true&ordering=strong",
                Map.of("points", body), stage, true, false);
        if (!result.isObject() || !"completed".equals(result.path("status").asText())) throw uncertain();
    }

    /** 核对确定性编号和摘要，供上层处理超时后的未知写结果；缺项不伪造为已写成功。 */
    public List<Stored> read(Index index, Scope scope, List<Long> chunks, TaskDeadline deadline) {
        TaskDeadline stage = stage(index, deadline);
        if (scope == null || chunks == null || chunks.isEmpty() || chunks.size() > 16) throw invalid("核对批次须为1—16条");
        List<Long> input = new ArrayList<>(chunks);
        var expected = new HashSet<Long>();
        for (Long id : input) if (id == null || id <= 0 || !expected.add(id)) throw invalid("核对编号须为不重复的正整数");
        requireCollection(index, stage);
        JsonNode result = call(HttpMethod.POST, path(index) + "/points", Map.of("ids",
                input.stream().map(id -> pointId(index, scope, id)).toList(), "with_payload", true, "with_vector", false), stage, false, false);
        if (!result.isArray() || result.size() > input.size()) throw malformed();
        var records = new ArrayList<Stored>();
        var seen = new HashSet<Long>();
        for (JsonNode row : result) {
            Stored record = stored(index, scope, row);
            if (!expected.contains(record.chunkId()) || !seen.add(record.chunkId())) throw malformed();
            records.add(record);
        }
        records.sort(Comparator.comparingLong(Stored::chunkId));
        return List.copyOf(records);
    }

    /** 查询先在Qdrant中限定范围；返回的仍是候选编号，引用前必须回MySQL复核授权与当前版本。 */
    public List<Hit> query(Index index, Scope scope, Vector query, double threshold, int limit, TaskDeadline deadline) {
        TaskDeadline stage = stage(index, deadline);
        if (scope == null || !Double.isFinite(threshold) || threshold < -1 || threshold > 1 || limit < 1 || limit > 24)
            throw invalid("查询范围、阈值或候选数量不合法");
        List<Double> normalized = vector(index, query);
        var must = payload(index, scope).entrySet().stream()
                .map(entry -> Map.of("key", entry.getKey(), "match", Map.of("value", entry.getValue()))).toList();
        requireCollection(index, stage);
        JsonNode result = call(HttpMethod.POST, path(index) + "/points/query", Map.of("query", normalized,
                "filter", Map.of("must", must), "score_threshold", threshold, "limit", limit,
                "with_payload", true, "with_vector", false), stage, false, false);
        JsonNode rows = result.path("points");
        if (!rows.isArray() || rows.size() > limit) throw malformed();
        var seen = new HashSet<Long>();
        var hits = new ArrayList<Hit>();
        for (JsonNode row : rows) {
            Stored record = stored(index, scope, row);
            JsonNode rawScore = row.path("score");
            double score = rawScore.doubleValue();
            // Qdrant使用float32；仅容忍余弦边界的微小舍入，不把分数解释为正确概率。
            if (!seen.add(record.chunkId()) || !rawScore.isNumber() || !Double.isFinite(score)
                    || score < -1.000001 || score > 1.000001 || score + 0.000001 < threshold) throw malformed();
            hits.add(new Hit(record.chunkId(), record.contentSha256(), Math.max(-1, Math.min(1, score))));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingLong(Hit::chunkId));
        return List.copyOf(hits);
    }

    /** 只删除调用方明确指定且元数据完全匹配的ko_集合；业务清理仍须由上层授权和代次状态控制。 */
    public void deleteCollection(Index index, TaskDeadline deadline) {
        TaskDeadline stage = stage(index, deadline);
        requireCollection(index, stage);
        JsonNode result = call(HttpMethod.DELETE, path(index), null, stage, true, false);
        if (!result.isBoolean() || !result.booleanValue()) throw uncertain();
    }

    /** 原始双精度向量摘要，与Qdrant的Cosine归一化后向量不同，不用于证明语义正确。 */
    public static String vectorSha256(Vector vector) {
        HybridRetrievalRanking.cosine(vector, vector);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Double value : vector.values()) digest.update(ByteBuffer.allocate(8).putDouble(value).array());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前JDK不支持SHA-256"); }
    }

    private boolean inspectWithin(Index index, TaskDeadline stage) {
        JsonNode result = call(HttpMethod.GET, path(index), null, stage, false, true);
        if (result == null) return false;
        JsonNode config = result.path("config"), vectors = config.path("params").path("vectors");
        JsonNode size = vectors.path("size"), metadata = config.path("metadata");
        if (!size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() != index.dimensions()
                || !"Cosine".equals(vectors.path("distance").asText())
                || !SCHEMA.equals(metadata.path("schema").asText()) || !index.model().equals(metadata.path("model").asText())
                || !index.revision().equals(metadata.path("revision").asText()))
            throw new BusinessException(ResultCode.CONFLICT, "向量集合与模型修订、维度或距离不一致，禁止接管或覆盖");
        return true;
    }

    private void requireCollection(Index index, TaskDeadline stage) {
        if (!inspectWithin(index, stage)) throw new BusinessException(ResultCode.NOT_FOUND, "向量集合不存在，请先完成索引构建");
    }

    private JsonNode call(HttpMethod method, String path, Object body, TaskDeadline stage, boolean write, boolean missingAllowed) {
        stage.timeoutFor(CALL_LIMIT);
        try {
            var request = http.method(method).uri(path).accept(MediaType.APPLICATION_JSON)
                    .httpRequest(raw -> BoundedJdkClientHttpRequestFactory.useRemainingBudget(raw, stage::remaining));
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            JsonNode result = request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404 && missingAllowed) return null;
                if (status == 401 || status == 403) throw new BusinessException(ResultCode.SERVICE_BUSY, "向量服务权限配置不可用，已停止调用且不会自动重试");
                if (status == 409) throw new BusinessException(ResultCode.CONFLICT, "向量记录发生冲突，请先核对已有索引");
                if (status != 200) throw write ? uncertain() : unavailable();
                MediaType type = response.getHeaders().getContentType();
                if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) throw write ? uncertain() : malformed();
                JsonNode root;
                try { root = reader.readValue(response.getBody()); }
                catch (Exception ex) { throw write ? uncertain() : malformed(); }
                if (root == null || !root.isObject() || !"ok".equals(root.path("status").asText()) || !root.hasNonNull("result"))
                    throw write ? uncertain() : malformed();
                return root.get("result");
            });
            if (write && stage.remaining().isZero()) throw uncertain();
            stage.timeoutFor(CALL_LIMIT);
            return result;
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw write ? uncertain() : unavailable(); }
    }

    private TaskDeadline stage(Index index, TaskDeadline deadline) {
        if (!enabled) throw new BusinessException(ResultCode.SERVICE_BUSY, "Qdrant向量服务尚未启用");
        if (index == null || deadline == null) throw invalid("缺少向量集合或任务截止时间");
        return deadline.limitedTo(CALL_LIMIT);
    }

    private static Map<String, String> payload(Index index, Scope scope) {
        return Map.of("enterprise_id", Long.toString(scope.enterpriseId()), "space_id", Long.toString(scope.spaceId()),
                "document_id", Long.toString(scope.documentId()), "version_id", Long.toString(scope.versionId()),
                "generation", scope.generation(), "model", index.model(), "model_revision", index.revision());
    }

    private static Stored stored(Index index, Scope scope, JsonNode row) {
        if (!row.isObject()) throw malformed();
        JsonNode data = row.path("payload");
        for (var entry : payload(index, scope).entrySet()) {
            if (!data.path(entry.getKey()).isTextual() || !entry.getValue().equals(data.path(entry.getKey()).textValue())) throw malformed();
        }
        String rawId = data.path("chunk_id").textValue();
        long chunkId;
        try {
            chunkId = Long.parseLong(rawId);
            if (chunkId <= 0 || !Long.toString(chunkId).equals(rawId)) throw malformed();
        } catch (NumberFormatException ex) { throw malformed(); }
        if (!pointId(index, scope, chunkId).equals(row.path("id").textValue())) throw malformed();
        String contentHash = data.path("content_sha256").textValue(), vectorHash = data.path("vector_sha256").textValue();
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}") || vectorHash == null || !vectorHash.matches("[0-9a-f]{64}")) throw malformed();
        return new Stored(chunkId, contentHash, vectorHash);
    }

    private static String pointId(Index index, Scope scope, long chunkId) {
        String name = index.collection() + ":" + index.revision() + ":" + scope.enterpriseId() + ":" + scope.spaceId()
                + ":" + scope.documentId() + ":" + scope.versionId() + ":" + scope.generation() + ":" + chunkId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static List<Double> vector(Index index, Vector vector) {
        if (vector == null || !index.model().equals(vector.model()) || vector.values().size() != index.dimensions())
            throw invalid("向量模型或维度与集合不一致");
        HybridRetrievalRanking.cosine(vector, vector);
        // 先缩放再归一化，防止有限double在Qdrant的float32中溢出或全部下溢为零。
        double scale = vector.values().stream().mapToDouble(Math::abs).max().orElseThrow();
        double norm = Math.sqrt(vector.values().stream().mapToDouble(value -> value / scale).map(value -> value * value).sum());
        return vector.values().stream().map(value -> (value / scale) / norm).toList();
    }

    private static String path(Index index) { return "/collections/" + index.collection(); }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
    private static BusinessException malformed() { return new BusinessException(ResultCode.SERVICE_BUSY, "向量服务响应格式或定位范围异常，未采用返回结果"); }
    private static BusinessException unavailable() { return new BusinessException(ResultCode.SERVICE_BUSY, "向量服务未完成，已停止等待且不会自动重试"); }
    private static BusinessException uncertain() { return new BusinessException(ResultCode.SERVICE_BUSY, "向量写入结果尚未确认，请先读取核对，禁止盲目重试或发布索引"); }
}
