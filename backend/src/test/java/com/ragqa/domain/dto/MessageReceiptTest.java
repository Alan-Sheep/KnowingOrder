package com.ragqa.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.Result;
import com.ragqa.config.JacksonConfig;
import com.ragqa.domain.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证消息回执契约、前端编号精度和结束状态，不启动数据库或调用第三方。 */
class MessageReceiptTest {

    private final ObjectMapper mapper = configuredMapper();

    @Test
    void acceptedMessageDoesNotClaimAnswerHasCompleted() throws Exception {
        MessageReceipt receipt = new MessageReceipt(1L, 2L, 3L, 1,
                TaskStatus.QUEUED, "消息已接收，等待处理");

        JsonNode root = mapper.readTree(mapper.writeValueAsString(Result.ok(receipt)));

        assertThat(root.size()).isEqualTo(4);
        assertThat(root.get("code").intValue()).isZero();
        assertThat(root.get("timestamp").isIntegralNumber()).isTrue();
        JsonNode data = root.get("data");
        assertThat(data.size()).isEqualTo(6);
        assertThat(data.get("status").textValue()).isEqualTo("QUEUED");
        assertThat(data.get("message").textValue()).isEqualTo("消息已接收，等待处理");
        assertThat(data.get("version").isIntegralNumber()).isTrue();
        assertThat(data.get("version").longValue()).isEqualTo(1);
        assertThat(data.has("answer")).isFalse();
        assertThat(receipt.status().isTerminal()).isFalse();
    }

    @Test
    void identifiersLargerThanJavascriptSafeIntegerRemainExact() throws Exception {
        MessageReceipt receipt = new MessageReceipt(9_007_199_254_740_993L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE, 2,
                TaskStatus.RUNNING, "正在处理");

        String json = mapper.writeValueAsString(receipt);
        JsonNode data = mapper.readTree(json);

        assertThat(data.get("conversationId").isTextual()).isTrue();
        assertThat(data.get("conversationId").textValue()).isEqualTo("9007199254740993");
        assertThat(data.get("messageId").isTextual()).isTrue();
        assertThat(data.get("messageId").textValue()).isEqualTo("9223372036854775806");
        assertThat(data.get("taskId").isTextual()).isTrue();
        assertThat(data.get("taskId").textValue()).isEqualTo("9223372036854775807");
        assertThat(mapper.readValue(json, MessageReceipt.class)).isEqualTo(receipt);
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void taskStatesRemainStableJsonStringsAndSupportReceiptReplay(TaskStatus status) throws Exception {
        MessageReceipt receipt = new MessageReceipt(1L, 2L, 3L, 2, status, "原消息已接收，请查看任务状态");

        JsonNode data = mapper.readTree(mapper.writeValueAsString(receipt));

        assertThat(data.get("status").isTextual()).isTrue();
        assertThat(data.get("status").textValue()).isEqualTo(status.name());
        assertThat(mapper.readValue(data.toString(), MessageReceipt.class)).isEqualTo(receipt);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"QUEUED", "RUNNING"})
    void activeStatesKeepTaskWaitingIndicator(TaskStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"SUCCEEDED", "PARTIAL", "NO_SOURCES", "FAILED",
            "TIMED_OUT", "CANCELLED", "MERGED", "NEEDS_CLARIFICATION"})
    void endedTasksIncludingMergedAndClarificationStopTheirOwnWaitingIndicator(TaskStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void rejectsMissingOrInvalidIdentifiersWithoutEchoingMessage(Long conversationId, Long messageId,
                                                                 Long taskId, String field) {
        assertThatThrownBy(() -> new MessageReceipt(conversationId, messageId, taskId,
                1, TaskStatus.QUEUED, "不应出现在异常中的原始内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(field + " 必须为正整数");
    }

    static Stream<Arguments> invalidIdentifiers() {
        return Stream.of(
                Arguments.of(null, 2L, 3L, "conversationId"),
                Arguments.of(0L, 2L, 3L, "conversationId"),
                Arguments.of(-1L, 2L, 3L, "conversationId"),
                Arguments.of(1L, null, 3L, "messageId"),
                Arguments.of(1L, 0L, 3L, "messageId"),
                Arguments.of(1L, -1L, 3L, "messageId"),
                Arguments.of(1L, 2L, null, "taskId"),
                Arguments.of(1L, 2L, 0L, "taskId"),
                Arguments.of(1L, 2L, -1L, "taskId"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveVersions(long version) {
        assertThatThrownBy(() -> new MessageReceipt(1L, 2L, 3L, version, TaskStatus.QUEUED, "已接收"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("version 必须为正整数");
    }

    @Test
    void rejectsMissingTaskStatus() {
        assertThatThrownBy(() -> new MessageReceipt(1L, 2L, 3L, 1, null, "已接收"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status 不能为空");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankUserFeedback(String message) {
        assertThatThrownBy(() -> new MessageReceipt(1L, 2L, 3L, 1, TaskStatus.QUEUED, message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message 不能为空白");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"conversationId\":\"1\",\"messageId\":\"2\",\"taskId\":\"3\",\"status\":\"QUEUED\",\"message\":\"已接收\"}",
            "{\"conversationId\":\"1\",\"messageId\":\"2\",\"version\":1,\"status\":\"QUEUED\",\"message\":\"已接收\"}",
            "{\"conversationId\":\"1\",\"messageId\":\"2\",\"taskId\":\"3\",\"version\":1,\"message\":\"已接收\"}",
            "{\"conversationId\":\"1\",\"messageId\":\"2\",\"taskId\":\"3\",\"version\":1,\"status\":\"QUEUED\",\"message\":\" \"}"
    })
    void deserializationCannotBypassRequiredFields(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, MessageReceipt.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownTaskStatusIsRejectedInsteadOfBeingTreatedAsActiveOrSuccessful() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"conversationId":"1","messageId":"2","taskId":"3","version":1,
                 "status":"UNKNOWN","message":"已接收"}
                """, MessageReceipt.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    private static ObjectMapper configuredMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        return builder.build();
    }
}
