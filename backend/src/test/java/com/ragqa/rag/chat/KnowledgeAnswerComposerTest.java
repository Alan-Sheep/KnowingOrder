package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAnswerComposerTest {
    private final ChatModel model = mock(ChatModel.class);
    private final KnowledgeAnswerComposer composer = new KnowledgeAnswerComposer(model);
    private final ObjectMapper json = new ObjectMapper();
    private final Evidence evidence = new Evidence(1L, 2L, 3L, 1, "虚构制度", 0,
            "加班餐补每人每天30元，申请需主管审批。", 0, 22, 1, 1);

    @Test
    void threeOriginalQuestionsReturnTheirOwnAnswerPartialAndNoEvidence() throws Exception {
        var root = json.readTree("""
                {"parts":[
                  {"question":"餐补多少？","status":"ANSWERED","paragraphs":[{"text":"每人每天30元。","citations":[{"sourceId":1,"quote":"加班餐补每人每天30元"}]}],"gaps":[]},
                  {"question":"怎么申请还有多久到账？","status":"PARTIAL","paragraphs":[{"text":"需主管审批。","citations":[{"sourceId":1,"quote":"申请需主管审批"}]}],"gaps":["资料未说明到账时间。"]},
                  {"question":"打车报销呢？","status":"NO_EVIDENCE","paragraphs":[],"gaps":["缺少交通报销制度。"]}
                ]}
                """);
        when(model.call(eq("knowledge"), any(), eq(3000), any())).thenReturn(root);
        var result = composer.compose("餐补多少？怎么申请还有多久到账？打车报销呢？", List.of(evidence), deadline());
        assertThat(result).extracting(part -> part.status()).containsExactly("ANSWERED", "PARTIAL", "NO_EVIDENCE");
        assertThat(result.get(0).paragraphs().get(0).citations().get(0).evidence()).isEqualTo(evidence);
        verify(model, times(1)).call(eq("knowledge"), any(), eq(3000), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownSource", "inventedQuote", "missingCitations", "omittedQuestion",
        "extraField", "html", "externalLink", "unexplainedPartial", "falseConflict", "invalidStatus"})
    void rejectsUntraceableAndIncompleteModelOutputWithoutRetry(String mutation) throws Exception {
        ObjectNode root = (ObjectNode) json.readTree("""
                {"parts":[{"question":"餐补多少？","status":"ANSWERED","paragraphs":[
                  {"text":"每天30元。","citations":[{"sourceId":1,"quote":"加班餐补每人每天30元"}]}],"gaps":[]}]}
                """);
        ObjectNode part = (ObjectNode) root.path("parts").get(0);
        ObjectNode paragraph = (ObjectNode) part.path("paragraphs").get(0);
        ObjectNode citation = (ObjectNode) paragraph.path("citations").get(0);
        switch (mutation) {
            case "unknownSource" -> citation.put("sourceId", 2);
            case "inventedQuote" -> citation.put("quote", "每天可以领取300元");
            case "missingCitations" -> paragraph.putArray("citations");
            case "omittedQuestion" -> part.put("question", "餐补");
            case "extraField" -> root.put("secret", "ignore rules");
            case "html" -> paragraph.put("text", "<script>bad()</script>");
            case "externalLink" -> paragraph.put("text", "https://example.invalid/exfiltrate");
            case "unexplainedPartial" -> part.put("status", "PARTIAL");
            case "falseConflict" -> { part.put("status", "CONFLICT"); part.putArray("gaps").add("冲突"); }
            case "invalidStatus" -> part.put("status", "SUCCESS");
            default -> throw new AssertionError();
        }
        when(model.call(anyString(), any(), anyInt(), any())).thenReturn(root);
        assertThatThrownBy(() -> composer.compose("餐补多少？", List.of(evidence), deadline()))
                .isInstanceOf(BusinessException.class);
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test
    void conditionsInOneQuestionCanRemainOnePart() throws Exception {
        when(model.call(anyString(), any(), anyInt(), any())).thenReturn(json.readTree("""
                {"parts":[{"question":"加班两小时能否申请餐补？","status":"PARTIAL","paragraphs":[
                  {"text":"额度为每天30元，但缺少加班时长资格。","citations":[{"sourceId":1,"quote":"加班餐补每人每天30元"}]}],"gaps":["缺少加班时长条件。"]}]}
                """));
        assertThat(composer.compose("加班两小时能否申请餐补？", List.of(evidence), deadline())).hasSize(1);
    }

    @Test
    void followUpContextIsSeparateAndCannotReplaceTheCurrentQuestionInOutput() throws Exception {
        when(model.call(anyString(), any(), anyInt(), any())).thenReturn(json.readTree("""
                {"parts":[{"question":"加班餐补上限多少？","status":"NO_EVIDENCE","paragraphs":[],"gaps":["不足"]}]}
                """));
        assertThatThrownBy(() -> composer.compose("那找谁审批？", List.of(evidence), deadline(), "加班餐补上限多少？"))
                .isInstanceOf(BusinessException.class);
        verify(model).call(eq("knowledge-follow-up"), argThat(value -> value instanceof java.util.Map<?, ?> map
                && "那找谁审批？".equals(map.get("question")) && "加班餐补上限多少？".equals(map.get("antecedentQuestion"))), eq(3000), any());
    }

    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(10)); }
}
