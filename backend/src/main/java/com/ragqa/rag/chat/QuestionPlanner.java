package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.ResultCode;
import com.ragqa.config.WebChatProperties;
import com.ragqa.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保守校验模型计划：原文可追溯、依赖无环、展示顺序不影响必要执行依赖。 */
@Component
public class QuestionPlanner {

    private final ChatModel model;
    private final WebChatProperties properties;

    public QuestionPlanner(ChatModel model, WebChatProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    public Plan plan(String question, TaskDeadline deadline) {
        return plan(question, deadline, RequestTimeContext.from(
                new com.ragqa.domain.dto.ChatRequest(question, null, null), deadline));
    }

    public Plan plan(String question, TaskDeadline deadline, RequestTimeContext timeContext) {
        JsonNode root = model.call("plan", Map.of("question", question,
                "maxQuestions", properties.getMaxQuestions(), "timeContext", timeContext.modelData()), 1600, deadline);
        fields(root, "overflow", "questions", "displayOrder");
        require(root.path("overflow").isBoolean());
        require(root.path("questions").isArray() && root.path("displayOrder").isArray());
        if (root.path("overflow").booleanValue()) {
            require(root.path("questions").isEmpty() && root.path("displayOrder").isEmpty());
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "本次最多处理 " + properties.getMaxQuestions() + " 个问题，请分批提交；本次尚未开始检索");
        }
        int size = root.path("questions").size();
        require(size > 0 && size <= properties.getMaxQuestions());
        List<Item> items = new ArrayList<>();
        int previousEnd = 0;
        for (JsonNode node : root.path("questions")) {
            fields(node, "id", "question", "query", "needsSearch", "dependsOn", "clarification");
            require(node.path("id").isInt() && node.path("id").intValue() == items.size() + 1);
            String fragment = text(node, "question", 500, false);
            int start = question.indexOf(fragment, previousEnd);
            require(start >= previousEnd);
            previousEnd = start + fragment.length();
            String query = text(node, "query", 500, false);
            query = EvidenceScope.anchor(query, fragment, timeContext);
            require(query.codePointCount(0, query.length()) <= 500);
            String clarification = text(node, "clarification", 160, true);
            require(node.path("needsSearch").isBoolean());
            List<Integer> dependencies = ids(node.path("dependsOn"), size);
            require(!dependencies.contains(node.path("id").intValue()));
            boolean needsSearch = node.path("needsSearch").booleanValue() || EvidenceScope.from(fragment, timeContext).weather();
            items.add(new Item(items.size() + 1, fragment, query, needsSearch,
                    dependencies, clarification));
        }
        List<Integer> display = ids(root.path("displayOrder"), size);
        require(display.size() == size);
        List<Item> execution = new ArrayList<>();
        Set<Integer> done = new HashSet<>();
        while (execution.size() < size) {
            Item ready = items.stream().filter(item -> !done.contains(item.id())
                    && done.containsAll(item.dependsOn())).findFirst().orElseThrow(ChatModel::invalid);
            execution.add(ready);
            done.add(ready.id());
        }
        return new Plan(List.copyOf(execution), List.copyOf(display));
    }

    static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject() && node.size() == names.length);
        for (String name : names) {
            require(node.has(name));
        }
    }

    static String text(JsonNode node, String field, int limit, boolean blankAllowed) {
        JsonNode value = node.path(field);
        require(value.isTextual());
        String text = value.textValue();
        require(text.length() <= limit * 2 && text.codePointCount(0, text.length()) <= limit);
        require(blankAllowed || !text.isBlank());
        return text;
    }

    static void require(boolean valid) {
        if (!valid) {
            throw ChatModel.invalid();
        }
    }

    private static List<Integer> ids(JsonNode array, int size) {
        require(array.isArray() && array.size() <= size);
        List<Integer> values = new ArrayList<>();
        for (JsonNode entry : array) {
            require(entry.isInt() && entry.intValue() > 0 && entry.intValue() <= size);
            require(!values.contains(entry.intValue()));
            values.add(entry.intValue());
        }
        return List.copyOf(values);
    }

    public record Item(int id, String question, String query, boolean needsSearch,
            List<Integer> dependsOn, String clarification) {
    }

    public record Plan(List<Item> executionOrder, List<Integer> displayOrder) {
    }
}
