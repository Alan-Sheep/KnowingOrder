package com.ragqa.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 本地最小闭环的资源上限；可由 rag.chat.* 覆盖，不代表已经过生产压测。 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "rag.chat")
public class WebChatProperties {

    @NotNull
    @DurationMin(millis = 1)
    @DurationMax(seconds = 180)
    private Duration totalTimeout = Duration.ofSeconds(90);

    @Min(1)
    @Max(3)
    private int maxQuestions = 3;

    @Min(1)
    @Max(2)
    private int maxConcurrentRequests = 2;

    /** 含分析、每题最多两次评估和一次生成；失败不额外重发模型请求。 */
    @Min(1)
    @Max(10)
    private int maxModelCalls = 10;

    /** 每题初查和补查各一次；Tavily 自身最多两次传输尝试，另受共享时限限制。 */
    @Min(1)
    @Max(6)
    private int maxSearchCalls = 6;
}
