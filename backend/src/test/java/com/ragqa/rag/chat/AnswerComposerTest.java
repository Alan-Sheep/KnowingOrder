package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.EvidenceSelector.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerComposerTest {
    private static final List<Entry> EVIDENCE = List.of(new Entry(1, "官方说明", "https://example.com/job", "岗位要求掌握 Java 17。"));

    @Test
    void acceptsExactQuotesAndAddsServerOwnedCitationIdentifiers() {
        try (ChatTestFixture f = new ChatTestFixture()) {
            ChatBudget budget = new ChatBudget(f.properties);
            AnswerComposer.Grade grade = f.composer.grade("岗位要求", EVIDENCE, budget);
            String answer = f.composer.compose(item(), grade, true, List.of(), budget);
            assertThat(answer).isEqualTo("岗位要求掌握 Java 17。 [q1-s1]");
            assertThat(f.composer.sources(1, EVIDENCE).get(0).id()).isEqualTo("q1-s1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreignSource", "inventedQuote", "inconsistentVerdict", "badType", "extraField"})
    void refusesUnverifiableOrMalformedGrades(String mode) {
        try (ChatTestFixture f = new ChatTestFixture()) {
            f.grade = payload -> {
                ObjectNode root = f.json.valueToTree(Map.of("verdict", "SUFFICIENT", "facts", List.of(Map.of(
                        "sourceId", 1, "quote", "Java 17", "text", "要求 Java 17")), "gaps", List.of(), "followUpQuery", ""));
                switch (mode) {
                    case "foreignSource" -> ((ObjectNode) root.at("/facts/0")).put("sourceId", 9);
                    case "inventedQuote" -> ((ObjectNode) root.at("/facts/0")).put("quote", "不存在的薪资");
                    case "inconsistentVerdict" -> root.put("verdict", "PARTIAL");
                    case "badType" -> ((ObjectNode) root.at("/facts/0")).put("sourceId", "1");
                    case "extraField" -> root.put("password", "fake-secret-marker");
                    default -> throw new AssertionError();
                }
                return root;
            };
            assertThatThrownBy(() -> f.composer.grade("问题", EVIDENCE, new ChatBudget(f.properties)))
                    .isInstanceOf(BusinessException.class).hasMessageNotContaining("fake-secret-marker");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreignSource", "noSources", "repeatedSource", "html", "inlineCitation", "wrongType"})
    void refusesInventedCitationsAndUnsafeAnswerStructure(String mode) {
        try (ChatTestFixture f = new ChatTestFixture()) {
            f.generate = payload -> f.node(Map.of("paragraphs", List.of(Map.of("text",
                    mode.equals("html") ? "<script>" : mode.equals("inlineCitation") ? "结论[99]" : "正文",
                    "sourceIds", switch (mode) {
                        case "foreignSource" -> List.of(999);
                        case "noSources" -> List.of();
                        case "repeatedSource" -> List.of(1, 1);
                        case "wrongType" -> List.of("1");
                        default -> List.of(1);
                    }))));
            AnswerComposer.Grade grade = new AnswerComposer.Grade("SUFFICIENT",
                    List.of(new AnswerComposer.Fact(1, "Java 17", "要求 Java 17")), List.of(), "");
            assertThatThrownBy(() -> f.composer.compose(item(), grade, true, List.of(), new ChatBudget(f.properties)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4000, 4001})
    void enforcesTotalBodyLimitByUnicodeCodePoints(int length) {
        try (ChatTestFixture f = new ChatTestFixture()) {
            java.util.ArrayList<Map<String, Object>> paragraphs = new java.util.ArrayList<>();
            for (int remaining = length; remaining > 0; remaining -= 800) {
                paragraphs.add(Map.of("text", "😀".repeat(Math.min(800, remaining)), "sourceIds", List.of()));
            }
            f.generate = payload -> f.node(Map.of("paragraphs", paragraphs));
            if (length == 4000) {
                String answer = f.composer.compose(item(), AnswerComposer.Grade.general(), false,
                        List.of(), new ChatBudget(f.properties));
                assertThat(answer.replace("\n", "").codePoints().count()).isEqualTo(4000);
            } else {
                assertThatThrownBy(() -> f.composer.compose(item(), AnswerComposer.Grade.general(), false,
                        List.of(), new ChatBudget(f.properties))).isInstanceOf(BusinessException.class);
            }
        }
    }

    private QuestionPlanner.Item item() {
        return new QuestionPlanner.Item(1, "岗位要求", "岗位要求", true, List.of(), "");
    }
}
