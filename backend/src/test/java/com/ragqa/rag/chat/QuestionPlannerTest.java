package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.config.WebChatProperties;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionPlannerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model = mock(ChatModel.class);
    private final QuestionPlanner planner = new QuestionPlanner(model, new WebChatProperties());

    @Test
    void separatesDependencyOrderFromDisplayOrderAndPreservesConditions() {
        JsonNode plan = json.valueToTree(Map.of("overflow", false, "questions", List.of(
                item(1, "根据结果准备面试", List.of(2)), item(2, "查北京岗位要求", List.of()),
                item(3, "晚饭20元不吃辣", List.of())), "displayOrder", List.of(3, 1, 2)));
        stub(plan);
        QuestionPlanner.Plan actual = planner.plan("根据结果准备面试；查北京岗位要求；晚饭20元不吃辣", budget());
        assertThat(actual.executionOrder()).extracting(QuestionPlanner.Item::id).containsExactly(2, 1, 3);
        assertThat(actual.displayOrder()).containsExactly(3, 1, 2);
        assertThat(actual.executionOrder().get(2).question()).isEqualTo("晚饭20元不吃辣");
    }

    @ParameterizedTest
    @ValueSource(strings = {"self", "cycle", "duplicateOrder", "foreignFragment", "wrongType", "extraField"})
    void rejectsUnsafePlans(String mode) {
        ObjectNode root = json.valueToTree(Map.of("overflow", false, "questions", List.of(
                item(1, "查岗位", List.of()), item(2, "面试准备", List.of())), "displayOrder", List.of(1, 2)));
        switch (mode) {
            case "self" -> ((ObjectNode) root.at("/questions/0")).set("dependsOn", json.valueToTree(List.of(1)));
            case "cycle" -> {
                ((ObjectNode) root.at("/questions/0")).set("dependsOn", json.valueToTree(List.of(2)));
                ((ObjectNode) root.at("/questions/1")).set("dependsOn", json.valueToTree(List.of(1)));
            }
            case "duplicateOrder" -> root.set("displayOrder", json.valueToTree(List.of(1, 1)));
            case "foreignFragment" -> ((ObjectNode) root.at("/questions/0")).put("question", "原文不存在");
            case "wrongType" -> ((ObjectNode) root.at("/questions/0")).put("needsSearch", "true");
            case "extraField" -> root.put("executeSql", "禁止执行");
            default -> throw new AssertionError();
        }
        stub(root);
        assertThatThrownBy(() -> planner.plan("查岗位；面试准备", budget())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("校验未通过");
    }

    @Test
    void rejectsOverflowWithoutStartingSearch() {
        stub(json.valueToTree(Map.of("overflow", true, "questions", List.of(), "displayOrder", List.of())));
        assertThatThrownBy(() -> planner.plan("四个问题", budget())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("分批提交");
    }

    private Map<String, Object> item(int id, String question, List<Integer> dependencies) {
        return Map.of("id", id, "question", question, "query", question, "needsSearch", true,
                "dependsOn", dependencies, "clarification", "");
    }

    private void stub(JsonNode node) {
        when(model.call(eq("plan"), any(), anyInt(), any())).thenReturn(node);
    }

    private static TaskDeadline budget() {
        return TaskDeadline.start(Duration.ofSeconds(5));
    }
}
