package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.WebChatProperties;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.EvidenceSelector;
import com.ragqa.rag.retrieval.TavilyClient;
import com.ragqa.service.WebChatService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 固定供应商替身只用于确定性流程测试，不用于声称真实模型质量达标。 */
final class ChatTestFixture implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper();
    final ResponsesClient client = mock(ResponsesClient.class);
    final TavilyClient search = mock(TavilyClient.class);
    final WebChatProperties properties = new WebChatProperties();
    final ChatModel model = new ChatModel(client, new PromptRegistry(), json);
    final AnswerComposer composer = new AnswerComposer(model);
    final List<Call> calls = new CopyOnWriteArrayList<>();
    Function<JsonNode, JsonNode> plan = payload -> plan(List.of(item(1, payload.path("question").asText(), true, List.of())), List.of(1));
    Function<JsonNode, JsonNode> grade = payload -> {
        JsonNode first = payload.path("evidence").get(0);
        String quote = EvidenceSelector.truncate(first.path("content").asText(), 200);
        return node(Map.of("verdict", "SUFFICIENT", "facts", List.of(Map.of("sourceId", first.path("id").asInt(),
                "quote", quote, "text", quote)), "gaps", List.of(), "followUpQuery", ""));
    };
    Function<JsonNode, JsonNode> generate = payload -> {
        boolean web = payload.path("basis").asText().equals("WEB");
        return node(Map.of("paragraphs", List.of(Map.of("text", web ? payload.at("/facts/0/text").asText()
                : "建议准备项目介绍与基础知识，并安排适当休息。", "sourceIds",
                web ? List.of(payload.at("/facts/0/sourceId").asInt()) : List.of()))));
    };
    final WebChatService service;

    ChatTestFixture() {
        try {
            when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
                String rules = invocation.getArgument(0);
                String input = invocation.getArgument(1);
                JsonNode payload = json.readTree(input.substring(input.indexOf('{')));
                String stage = rules.contains("诉求分析器") ? "plan" : rules.contains("资料充分性评估器") ? "grade" : "generate";
                calls.add(new Call(stage, invocation.getArgument(3), payload));
                return switch (stage) {
                    case "plan" -> plan.apply(payload).toString();
                    case "grade" -> grade.apply(payload).toString();
                    default -> generate.apply(payload).toString();
                };
            });
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        service = new WebChatService(new QuestionPlanner(model, properties), search,
                new EvidenceSelector(), composer, properties);
    }

    JsonNode node(Object data) {
        return json.valueToTree(data);
    }

    JsonNode plan(List<Map<String, Object>> questions, List<Integer> order) {
        return node(Map.of("overflow", false, "questions", questions, "displayOrder", order));
    }

    Map<String, Object> item(int id, String question, boolean needsSearch, List<Integer> dependencies) {
        return Map.of("id", id, "question", question, "query", question, "needsSearch", needsSearch,
                "dependsOn", dependencies, "clarification", "");
    }

    @Override
    public void close() {
        service.close();
    }

    record Call(String stage, TaskDeadline deadline, JsonNode payload) {
    }
}
