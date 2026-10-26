package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.ResultCode;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ollama /api/embed本机协议适配器；默认关闭，不拉取模型、不重试、不向中转服务外发。 */
@Component
public final class LocalEmbeddingClient implements TextEmbeddings {
    private final boolean enabled;
    private final String model;
    private final RestClient http;
    private final ObjectReader reader;

    public LocalEmbeddingClient(ObjectMapper json,
            @Value("${knowingorder.embedding.enabled:false}") boolean enabled,
            @Value("${knowingorder.embedding.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${knowingorder.embedding.model:}") String model) {
        this.enabled = enabled;
        this.model = model;
        URI uri;
        try { uri = URI.create(baseUrl); } catch (RuntimeException ex) { throw new IllegalArgumentException("本机Embedding地址格式不正确"); }
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Embedding只接受本机回环HTTP服务根地址，不接受公网、凭据或路径");
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        http = RestClient.builder().baseUrl(baseUrl).requestFactory(new BoundedJdkClientHttpRequestFactory(
                client, Duration.ofSeconds(5), 2 * 1024 * 1024)).build();
        reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /** 安装清单摘要不是模型名，也不是防篡改证明；后台执行前仍须重新核对。 */
    public record ModelSnapshot(String model, String revision, int dimensions) {
        public ModelSnapshot {
            if (!validModel(model) || revision == null || !revision.matches("[0-9a-f]{64}")
                    || dimensions < 1 || dimensions > 2048) {
                throw new IllegalArgumentException("本机模型快照不符合索引约束");
            }
        }
        @Override public String toString() { return "ModelSnapshot[dimensions=" + dimensions + "]"; }
    }

    /**
     * 只读取得已安装模型的清单摘要及原生维度，不执行推理或拉取模型。
     * 三次元数据请求共享最多5秒；前后摘要相同只证明这两次观察一致，不锁住可变标签。
     */
    public ModelSnapshot snapshot(TaskDeadline deadline) {
        if (!enabled || !validModel(model)) throw unavailable("本机向量服务未启用或模型配置不符合约束");
        if (deadline == null) throw new BusinessException(ResultCode.PARAM_ERROR, "缺少任务截止时间");
        TaskDeadline stage = deadline.limitedTo(Duration.ofSeconds(5));
        try {
            String revision = installedRevision(metadata("/api/tags", null, stage));
            JsonNode info = metadata("/api/show", Map.of("model", model, "verbose", false), stage);
            requireLocal(info);
            // 多子清单的实际执行变体尚未绑定，不能只用父摘要猜测具体模型。
            JsonNode manifests = info.path("manifests");
            if (!manifests.isMissingNode() && (!manifests.isArray() || !manifests.isEmpty())) throw metadataInvalid();
            JsonNode capabilities = info.path("capabilities");
            boolean embedding = false;
            if (!capabilities.isArray() || capabilities.isEmpty() || capabilities.size() > 32) throw metadataInvalid();
            for (JsonNode capability : capabilities) {
                if (!capability.isTextual()) throw metadataInvalid();
                if ("embedding".equals(capability.textValue())) embedding = true;
            }
            if (!embedding) throw unavailable("配置的本机模型不支持向量化，请选择已安装的Embedding模型");
            JsonNode modelInfo = info.path("model_info");
            JsonNode architecture = modelInfo.path("general.architecture");
            if (!modelInfo.isObject() || !architecture.isTextual()
                    || !architecture.textValue().matches("[a-zA-Z0-9_]{1,64}")) throw metadataInvalid();
            JsonNode dimensions = modelInfo.path(architecture.textValue() + ".embedding_length");
            if (!dimensions.isIntegralNumber() || !dimensions.canConvertToInt()
                    || dimensions.intValue() < 1 || dimensions.intValue() > 2048) throw metadataInvalid();
            if (!revision.equals(installedRevision(metadata("/api/tags", null, stage)))) {
                throw new BusinessException(ResultCode.CONFLICT, "模型在核验期间发生变化，未生成索引快照，请核对本机模型");
            }
            checkpoint(stage);
            return new ModelSnapshot(model, revision, dimensions.intValue());
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw unavailable("本机模型核验未完成，已停止等待；不会自动重试或下载模型"); }
    }

    private JsonNode metadata(String path, Map<String, Object> body, TaskDeadline stage) {
        checkpoint(stage);
        var request = body == null ? http.get().uri(path) : http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body);
        return request.httpRequest(raw -> BoundedJdkClientHttpRequestFactory.useRemainingBudget(raw, stage::remaining))
                .exchange((raw, response) -> {
                    if (response.getStatusCode().value() != 200) throw unavailable("本机模型元数据不可用，请核对模型安装和服务权限");
                    MediaType type = response.getHeaders().getContentType();
                    if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) throw metadataInvalid();
                    JsonNode result;
                    try { result = reader.readValue(response.getBody()); } catch (Exception ex) { throw metadataInvalid(); }
                    checkpoint(stage);
                    if (result == null || !result.isObject()) throw metadataInvalid();
                    return result;
                });
    }

    private String installedRevision(JsonNode info) {
        JsonNode models = info.path("models");
        if (!models.isArray() || models.size() > 256) throw metadataInvalid();
        String lastSegment = model.substring(model.lastIndexOf('/') + 1);
        String expected = lastSegment.contains(":") ? model : model + ":latest";
        String revision = null;
        for (JsonNode installed : models) {
            if (!installed.isObject() || !installed.path("name").isTextual()) throw metadataInvalid();
            if (!expected.equals(installed.path("name").textValue())) continue;
            if (revision != null || !expected.equals(installed.path("model").textValue())) throw metadataInvalid();
            requireLocal(installed);
            JsonNode digest = installed.path("digest");
            if (!digest.isTextual() || !digest.textValue().matches("[0-9a-f]{64}")) throw metadataInvalid();
            revision = digest.textValue();
        }
        if (revision == null) throw unavailable("配置的Embedding模型尚未安装；请由管理员准备，本次不会下载模型");
        return revision;
    }

    private static void requireLocal(JsonNode info) {
        for (String key : List.of("remote_host", "remote_model")) {
            JsonNode value = info.path(key);
            if (!value.isMissingNode() && (!value.isTextual() || !value.textValue().isEmpty())) {
                throw unavailable("仅允许本机已安装的Embedding模型，远端模型或来源不明的配置已拒绝");
            }
        }
    }

    private static boolean validModel(String value) {
        return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}");
    }

    private static void checkpoint(TaskDeadline stage) {
        if (Thread.currentThread().isInterrupted()) throw unavailable("本机模型核验已取消，未继续调用服务");
        stage.timeoutFor(Duration.ofSeconds(5));
    }

    private static BusinessException metadataInvalid() { return unavailable("本机模型元数据格式、能力或维度不符合契约"); }

    @Override public List<Vector> embed(List<String> texts, TaskDeadline deadline) {
        if (!enabled || model == null || model.isBlank()) throw unavailable("本机向量服务未启用或模型尚未配置");
        if (texts == null || texts.isEmpty() || texts.size() > 16 || texts.stream().anyMatch(text ->
                text == null || text.isBlank() || text.length() > 1200 || text.codePoints().anyMatch(cp ->
                        Character.getType(cp) == Character.SURROGATE || Character.getType(cp) == Character.FORMAT
                                || (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "单批最多16条、每条最多1200字符的有效文本");
        }
        List<String> input = List.copyOf(texts);
        if (deadline == null) throw new BusinessException(ResultCode.PARAM_ERROR, "缺少任务截止时间");
        TaskDeadline stage = deadline.limitedTo(Duration.ofSeconds(5));
        try {
            return http.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
                    .httpRequest(request -> BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, stage::remaining))
                    .body(Map.of("model", model, "input", input, "truncate", false))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) throw unavailable("本机向量服务调用失败，请核对模型和服务状态");
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) throw invalid();
                        JsonNode output;
                        try { output = reader.readValue(response.getBody()); } catch (Exception ex) { throw invalid(); }
                        stage.timeoutFor(Duration.ofSeconds(5));
                        if (!output.isObject() || !model.equals(output.path("model").asText())
                                || !output.path("embeddings").isArray() || output.path("embeddings").size() != input.size()) throw invalid();
                        List<Vector> result = new ArrayList<>();
                        int dimensions = -1;
                        for (JsonNode row : output.path("embeddings")) {
                            if (!row.isArray() || row.isEmpty() || row.size() > 2048
                                    || (dimensions != -1 && dimensions != row.size())) throw invalid();
                            dimensions = row.size();
                            List<Double> values = new ArrayList<>();
                            double norm = 0;
                            for (JsonNode element : row) {
                                if (!element.isNumber() || !Double.isFinite(element.doubleValue())) throw invalid();
                                double value = element.doubleValue(); norm += value * value; values.add(value);
                            }
                            if (!Double.isFinite(norm) || norm <= 0) throw invalid();
                            result.add(new Vector(model, values));
                        }
                        return List.copyOf(result);
                    });
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw unavailable("本机向量服务未完成，已停止等待；不会自动重试或下载模型"); }
    }
    private static BusinessException invalid() { return unavailable("本机向量响应格式、模型或维度不符合契约"); }
    private static BusinessException unavailable(String message) { return new BusinessException(ResultCode.SERVICE_BUSY, message); }
}
