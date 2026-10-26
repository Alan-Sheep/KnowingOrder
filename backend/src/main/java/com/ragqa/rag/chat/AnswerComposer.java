package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.rag.retrieval.EvidenceSelector.Entry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ragqa.rag.chat.QuestionPlanner.fields;
import static com.ragqa.rag.chat.QuestionPlanner.require;
import static com.ragqa.rag.chat.QuestionPlanner.text;

/** 评估与撰写分开调用；以原文引文校验事实输入，以服务端来源表校验最终引用。 */
@Component
public class AnswerComposer {

    private final ChatModel model;

    public AnswerComposer(ChatModel model) {
        this.model = model;
    }

    public Grade grade(String question, List<Entry> evidence, ChatBudget budget) {
        return grade(question, evidence, budget, EvidenceScope.from(question, budget.timeContext()));
    }

    /** 日期地点约束取用户原文，不能因模型改写漏词而绕过；补查复用同一约束。 */
    public Grade gradeQuestion(QuestionPlanner.Item item, List<Entry> evidence, ChatBudget budget) {
        return grade(item.query(), evidence, budget, EvidenceScope.from(item.question(), budget.timeContext()));
    }

    private Grade grade(String question, List<Entry> evidence, ChatBudget budget, EvidenceScope scope) {
        budget.beforeModel();
        JsonNode root = model.call("grade", Map.of("question", question,
                "evidence", evidence.stream().map(Entry::modelData).toList(),
                "timeContext", budget.timeContext().modelData(), "applicability", scope.modelData()), 2600, budget.deadline());
        fields(root, "verdict", "facts", "gaps", "followUpQuery");
        String verdict = text(root, "verdict", 20, false);
        require(Set.of("SUFFICIENT", "PARTIAL", "INSUFFICIENT").contains(verdict));
        require(root.path("facts").isArray() && root.path("facts").size() <= 8);
        List<Fact> facts = new ArrayList<>();
        for (JsonNode fact : root.path("facts")) {
            if (scope.weather()) fields(fact, "sourceId", "quote", "text", "appliesOn", "dateQuote", "locationQuote");
            else fields(fact, "sourceId", "quote", "text");
            require(fact.path("sourceId").isInt());
            int id = fact.path("sourceId").intValue();
            Entry source = evidence.stream().filter(entry -> entry.id() == id).findFirst()
                    .orElseThrow(ChatModel::invalid);
            String quote = text(fact, "quote", 400, false);
            require(source.content().contains(quote));
            String claim = text(fact, "text", 400, false);
            if (scope.accepts(source, fact, quote) && !scope.conflictingDay(claim)) facts.add(new Fact(id, quote, claim));
        }
        require(root.path("gaps").isArray() && root.path("gaps").size() <= 3);
        List<String> gaps = new ArrayList<>();
        for (JsonNode gap : root.path("gaps")) {
            require(gap.isTextual() && !gap.textValue().isBlank()
                    && gap.textValue().codePointCount(0, gap.textValue().length()) <= 160);
            gaps.add(gap.textValue());
        }
        String followUp = text(root, "followUpQuery", 500, true);
        require(verdict.equals("INSUFFICIENT") ? root.path("facts").isEmpty() : !root.path("facts").isEmpty());
        require(verdict.equals("SUFFICIENT") ? gaps.isEmpty() && followUp.isBlank() : !gaps.isEmpty());
        if (scope.weather() && facts.size() != root.path("facts").size()) {
            if (gaps.size() == 3) gaps.remove(2);
            gaps.add(scope.gap());
            verdict = facts.isEmpty() ? "INSUFFICIENT" : "PARTIAL";
            followUp = scope.date() == null || scope.location().isBlank() ? ""
                    : scope.date() + " " + scope.location() + " 天气 气温 年月日";
        }
        return new Grade(verdict, List.copyOf(facts), List.copyOf(gaps), followUp);
    }

    public String compose(QuestionPlanner.Item item, Grade grade, boolean web,
            List<Map<String, Object>> dependencies, ChatBudget budget) {
        budget.beforeModel();
        JsonNode root = model.call("generate", Map.of("question", item.query(), "originalQuestion", item.question(), "basis", web ? "WEB" : "GENERAL",
                "facts", grade.facts(), "gaps", grade.gaps(), "dependencies", dependencies,
                "timeContext", budget.timeContext().modelData(), "answerLanguage", budget.answerLanguage()),
                2200, budget.deadline());
        fields(root, "paragraphs");
        require(root.path("paragraphs").isArray() && !root.path("paragraphs").isEmpty()
                && root.path("paragraphs").size() <= 6);
        Set<Integer> permitted = grade.facts().stream().map(Fact::sourceId).collect(Collectors.toSet());
        StringBuilder answer = new StringBuilder();
        int bodyCodePoints = 0;
        for (JsonNode paragraph : root.path("paragraphs")) {
            fields(paragraph, "text", "sourceIds");
            String body = text(paragraph, "text", 800, false);
            require(!EvidenceScope.from(item.question(), budget.timeContext()).conflictingDay(body));
            bodyCodePoints += body.codePointCount(0, body.length());
            require(bodyCodePoints <= 4000);
            // 正文使用纯文本；编号和链接统一由程序添加，模型不得偷偷夹带新来源。
            require(!body.contains("<") && !body.contains(">") && !body.contains("[") && !body.contains("]"));
            JsonNode ids = paragraph.path("sourceIds");
            require(ids.isArray() && ids.size() <= 5 && (web ? !ids.isEmpty() : ids.isEmpty()));
            Set<Integer> used = new HashSet<>();
            if (!answer.isEmpty()) {
                answer.append("\n\n");
            }
            answer.append(body);
            for (JsonNode id : ids) {
                require(id.isInt() && permitted.contains(id.intValue()) && used.add(id.intValue()));
                answer.append(" [q").append(item.id()).append("-s").append(id.intValue()).append("]");
            }
        }
        require(answer.codePointCount(0, answer.length()) <= 4300);
        budget.active();
        return answer.toString();
    }

    public List<ChatAnswer.Source> sources(int questionId, List<Entry> evidence) {
        return evidence.stream().map(entry -> new ChatAnswer.Source("q" + questionId + "-s" + entry.id(),
                entry.title(), entry.url(), entry.content())).toList();
    }

    public record Fact(int sourceId, String quote, String text) {
    }

    public record Grade(String verdict, List<Fact> facts, List<String> gaps, String followUpQuery) {
        public static Grade general() {
            return new Grade("SUFFICIENT", List.of(), List.of(), "");
        }
    }
}
