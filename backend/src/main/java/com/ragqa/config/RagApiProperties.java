package com.ragqa.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 联网检索与模型调用配置。启动时校验普通参数，调用前校验凭据是否齐备。
 *
 * <p>密钥仅由外部环境注入，不生成包含字段值的 toString，且禁止 JSON 序列化密钥。
 * 本类不发送请求；超时值需由后续 HTTP 客户端实际应用。</p>
 */
@Getter
@Component
@Validated
@ConfigurationProperties(prefix = "rag")
public class RagApiProperties {

    @Valid
    private final Search search = new Search();

    @Valid
    private final Llm llm = new Llm();

    /** Tavily 搜索参数。首版固定使用基础搜索，不自动升级搜索深度。 */
    @Getter
    @Setter
    public static class Search {

        @NotBlank
        @URL(protocol = "https", message = "搜索地址必须为有效 HTTPS URL")
        private String endpoint = "https://api.tavily.com/search";

        @JsonIgnore
        private String apiKey = "";

        @Pattern(regexp = "basic", message = "首版仅支持 basic 搜索")
        @NotNull
        private String depth = "basic";

        @Min(1)
        @Max(5)
        private int maxResults = 5;

        @NotNull
        @DurationMin(millis = 1)
        private Duration connectTimeout = Duration.ofSeconds(3);

        @NotNull
        @DurationMin(millis = 1)
        private Duration readTimeout = Duration.ofSeconds(10);

        /** 后续客户端发送请求前调用；报错仅包含变量名，不包含凭据值。 */
        public void requireConfigured() {
            requireValue(apiKey, "TAVILY_API_KEY");
        }
    }

    /** OpenAI 兼容格式的模型配置；服务地址和模型由使用者明确选择。 */
    @Getter
    @Setter
    public static class Llm {

        // 空值允许基础应用启动，真正调用模型前再检查。
        @URL(protocol = "https", message = "模型地址必须为有效 HTTPS URL")
        private String baseUrl = "";

        @JsonIgnore
        private String apiKey = "";

        private String model = "";

        @NotNull
        @DurationMin(millis = 1)
        private Duration connectTimeout = Duration.ofSeconds(3);

        @NotNull
        @DurationMin(millis = 1)
        private Duration readTimeout = Duration.ofSeconds(60);

        /** 后续客户端发送请求前调用；未配置时明确指出缺失项。 */
        public void requireConfigured() {
            requireValue(baseUrl, "DASHSCOPE_BASE_URL");
            requireValue(model, "LLM_MODEL");
            requireValue(apiKey, "DASHSCOPE_API_KEY");
        }
    }

    private static void requireValue(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("外部 API 配置缺失，请设置环境变量 " + variable);
        }
    }
}
