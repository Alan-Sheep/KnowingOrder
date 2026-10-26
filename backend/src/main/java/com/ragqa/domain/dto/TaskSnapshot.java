package com.ragqa.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ragqa.domain.enums.TaskPhase;
import com.ragqa.domain.enums.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 任务查询与进度展示使用的不可变快照，作为 Result 的业务数据。
 *
 * <p>状态和时间来自实际执行记录，不在读取时刷新截止时间或保留期。
 * 本类型仅校验单份快照，不执行任务、不鉴权、不实现版本竞争或定时清理。</p>
 *
 * @param conversationId 会话编号
 * @param messageId 本任务对应的原始消息编号
 * @param taskId 本任务编号
 * @param version 状态快照版本，从 1 开始，供前端忽略旧状态
 * @param executionRevision 问题修订版本，从 1 开始，供执行端校验结果归属
 * @param status 任务状态，终态不一定表示已有答案
 * @param phase 正在执行或最后执行的阶段；排队时为空，运行时必填
 * @param receivedAt 原请求接收时间，不随刷新、补充或重连改变
 * @param deadlineAt 原任务截止时间，不在快照构造时推算
 * @param finishedAt 结束时间，所有终态必填，活动任务为空
 * @param expiresAt 查询保留期的到期时间，所有终态必填，活动任务为空
 * @param answer 已有答案或已完成部分；无答案时为空，不能仅凭此字段判断整体完成
 * @param sources 已检索到的来源，不保证足以回答；缺省转为空列表并作防御性复制
 * @param mergeTarget 合并目标，仅 MERGED 必填，不能指向本任务
 * @param errorCode 安全错误分类，仅 FAILED、TIMED_OUT 必填，PARTIAL 可选
 * @param message 服务端控制的非空白用户提示，不得使用原始第三方响应或异常文本
 */
public record TaskSnapshot(Long conversationId, Long messageId, Long taskId,
                           long version, long executionRevision, TaskStatus status, TaskPhase phase,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") Instant receivedAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") Instant deadlineAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") Instant finishedAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") Instant expiresAt,
                           String answer, List<SearchResult> sources, MergeTarget mergeTarget,
                           String errorCode, String message) {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** 只校验字段与状态的一致性；错误信息不回显输入内容。 */
    public TaskSnapshot {
        requirePositive(conversationId, "conversationId");
        requirePositive(messageId, "messageId");
        requirePositive(taskId, "taskId");
        requirePositive(version, "version");
        requirePositive(executionRevision, "executionRevision");
        require(status != null, "status 不能为空");
        require(message != null && !message.isBlank(), "message 不能为空白");
        require(receivedAt != null, "receivedAt 不能为空");
        require(deadlineAt != null && deadlineAt.isAfter(receivedAt), "deadlineAt 必须晚于 receivedAt");

        if (status.isTerminal()) {
            require(finishedAt != null && !finishedAt.isBefore(receivedAt),
                    "finishedAt 必须存在且不早于 receivedAt");
            require(expiresAt != null && expiresAt.isAfter(finishedAt),
                    "expiresAt 必须存在且晚于 finishedAt");
        } else {
            require(finishedAt == null && expiresAt == null, "活动任务不能设置 finishedAt 或 expiresAt");
        }
        require(status != TaskStatus.QUEUED || phase == null, "排队任务不能设置 phase");
        require(status != TaskStatus.RUNNING || phase != null, "运行任务必须设置 phase");

        if (sources == null) {
            sources = List.of();
        } else {
            require(sources.stream().noneMatch(value -> value == null), "sources 不能包含 null");
            sources = List.copyOf(sources);
        }
        require(answer == null || !answer.isBlank(), "answer 不能仅包含空白");
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.PARTIAL) {
            require(answer != null, "成功或部分完成任务必须提供 answer");
        }
        if (status == TaskStatus.QUEUED || status == TaskStatus.NO_SOURCES
                || status == TaskStatus.MERGED || status == TaskStatus.NEEDS_CLARIFICATION) {
            require(answer == null, "当前状态不能提供 answer");
        }
        require(status != TaskStatus.QUEUED || sources.isEmpty(), "排队任务不能提供 sources");

        if (status == TaskStatus.MERGED) {
            require(mergeTarget != null, "合并任务必须提供 mergeTarget");
            require(!taskId.equals(mergeTarget.taskId()), "合并目标不能指向本任务");
        } else {
            require(mergeTarget == null, "非合并任务不能设置 mergeTarget");
        }
        if (status == TaskStatus.FAILED || status == TaskStatus.TIMED_OUT) {
            require(errorCode != null, "失败或超时任务必须提供 errorCode");
        } else if (status != TaskStatus.PARTIAL) {
            require(errorCode == null, "当前状态不能设置 errorCode");
        }
        require(errorCode == null || ERROR_CODE_PATTERN.matcher(errorCode).matches(),
                "errorCode 必须为不超过 64 位的大写字母数字下划线分类");
    }

    /** 合并目标版本属于目标任务，不能拿本快照的 version 代替。 */
    public record MergeTarget(Long taskId, long version) {

        public MergeTarget {
            requirePositive(taskId, "mergeTarget.taskId");
            requirePositive(version, "mergeTarget.version");
        }
    }

    private static void requirePositive(Long value, String field) {
        require(value != null && value > 0, field + " 必须为正整数");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
