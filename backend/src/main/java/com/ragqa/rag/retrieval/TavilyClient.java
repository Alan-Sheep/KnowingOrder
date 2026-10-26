package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.ResultCode;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.config.RagApiProperties;
import com.ragqa.domain.dto.SearchResult;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Tavily 搜索适配：有限重试、安全提示与共享时限；不创建整次问答任务或调度并发。 */
@Component
public class TavilyClient {

    private static final int MAX_QUERY_CODE_POINTS = 500;
    private static final int MAX_ATTEMPTS = 2;
    private static final long MAX_RETRY_AFTER_MILLIS = 2000;

    private final RestClient client;
    private final RagApiProperties.Search settings;
    private final TavilyResultMapper resultMapper;
    private final ObjectReader responseReader;

    public TavilyClient(@Qualifier("searchRestClient") RestClient client, RagApiProperties properties,
            TavilyResultMapper resultMapper, ObjectMapper objectMapper) {
        this.client = client;
        this.settings = properties.getSearch();
        this.resultMapper = resultMapper;
        this.responseReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 搜索一个查询。返回空列表仅表示上游明确没有结果；其他失败抛出业务异常。
     * 缺少配置不影响基础应用启动，但不得发送实际请求。
     * 独立调用以搜索读取上限作为整轮预算，重试与退避不能续期。
     */
    public List<SearchResult> search(String query) {
        return search(query, TaskDeadline.start(settings.getReadTimeout()));
    }

    /** 问答编排传入入口创建的同一预算；检索不得重新创建或延长它。 */
    public List<SearchResult> search(String query, TaskDeadline deadline) {
        Objects.requireNonNull(deadline, "任务时限不能为空");
        checkActive(deadline);
        if (query == null || query.length() > MAX_QUERY_CODE_POINTS * 2 || query.isBlank()
                || query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
            throw Failure.INPUT.exception();
        }
        String apiKey = settings.getApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(value -> value < 33 || value > 126)) {
            throw Failure.CONFIG.exception();
        }
        Map<String, Object> payload = Map.of("query", query.strip(), "search_depth", settings.getDepth(),
                "max_results", settings.getMaxResults(), "include_answer", false,
                "include_raw_content", false, "include_images", false, "auto_parameters", false);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            checkActive(deadline);
            Failure failure;
            long retryDelay = -1;
            try {
                Attempt response = client.post().contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(apiKey)).body(payload)
                        .httpRequest(request -> BoundedJdkClientHttpRequestFactory
                                .useRemainingBudget(request, deadline::remaining))
                        .exchange((request, upstream) -> {
                            int status = upstream.getStatusCode().value();
                            if (status != 200) {
                                // 错误正文可能回显凭据，既不解析也不放进异常链或日志。
                                return new Attempt(status, upstream.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), null);
                            }
                            MediaType contentType = upstream.getHeaders().getContentType();
                            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                                throw Failure.FORMAT.exception();
                            }
                            try {
                                return new Attempt(status, null, responseReader.readValue(upstream.getBody()));
                            } catch (JsonProcessingException exception) {
                                // 在传输层包装 IOException 之前分类，保留格式错误的准确语义。
                                throw Failure.FORMAT.exception();
                            }
                        });
                checkActive(deadline);
                if (response == null) {
                    throw Failure.FORMAT.exception();
                }
                if (response.status() == 200) {
                    List<SearchResult> results = resultMapper.map(response.body());
                    checkActive(deadline);
                    return results;
                }
                failure = switch (response.status()) {
                    case 401, 403 -> Failure.AUTH;
                    case 402, 432, 433 -> Failure.QUOTA;
                    case 429 -> Failure.BUSY;
                    default -> Failure.UNAVAILABLE;
                };
                if (response.status() == 429) {
                    retryDelay = retryAfterMillis(response.retryAfter());
                } else if (response.status() == 502 || response.status() == 503 || response.status() == 504) {
                    retryDelay = jitterMillis();
                }
            } catch (ResourceAccessException exception) {
                if (Thread.currentThread().isInterrupted() || causedBy(exception, InterruptedException.class)) {
                    Thread.currentThread().interrupt();
                    throw Failure.CANCELLED.exception();
                }
                if (causedBy(exception, TimeoutException.class) || causedBy(exception, HttpTimeoutException.class)
                        || causedBy(exception, java.net.SocketTimeoutException.class)) {
                    throw Failure.TIMEOUT.exception();
                }
                failure = Failure.UNAVAILABLE;
                if (!causedBy(exception, SSLException.class)
                        && (causedBy(exception, SocketException.class) || causedBy(exception, UnknownHostException.class))) {
                    retryDelay = jitterMillis();
                }
            } catch (RestClientException | org.springframework.http.InvalidMediaTypeException exception) {
                // 丢弃上游解析异常链，避免 Jackson 片段或第三方响应进入全局异常日志。
                throw Failure.FORMAT.exception();
            }
            checkActive(deadline);
            if (attempt + 1 == MAX_ATTEMPTS || retryDelay < 0) {
                throw failure.exception();
            }
            pause(retryDelay, deadline);
        }
        throw Failure.UNAVAILABLE.exception();
    }

    private static long retryAfterMillis(String value) {
        if (value == null || value.length() > 128) {
            return -1;
        }
        String trimmed = value.strip();
        long millis;
        try {
            if (trimmed.matches("[0-9]+")) {
                long seconds = Long.parseLong(trimmed);
                if (seconds > MAX_RETRY_AFTER_MILLIS / 1000) {
                    return -1;
                }
                millis = seconds * 1000;
            } else {
                Instant retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                millis = Math.max(0, Duration.between(Instant.now(), retryAt).toMillis());
            }
        } catch (NumberFormatException | DateTimeParseException | ArithmeticException exception) {
            return -1;
        }
        return millis > MAX_RETRY_AFTER_MILLIS ? -1 : Math.max(jitterMillis(), millis);
    }

    private static long jitterMillis() {
        // 首版只重试一次，使用短随机退避，避免并发请求同时立刻重发。
        return ThreadLocalRandom.current().nextLong(200, 401);
    }

    private static void pause(long millis, TaskDeadline deadline) {
        checkActive(deadline);
        // 不缩短供应商要求的等待，也不在等待结束后重新分配整份预算。
        if (deadline.remaining().compareTo(Duration.ofMillis(millis)) <= 0) {
            throw Failure.TIMEOUT.exception();
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw Failure.CANCELLED.exception();
        }
        checkActive(deadline);
    }

    private static void checkActive(TaskDeadline deadline) {
        checkCancelled();
        if (deadline.remaining().compareTo(Duration.ZERO) <= 0) {
            throw Failure.TIMEOUT.exception();
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw Failure.CANCELLED.exception();
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

    private record Attempt(int status, String retryAfter, JsonNode body) {
    }

    private enum Failure {
        INPUT(ResultCode.PARAM_ERROR, "搜索问题不能为空，且最多为 500 字"),
        CONFIG(ResultCode.SYSTEM_ERROR, "联网搜索尚未配置，请联系管理员"),
        AUTH(ResultCode.SYSTEM_ERROR, "联网搜索服务暂不可用，请稍后重试"),
        QUOTA(ResultCode.SERVICE_BUSY, "联网搜索服务额度暂不可用，请稍后重试"),
        BUSY(ResultCode.SERVICE_BUSY, "联网搜索繁忙，请稍后重试"),
        UNAVAILABLE(ResultCode.SERVICE_BUSY, "联网搜索暂不可用，请稍后重试"),
        TIMEOUT(ResultCode.SERVICE_BUSY, "联网搜索超时，请稍后重试"),
        CANCELLED(ResultCode.BIZ_ERROR, "本次搜索已取消"),
        FORMAT(ResultCode.BIZ_ERROR, "搜索服务响应格式异常，请稍后重试");

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
