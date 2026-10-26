package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ragqa.rag.chat.QuestionPlanner.fields;
import static com.ragqa.rag.chat.QuestionPlanner.require;
import static com.ragqa.rag.chat.QuestionPlanner.text;

/** 一次有界模型调用完成多问作答；编号、原文引文与问题覆盖由程序验证，不接受模型路径或链接。 */
@Component
public class KnowledgeAnswerComposer {
    private final ChatModel model;
    public KnowledgeAnswerComposer(ChatModel model) { this.model = model; }

    public List<Part> compose(String question, List<Evidence> evidence, TaskDeadline deadline) {
        return compose(question, evidence, deadline, null);
    }

    /** 话题线索与当前原问题分开传输，返回覆盖校验仍只针对当前原文。 */
    public List<Part> compose(String question, List<Evidence> evidence, TaskDeadline deadline, String antecedentQuestion) {
        return compose(question, evidence, deadline, antecedentQuestion, null);
    }

    /** 校内分段已由范围契约固定，一次回答全部校内问题，不能由生成阶段再次改写边界。 */
    public List<Part> composeFixed(List<String> questions, List<Evidence> evidence, TaskDeadline deadline,
            String antecedentQuestion) {
        require(questions != null && !questions.isEmpty() && questions.size() <= 3);
        return compose(String.join("", questions), evidence, deadline, antecedentQuestion, List.copyOf(questions));
    }

    private List<Part> compose(String question, List<Evidence> evidence, TaskDeadline deadline,
            String antecedentQuestion, List<String> fixedQuestions) {
        var sources = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < evidence.size(); i++) {
            Evidence source = evidence.get(i);
            sources.add(Map.of("sourceId", i + 1, "title", source.title(), "versionNo", source.versionNo(),
                    "content", source.content()));
        }
        var payload = new HashMap<String, Object>();
        payload.put("question", question); payload.put("sources", sources);
        if (fixedQuestions != null) payload.put("questions", fixedQuestions);
        if (antecedentQuestion != null) payload.put("antecedentQuestion", antecedentQuestion);
        JsonNode root = model.call(fixedQuestions != null ? "knowledge-scoped"
                : antecedentQuestion == null ? "knowledge" : "knowledge-follow-up", payload, 3000, deadline);
        fields(root, "parts");
        require(root.path("parts").isArray() && !root.path("parts").isEmpty() && root.path("parts").size() <= 3);
        var result = new ArrayList<Part>();
        StringBuilder covered = new StringBuilder();
        for (JsonNode node : root.path("parts")) {
            fields(node, "question", "status", "paragraphs", "gaps");
            String original = text(node, "question", 1200, false);
            covered.append(normalize(original));
            String status = text(node, "status", 24, false);
            require(Set.of("ANSWERED", "PARTIAL", "NO_EVIDENCE", "CONFLICT").contains(status));
            require(node.path("paragraphs").isArray() && node.path("paragraphs").size() <= 3);
            var paragraphs = new ArrayList<Paragraph>();
            var partSources = new HashSet<Integer>();
            for (JsonNode paragraph : node.path("paragraphs")) {
                fields(paragraph, "text", "citations");
                String body = text(paragraph, "text", 650, false);
                require(!body.contains("<") && !body.contains(">") && !body.contains("http://") && !body.contains("https://"));
                require(paragraph.path("citations").isArray() && !paragraph.path("citations").isEmpty()
                        && paragraph.path("citations").size() <= 3);
                var citations = new ArrayList<Citation>();
                for (JsonNode citation : paragraph.path("citations")) {
                    fields(citation, "sourceId", "quote");
                    require(citation.path("sourceId").isInt());
                    int id = citation.path("sourceId").intValue();
                    // 允许同段引用同一出处的多条原句（外部模型常见习惯）；只要求出处编号有效。
                    require(id >= 1 && id <= evidence.size());
                    String quote = text(citation, "quote", 250, false);
                    require(quote.codePointCount(0, quote.length()) >= 4 && evidence.get(id - 1).content().contains(quote));
                    citations.add(new Citation(id, evidence.get(id - 1), quote));
                    partSources.add(id);
                }
                paragraphs.add(new Paragraph(body, List.copyOf(citations)));
            }
            require(node.path("gaps").isArray() && node.path("gaps").size() <= 3);
            var gaps = new ArrayList<String>();
            for (JsonNode gap : node.path("gaps")) {
                require(gap.isTextual() && !gap.textValue().isBlank() && gap.textValue().length() <= 200);
                gaps.add(gap.textValue());
            }
            require(status.equals("NO_EVIDENCE") ? paragraphs.isEmpty() : !paragraphs.isEmpty());
            require(status.equals("ANSWERED") ? gaps.isEmpty() : !gaps.isEmpty());
            require(!status.equals("CONFLICT") || partSources.size() >= 2);
            result.add(new Part(result.size() + 1, original, status, List.copyOf(paragraphs), List.copyOf(gaps)));
        }
        // 模型不能跳过用户后半句、把多个独立问题悄悄丢掉或改变其原文顺序。
        require(covered.toString().equals(normalize(question)));
        if (fixedQuestions != null) require(result.stream().map(Part::question).toList().equals(fixedQuestions));
        return List.copyOf(result);
    }

    /** 由服务在两阶段之间重新核权，不能因生成已成功而省略下一次外发前的授权检查。 */
    public List<Part> review(String question, List<Part> parts, TaskDeadline deadline) {
        return new AnswerSupportReview(model).review(question, parts, deadline);
    }

    private static String normalize(String value) { return value.replaceAll("[\\p{P}\\s]", ""); }
}
