package com.ragqa.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.common.Result;
import com.ragqa.config.JacksonConfig;
import com.ragqa.domain.enums.TaskPhase;
import com.ragqa.domain.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证任务快照的数据一致性、边界与 JSON 契约，不代表调度和鉴权已实现。 */
class TaskSnapshotTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-16T01:00:00Z");
    private static final SearchResult SOURCE = new SearchResult("测试资料", "https://example.com/article",
            "人工构造的测试正文", null);

    private final ObjectMapper mapper = configuredMapper();

    @Test
    void queuedSnapshotHasNoInventedProgressOrCompletionTime() throws Exception {
        JsonNode root = mapper.readTree(mapper.writeValueAsString(Result.ok(new Input().build())));
        JsonNode data = root.get("data");

        assertThat(root.get("code").intValue()).isZero();
        assertThat(data.size()).isEqualTo(16);
        assertThat(data.get("status").textValue()).isEqualTo("QUEUED");
        assertThat(data.get("phase").isNull()).isTrue();
        assertThat(data.get("finishedAt").isNull()).isTrue();
        assertThat(data.get("expiresAt").isNull()).isTrue();
        assertThat(data.get("answer").isNull()).isTrue();
        assertThat(data.get("sources").isArray()).isTrue();
        assertThat(data.get("sources").size()).isZero();
    }

    @Test
    void timestampsStayUtcAndIdentifiersRemainExactWithExistingJacksonConfig() throws Exception {
        Input input = ended(TaskStatus.MERGED);
        input.conversationId = 9_007_199_254_740_993L;
        input.messageId = Long.MAX_VALUE - 1;
        input.taskId = Long.MAX_VALUE;
        input.version = 8;
        input.executionRevision = 2;
        input.mergeTarget = new TaskSnapshot.MergeTarget(Long.MAX_VALUE - 2, 7);
        TaskSnapshot snapshot = input.build();

        String json = mapper.writeValueAsString(snapshot);
        JsonNode data = mapper.readTree(json);

        assertThat(data.get("conversationId").textValue()).isEqualTo("9007199254740993");
        assertThat(data.get("messageId").textValue()).isEqualTo("9223372036854775806");
        assertThat(data.get("taskId").textValue()).isEqualTo("9223372036854775807");
        assertThat(data.get("version").isIntegralNumber()).isTrue();
        assertThat(data.get("version").longValue()).isEqualTo(8);
        assertThat(data.get("executionRevision").longValue()).isEqualTo(2);
        assertThat(data.get("receivedAt").textValue()).isEqualTo("2026-10-16T01:00:00Z");
        assertThat(data.get("deadlineAt").textValue()).isEqualTo("2026-10-16T01:01:30Z");
        assertThat(data.get("finishedAt").textValue()).isEqualTo("2026-10-16T01:00:05Z");
        assertThat(data.get("expiresAt").textValue()).isEqualTo("2026-10-17T01:00:05Z");
        assertThat(data.path("mergeTarget").get("taskId").textValue()).isEqualTo("9223372036854775805");
        assertThat(data.path("mergeTarget").get("version").longValue()).isEqualTo(7);
        assertThat(mapper.readValue(json, TaskSnapshot.class)).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @EnumSource(TaskPhase.class)
    void runningSnapshotReportsActualPhaseSeparatelyFromStatus(TaskPhase phase) throws Exception {
        Input input = new Input();
        input.status = TaskStatus.RUNNING;
        input.phase = phase;

        JsonNode data = mapper.readTree(mapper.writeValueAsString(input.build()));

        assertThat(data.get("status").textValue()).isEqualTo("RUNNING");
        assertThat(data.get("phase").textValue()).isEqualTo(phase.name());
        assertThat(data.get("finishedAt").isNull()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"SUCCEEDED", "PARTIAL", "NO_SOURCES", "FAILED",
            "TIMED_OUT", "CANCELLED", "MERGED", "NEEDS_CLARIFICATION"})
    void endedTaskRoundTripKeepsOriginalDeadlineAndRetention(TaskStatus status) throws Exception {
        TaskSnapshot original = ended(status).build();

        TaskSnapshot restored = mapper.readValue(mapper.writeValueAsString(original), TaskSnapshot.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.deadlineAt()).isEqualTo(RECEIVED_AT.plusSeconds(90));
        assertThat(restored.expiresAt()).isEqualTo(original.finishedAt().plusSeconds(86_400));
    }

    @Test
    void timeoutKeepsCompletedPartAndSourcesWithoutPretendingWholeTaskSucceeded() throws Exception {
        Input input = ended(TaskStatus.TIMED_OUT);
        input.finishedAt = RECEIVED_AT.plusSeconds(91);
        input.expiresAt = input.finishedAt.plusSeconds(86_400);
        input.answer = "已完成第一个问题，其余部分超时";
        input.sources = List.of(SOURCE);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(Result.ok(input.build())));

        assertThat(root.get("code").intValue()).isZero();
        assertThat(root.path("data").get("status").textValue()).isEqualTo("TIMED_OUT");
        assertThat(root.path("data").get("errorCode").textValue()).isEqualTo("TASK_TIMEOUT");
        assertThat(root.path("data").get("answer").textValue()).isEqualTo(input.answer);
        assertThat(root.path("data").get("sources").size()).isEqualTo(1);
    }

    @Test
    void sourcesAreDefensivelyCopiedAndCannotBeModifiedThroughSnapshot() {
        Input input = ended(TaskStatus.SUCCEEDED);
        List<SearchResult> original = new ArrayList<>(List.of(SOURCE));
        input.sources = original;
        TaskSnapshot snapshot = input.build();

        original.clear();

        assertThat(snapshot.sources()).containsExactly(SOURCE);
        assertThatThrownBy(() -> snapshot.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void absentSourcesUseEmptyListInsteadOfNull() {
        Input input = new Input();
        input.sources = null;
        assertThat(input.build().sources()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    void invalidSnapshotCannotBePublishedAndExceptionDoesNotEchoContent(Consumer<Input> mutation, String error) {
        Input input = new Input();
        input.message = "不应出现在错误中的敏感标记";
        mutation.accept(input);

        assertThatThrownBy(input::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(error).hasMessageNotContaining("敏感标记");
    }

    static Stream<Arguments> invalidSnapshots() {
        return Stream.of(
                invalid(i -> i.conversationId = null, "conversationId 必须为正整数"),
                invalid(i -> i.conversationId = 0L, "conversationId 必须为正整数"),
                invalid(i -> i.messageId = null, "messageId 必须为正整数"),
                invalid(i -> i.messageId = -1L, "messageId 必须为正整数"),
                invalid(i -> i.taskId = null, "taskId 必须为正整数"),
                invalid(i -> i.taskId = 0L, "taskId 必须为正整数"),
                invalid(i -> i.version = 0, "version 必须为正整数"),
                invalid(i -> i.executionRevision = -1, "executionRevision 必须为正整数"),
                invalid(i -> i.status = null, "status 不能为空"),
                invalid(i -> i.message = null, "message 不能为空白"),
                invalid(i -> i.message = "\t\n", "message 不能为空白"),
                invalid(i -> i.receivedAt = null, "receivedAt 不能为空"),
                invalid(i -> i.deadlineAt = null, "deadlineAt 必须晚于 receivedAt"),
                invalid(i -> i.deadlineAt = RECEIVED_AT, "deadlineAt 必须晚于 receivedAt"),
                invalid(i -> i.deadlineAt = RECEIVED_AT.minusSeconds(1), "deadlineAt 必须晚于 receivedAt"),
                invalid(i -> i.finishedAt = RECEIVED_AT, "活动任务不能设置 finishedAt 或 expiresAt"),
                invalid(i -> i.expiresAt = RECEIVED_AT, "活动任务不能设置 finishedAt 或 expiresAt"),
                invalid(i -> i.status = TaskStatus.RUNNING, "运行任务必须设置 phase"),
                invalid(i -> i.phase = TaskPhase.SEARCHING, "排队任务不能设置 phase"),
                invalid(i -> i.answer = "已有答案", "当前状态不能提供 answer"),
                invalid(i -> i.answer = " ", "answer 不能仅包含空白"),
                invalid(i -> i.sources = List.of(SOURCE), "排队任务不能提供 sources"),
                invalid(i -> i.sources = Arrays.asList(SOURCE, null), "sources 不能包含 null"),
                invalid(i -> i.errorCode = "TASK_FAILED", "当前状态不能设置 errorCode"),
                invalid(i -> i.mergeTarget = new TaskSnapshot.MergeTarget(4L, 1),
                        "非合并任务不能设置 mergeTarget"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).finishedAt = null,
                        "finishedAt 必须存在且不早于 receivedAt"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).finishedAt = RECEIVED_AT.minusSeconds(1),
                        "finishedAt 必须存在且不早于 receivedAt"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).expiresAt = null,
                        "expiresAt 必须存在且晚于 finishedAt"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).expiresAt = i.finishedAt,
                        "expiresAt 必须存在且晚于 finishedAt"),
                invalid(i -> i.useEnded(TaskStatus.SUCCEEDED).answer = null,
                        "成功或部分完成任务必须提供 answer"),
                invalid(i -> i.useEnded(TaskStatus.PARTIAL).answer = null,
                        "成功或部分完成任务必须提供 answer"),
                invalid(i -> i.useEnded(TaskStatus.MERGED).mergeTarget = null,
                        "合并任务必须提供 mergeTarget"),
                invalid(i -> i.useEnded(TaskStatus.MERGED).mergeTarget = new TaskSnapshot.MergeTarget(3L, 1),
                        "合并目标不能指向本任务"),
                invalid(i -> i.useEnded(TaskStatus.MERGED).answer = "旧答案", "当前状态不能提供 answer"),
                invalid(i -> i.useEnded(TaskStatus.NO_SOURCES).answer = "无依据的答案", "当前状态不能提供 answer"),
                invalid(i -> i.useEnded(TaskStatus.NEEDS_CLARIFICATION).answer = "答案", "当前状态不能提供 answer"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).errorCode = null,
                        "失败或超时任务必须提供 errorCode"),
                invalid(i -> i.useEnded(TaskStatus.TIMED_OUT).errorCode = null,
                        "失败或超时任务必须提供 errorCode"),
                invalid(i -> i.useEnded(TaskStatus.SUCCEEDED).errorCode = "TASK_FAILED", "当前状态不能设置 errorCode"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).errorCode = "敏感标记",
                        "errorCode 必须为不超过 64 位的大写字母数字下划线分类"),
                invalid(i -> i.useEnded(TaskStatus.FAILED).errorCode = "A".repeat(65),
                        "errorCode 必须为不超过 64 位的大写字母数字下划线分类"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"receivedAt", "deadlineAt", "taskId", "status", "version", "executionRevision", "message"})
    void jsonDeserializationCannotBypassRequiredFields(String field) throws Exception {
        ObjectNode data = mapper.valueToTree(new Input().build());
        data.remove(field);

        assertThatThrownBy(() -> mapper.treeToValue(data, TaskSnapshot.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "phase"})
    void unknownStatusOrPhaseDoesNotBecomeFalseProgress(String field) throws Exception {
        Input input = new Input();
        input.status = TaskStatus.RUNNING;
        input.phase = TaskPhase.ANALYZING;
        ObjectNode data = mapper.valueToTree(input.build());
        data.put(field, "UNKNOWN");

        assertThatThrownBy(() -> mapper.treeToValue(data, TaskSnapshot.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidMergeTargets")
    void mergeTargetRequiresItsOwnValidIdentifierAndVersion(Long taskId, long version, String error) {
        assertThatThrownBy(() -> new TaskSnapshot.MergeTarget(taskId, version))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(error);
    }

    static Stream<Arguments> invalidMergeTargets() {
        return Stream.of(Arguments.of(null, 1L, "mergeTarget.taskId 必须为正整数"),
                Arguments.of(0L, 1L, "mergeTarget.taskId 必须为正整数"),
                Arguments.of(4L, 0L, "mergeTarget.version 必须为正整数"));
    }

    private static Arguments invalid(Consumer<Input> mutation, String error) {
        return Arguments.of(mutation, error);
    }

    private static Input ended(TaskStatus status) {
        return new Input().useEnded(status);
    }

    private static ObjectMapper configuredMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        return builder.build();
    }

    /** 仅供用例改变一个边界，避免大构造参数掩盖被测条件。 */
    private static class Input {
        private Long conversationId = 1L;
        private Long messageId = 2L;
        private Long taskId = 3L;
        private long version = 1;
        private long executionRevision = 1;
        private TaskStatus status = TaskStatus.QUEUED;
        private TaskPhase phase;
        private Instant receivedAt = RECEIVED_AT;
        private Instant deadlineAt = RECEIVED_AT.plusSeconds(90);
        private Instant finishedAt;
        private Instant expiresAt;
        private String answer;
        private List<SearchResult> sources = List.of();
        private TaskSnapshot.MergeTarget mergeTarget;
        private String errorCode;
        private String message = "消息已接收，等待处理";

        private Input useEnded(TaskStatus terminalStatus) {
            status = terminalStatus;
            finishedAt = RECEIVED_AT.plusSeconds(5);
            expiresAt = finishedAt.plusSeconds(86_400);
            if (status == TaskStatus.SUCCEEDED || status == TaskStatus.PARTIAL) {
                answer = "已完成的测试答案";
            }
            if (status == TaskStatus.FAILED || status == TaskStatus.TIMED_OUT) {
                errorCode = status == TaskStatus.FAILED ? "TASK_FAILED" : "TASK_TIMEOUT";
            }
            if (status == TaskStatus.MERGED) {
                mergeTarget = new TaskSnapshot.MergeTarget(4L, 1);
            }
            return this;
        }

        private TaskSnapshot build() {
            return new TaskSnapshot(conversationId, messageId, taskId, version, executionRevision, status, phase,
                    receivedAt, deadlineAt, finishedAt, expiresAt, answer, sources, mergeTarget, errorCode, message);
        }
    }
}
