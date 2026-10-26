package com.ragqa.rag.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.ResultCode;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.config.RagApiProperties;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 非流式 Responses 传输适配器：一次生成最多发送一次，复用调用方的任务预算。
 *
 * <p>只返回已完成的助手正文，不组装 RAG 模板、不执行工具调用、不暴露推理内容。
 * 系统规则必须由服务端提供；输出仍是不可信文本，展示安全与引用校验由上层负责。</p>
 */
@Component
public class ResponsesClient {

    /** 首版资源保护值：合计输入最多 16000 个码点，输出由调用方在 1～4096 token 内选择。 */
    private static final int MAX_INPUT_CODE_POINTS = 16000;
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final RestClient client;
    private final RagApiProperties.Llm settings;
    private final ObjectReader responseReader;

    public ResponsesClient(@Qualifier("llmRestClient") RestClient client,
            RagApiProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.settings = properties.getLlm();
        this.responseReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /**
     * 规则与用户资料分角色发送；输入保持原样，空白片段不在拼接中丢失。
     * 余额耗尽、未完成、拒答及服务故障均抛安全业务异常，不返回部分文本冒充完整答案。
     */
    public String generate(String instructions, String input, int maxOutputTokens, TaskDeadline deadline) {
        Objects.requireNonNull(deadline, "任务时限不能为空");
        checkActive(deadline);
        validateInput(instructions, input, maxOutputTokens);
        validateConfiguration();
        try {
            String text = client.post().uri("/responses").contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(settings.getApiKey()))
                    .httpRequest(request -> BoundedJdkClientHttpRequestFactory
                            .useRemainingBudget(request, deadline::remaining))
                    .body(Map.of("model", settings.getModel(), "input", List.of(
                            Map.of("role", "system", "content", instructions),
                            Map.of("role", "user", "content", input)),
                            "max_output_tokens", maxOutputTokens, "stream", false, "store", false))
                    .exchange((request, response) -> {
                        checkActive(deadline);
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            // 不读取错误正文，也不把可能含密钥的供应商异常加入异常链。
                            throw httpFailure(status).exception();
                        }
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                            throw Failure.FORMAT.exception();
                        }
                        try {
                            return completedText(responseReader.readValue(response.getBody()));
                        } catch (JsonProcessingException exception) {
                            throw Failure.FORMAT.exception();
                        }
                    });
            checkActive(deadline);
            if (text == null) {
                throw Failure.FORMAT.exception();
            }
            return text;
        } catch (ResourceAccessException exception) {
            if (Thread.currentThread().isInterrupted() || causedBy(exception, InterruptedException.class)) {
                Thread.currentThread().interrupt();
                throw Failure.CANCELLED.exception();
            }
            if (causedBy(exception, TimeoutException.class) || causedBy(exception, HttpTimeoutException.class)
                    || causedBy(exception, SocketTimeoutException.class)) {
                throw Failure.TIMEOUT.exception();
            }
            checkActive(deadline);
            throw Failure.UNAVAILABLE.exception();
        } catch (RestClientException | InvalidMediaTypeException exception) {
            throw Failure.FORMAT.exception();
        } catch (IllegalArgumentException exception) {
            // 请求工厂或地址组装失败也只输出安全配置提示，不透传配置或输入。
            throw Failure.CONFIG.exception();
        }
    }

    private static void validateInput(String instructions, String input, int maxOutputTokens) {
        if (instructions == null || input == null || instructions.isBlank() || input.isBlank()
                || instructions.length() > MAX_INPUT_CODE_POINTS * 2 || input.length() > MAX_INPUT_CODE_POINTS * 2
                || instructions.codePointCount(0, instructions.length()) + input.codePointCount(0, input.length())
                        > MAX_INPUT_CODE_POINTS
                || maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw Failure.INPUT.exception();
        }
    }

    private void validateConfiguration() {
        String key = settings.getApiKey();
        String model = settings.getModel();
        String baseUrl = settings.getBaseUrl();
        if (key == null || key.isBlank() || key.chars().anyMatch(value -> value < 33 || value > 126)
                || model == null || model.isBlank() || model.length() > 256
                || model.codePoints().anyMatch(Character::isISOControl)
                || baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 2048) {
            throw Failure.CONFIG.exception();
        }
        try {
            URI uri = URI.create(baseUrl);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path.endsWith("/responses") || path.endsWith("/chat/completions")) {
                throw Failure.CONFIG.exception();
            }
        } catch (IllegalArgumentException exception) {
            throw Failure.CONFIG.exception();
        }
    }

    private static String completedText(JsonNode body) {
        if (body == null || !body.isObject() || !"response".equals(body.path("object").asText())) {
            throw Failure.FORMAT.exception();
        }
        if (body.hasNonNull("error")) {
            throw Failure.UNAVAILABLE.exception();
        }
        switch (body.path("status").asText()) {
            case "incomplete", "in_progress", "queued" -> throw Failure.INCOMPLETE.exception();
            case "failed", "cancelled" -> throw Failure.UNAVAILABLE.exception();
            case "completed" -> { }
            default -> throw Failure.FORMAT.exception();
        }
        if (!body.path("output").isArray()) {
            throw Failure.FORMAT.exception();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : body.path("output")) {
            if (item.isObject() && "reasoning".equals(item.path("type").asText())) {
                continue;
            }
            if (!item.isObject() || !"message".equals(item.path("type").asText())
                    || !"assistant".equals(item.path("role").asText()) || !item.path("content").isArray()) {
                throw Failure.FORMAT.exception();
            }
            if (item.has("status") && !"completed".equals(item.path("status").asText())) {
                throw Failure.INCOMPLETE.exception();
            }
            // 有显式消息阶段时只交付最终答案，不把进度说明拼成答案正文。
            String phase = item.path("phase").asText("");
            if (item.hasNonNull("phase") && !"commentary".equals(phase) && !"final_answer".equals(phase)) {
                throw Failure.FORMAT.exception();
            }
            for (JsonNode content : item.path("content")) {
                if ("refusal".equals(content.path("type").asText())) {
                    throw Failure.REFUSED.exception();
                }
                if (!"output_text".equals(content.path("type").asText()) || !content.path("text").isTextual()) {
                    throw Failure.FORMAT.exception();
                }
                if (!"commentary".equals(phase)) {
                    text.append(content.path("text").textValue());
                }
            }
        }
        if (text.toString().isBlank()) {
            throw Failure.FORMAT.exception();
        }
        return text.toString();
    }

    private static Failure httpFailure(int status) {
        return switch (status) {
            case 400, 404, 405, 422 -> Failure.CONFIG;
            case 401, 403 -> Failure.AUTH;
            case 402 -> Failure.QUOTA;
            case 429 -> Failure.BUSY;
            case 408, 504 -> Failure.TIMEOUT;
            default -> Failure.UNAVAILABLE;
        };
    }

    private static void checkActive(TaskDeadline deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw Failure.CANCELLED.exception();
        }
        if (deadline.remaining().isZero()) {
            throw Failure.TIMEOUT.exception();
        }
    }

    private static boolean causedBy(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private enum Failure {
        INPUT(ResultCode.PARAM_ERROR, "模型输入不能为空，合计最多为 16000 字，输出额度须为 1～4096 token"),
        CONFIG(ResultCode.SYSTEM_ERROR, "模型调用配置异常，请联系管理员"),
        AUTH(ResultCode.SYSTEM_ERROR, "模型服务暂不可用，请稍后重试"),
        QUOTA(ResultCode.SERVICE_BUSY, "模型服务额度暂不可用，请稍后重试"),
        BUSY(ResultCode.SERVICE_BUSY, "模型服务繁忙，请稍后重试"),
        UNAVAILABLE(ResultCode.SERVICE_BUSY, "模型服务暂不可用，请稍后重试"),
        TIMEOUT(ResultCode.SERVICE_BUSY, "模型回答超时，请稍后重试"),
        CANCELLED(ResultCode.BIZ_ERROR, "本次回答已取消"),
        INCOMPLETE(ResultCode.BIZ_ERROR, "模型回答未完整生成，请稍后重试"),
        REFUSED(ResultCode.BIZ_ERROR, "本次问题暂时无法回答，请调整问题内容"),
        FORMAT(ResultCode.BIZ_ERROR, "模型服务响应格式异常，请稍后重试");

        private final ResultCode code;
        private final String message;

        Failure(ResultCode code, String message) {
            this.code = code;
            this.message = message;
        }

        private BusinessException exception() {
            return new BusinessException(code, message);
        }
    }
}
