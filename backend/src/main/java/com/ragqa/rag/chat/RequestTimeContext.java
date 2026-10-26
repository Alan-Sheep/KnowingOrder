package com.ragqa.rag.chat;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.exception.BusinessException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单次请求的不可变时间上下文：复用总预算的接收时间，不在每个处理阶段重新读取时钟。
 * 客户端时间仅保留用于辅助核对，不作为“今天”的依据，也不作为检索事实传给模型。
 * 本类只建立时间基准，不判断网页资料的适用日期，不改变原有超时预算。
 */
public final class RequestTimeContext {

    // 与 ChatRequest 及前端偏好支持范围一致；内部调用也不能绕过时区限制。
    private static final Set<String> SUPPORTED_TIME_ZONES = Set.of("Asia/Shanghai", "Asia/Tokyo",
            "Europe/Paris", "Europe/London", "America/New_York", "America/Los_Angeles");

    private final Instant receivedAt;
    private final Instant clientSubmittedAt;
    private final ZonedDateTime receivedLocalTime;

    private RequestTimeContext(Instant receivedAt, Instant clientSubmittedAt, ZoneId timeZone) {
        this.receivedAt = receivedAt;
        this.clientSubmittedAt = clientSubmittedAt;
        this.receivedLocalTime = receivedAt.atZone(timeZone);
    }

    /** 从现有请求和总预算创建；缺省时区已由请求结构回退为北京时间。 */
    public static RequestTimeContext from(ChatRequest request, TaskDeadline deadline) {
        Objects.requireNonNull(request, "请求不能为空");
        Objects.requireNonNull(deadline, "任务时限不能为空");
        if (!SUPPORTED_TIME_ZONES.contains(request.timeZone())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "时区不在支持范围内");
        }
        return new RequestTimeContext(deadline.receivedAt(), request.clientSubmittedAt(),
                ZoneId.of(request.timeZone()));
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant clientSubmittedAt() {
        return clientSubmittedAt;
    }

    public ZoneId timeZone() {
        return receivedLocalTime.getZone();
    }

    public ZonedDateTime receivedLocalTime() {
        return receivedLocalTime;
    }

    public LocalDate referenceDate() {
        return receivedLocalTime.toLocalDate();
    }

    /** 提供只读的可信时间字段；不是天气证据，不包含未经信任的客户端时间。 */
    public Map<String, String> modelData() {
        return Map.of("receivedAt", receivedAt.toString(), "timeZone", timeZone().getId(),
                "referenceDate", referenceDate().toString(),
                "localDateTime", receivedLocalTime.toOffsetDateTime().toString(),
                "timeBasis", "SERVER_RECEIVED_AT");
    }
}
