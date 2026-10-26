package com.ragqa.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 单次联网问答输入；客户端时间仅用于辅助核对，不能代替后端接收时间或充当资料证据。
 * 时区采用设置页约定的标准标识；旧客户端未传时默认北京时间。
 * 回答语言只接受固定标识，不允许客户端控制模型、工具或系统规则。
 */
public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题最多为 500 个 Unicode 字符") String question,
        Instant clientSubmittedAt,
        @Pattern(regexp = "Asia/Shanghai|Asia/Tokyo|Europe/Paris|Europe/London|America/New_York|America/Los_Angeles",
                message = "时区不在支持范围内") String timeZone,
        @Pattern(regexp = "zh-CN|en|fr|ja|de|es", message = "回答语言不在支持范围内") String answerLanguage) {

    /** 兼容只带时间元数据的旧调用方。 */
    public ChatRequest(String question, Instant clientSubmittedAt, String timeZone) {
        this(question, clientSubmittedAt, timeZone, null);
    }

    public ChatRequest {
        timeZone = timeZone == null ? "Asia/Shanghai" : timeZone;
        answerLanguage = answerLanguage == null ? "zh-CN" : answerLanguage;
    }
}
