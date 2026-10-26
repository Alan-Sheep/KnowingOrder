package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnswerSupportReviewTest {
    private final ChatModel model = mock(ChatModel.class);
    private final AnswerSupportReview review = new AnswerSupportReview(model);
    private final ObjectMapper json = new ObjectMapper();
    private final Evidence evidence = new Evidence(1L, 2L, 3L, 1, "虚构制度", 0, "餐补每日30元。", 0, 8, 1, 1);
    private Part part(int id) { return new Part(id, "餐补多少？", "ANSWERED",
            List.of(new Paragraph("每日30元。", List.of(new Citation(1, evidence, "餐补每日30元")))), List.of()); }
    private TaskDeadline budget() { return TaskDeadline.start(Duration.ofSeconds(10)); }

    @Test void supportedReviewKeepsOriginalObjectAndUsesOneBoundedCall() {
        when(model.call(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            Map<?, ?> payload = call.getArgument(1);
            assertThat(payload.get("snapshotId").toString()).matches("[a-f0-9]{64}");
            assertThat(call.<TaskDeadline>getArgument(3).remaining()).isLessThanOrEqualTo(Duration.ofSeconds(5));
            return json.valueToTree(Map.of("snapshotId", payload.get("snapshotId"), "verdicts", List.of(Map.of("id", "1:0", "verdict", "SUPPORTED"))));
        });
        var parts = List.of(part(1));
        assertThat(review.review("餐补多少？", parts, budget())).containsExactlyElementsOf(parts);
        verify(model).call(eq("knowledge-support"), any(), eq(1200), any());
        verifyNoMoreInteractions(model);
    }

    @ParameterizedTest @ValueSource(strings = {"UNSUPPORTED", "UNKNOWN", "badSnapshot", "duplicate", "unknownId", "missing", "extra", "invalidVerdict"})
    void failedOrUnboundReviewNeverReturnsGeneratedText(String mutation) {
        when(model.call(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(1);
            var root = json.createObjectNode().put("snapshotId", mutation.equals("badSnapshot") ? "other" : input.get("snapshotId").toString());
            var items = root.putArray("verdicts");
            if (!mutation.equals("missing")) items.addObject().put("id", mutation.equals("unknownId") ? "9:0" : "1:0")
                    .put("verdict", List.of("UNSUPPORTED", "UNKNOWN").contains(mutation) ? mutation : mutation.equals("invalidVerdict") ? "YES" : "SUPPORTED");
            if (mutation.equals("duplicate")) items.add(items.get(0).deepCopy());
            if (mutation.equals("extra")) root.put("debug", "not allowed");
            return root;
        });
        var result = review.review("餐补多少？", List.of(part(1)), budget());
        assertThat(result.get(0).status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(result.get(0).paragraphs()).isEmpty();
        assertThat(result.get(0).gaps()).isNotEmpty();
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test void providerFailureDoesNotLeakDetailsOrRetry() {
        when(model.call(anyString(), any(), anyInt(), any())).thenThrow(new BusinessException("FAKE_SECRET"));
        var result = review.review("餐补多少？", List.of(part(1)), budget());
        assertThat(result.toString()).doesNotContain("FAKE_SECRET", "每日30元");
        assertThat(result.get(0).status()).isEqualTo("EVIDENCE_ONLY");
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test void subquestionsDowngradeIndependentlyAndNoEvidenceNeedsNoModel() {
        when(model.call(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(1);
            return json.valueToTree(Map.of("snapshotId", input.get("snapshotId"), "verdicts", List.of(
                    Map.of("id", "1:0", "verdict", "SUPPORTED"), Map.of("id", "2:0", "verdict", "UNSUPPORTED"))));
        });
        assertThat(review.review("两问", List.of(part(1), part(2)), budget())).extracting(Part::status).containsExactly("ANSWERED", "EVIDENCE_ONLY");
        clearInvocations(model);
        var empty = List.of(new Part(1, "何时到账？", "NO_EVIDENCE", List.of(), List.of("缺少时间依据")));
        assertThat(review.review("何时到账？", empty, budget())).isEqualTo(empty);
        verifyNoInteractions(model);
    }
}
