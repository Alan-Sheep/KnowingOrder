package com.ragqa.rag.chat;

import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.KnowledgeEvidenceAdmission;
import com.ragqa.rag.retrieval.KnowledgeEvidenceAdmission.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 手工判定只验证准入组合，不计作自动语义识别或模型质量。 */
class KnowledgeEvidenceAdmissionTest {
    private final Evidence first = evidence(1, "宠物保险未说明");
    private final Evidence second = evidence(2, "加班餐补30元");
    private Evidence evidence(long id, String content) {
        return new Evidence(id, id, id, 1, "制度", 0, content, 0, content.length(), 1, 1);
    }
    private Review review(Meaning meaning, List<Item> items) {
        return new Review("宠物保险多少钱", "餐补多少钱", meaning, items);
    }
    private KnowledgeEvidenceAdmission.Decision run(Review review) {
        return KnowledgeEvidenceAdmission.decide("宠物保险多少钱", "餐补多少钱", List.of(first), List.of(second), review);
    }
    @Test void missingReviewKeepsBaseline() {
        assertThat(run(null).preview().candidates()).extracting(x -> x.evidence()).containsExactly(first);
    }
    @ParameterizedTest @EnumSource(value = Meaning.class, names = {"BLOCK", "UNKNOWN"})
    void rejectingMeaningOverridesAcceptedEvidence(Meaning meaning) {
        assertThat(run(review(meaning, List.of(new Item(second, Applicability.ACCEPT)))).preview().candidates())
                .extracting(x -> x.evidence()).containsExactly(first);
    }
    @ParameterizedTest @EnumSource(Applicability.class)
    void eachEvidenceNeedsItsOwnDecision(Applicability applicability) {
        var result = run(review(Meaning.PASS, List.of(new Item(second, applicability))));
        assertThat(result.preview().candidates()).hasSize(applicability == Applicability.ACCEPT ? 2 : 1);
    }
    @Test void questionBindingCannotBeReused() {
        assertThat(run(new Review("别的问题", "餐补多少钱", Meaning.PASS,
                List.of(new Item(second, Applicability.ACCEPT)))).reason()).isEqualTo("UNVERIFIED");
    }
    @Test void missingEvidenceDecisionDoesNotAdmitIt() {
        assertThat(run(review(Meaning.PASS, List.of())).preview().candidates()).hasSize(1);
    }
    @Test void wrongSnapshotIsRejected() {
        assertThat(run(review(Meaning.PASS, List.of(new Item(evidence(2, "已修改"), Applicability.ACCEPT))))
                .reason()).isEqualTo("INVALID_REVIEW");
    }
    @Test void duplicateReviewIsNotLastWriteWins() {
        assertThat(run(review(Meaning.PASS, List.of(new Item(second, Applicability.ACCEPT),
                new Item(second, Applicability.REJECT)))).reason()).isEqualTo("INVALID_REVIEW");
    }
    @Test void conflictingRawSnapshotsStillFailBeforeFiltering() {
        assertThatThrownBy(() -> KnowledgeEvidenceAdmission.decide("q", "r", List.of(first),
                List.of(evidence(1, "不同内容")), null)).isInstanceOf(BusinessException.class);
    }
    @Test void emptyBaselineDoesNotPromoteUnknownEvidence() {
        var result = KnowledgeEvidenceAdmission.decide("q", "r", List.of(), List.of(second), null);
        assertThat(result.preview().candidates()).isEmpty();
    }
    @Test void outputCannotChangeWhenInputCollectionChanges() {
        var input = new ArrayList<>(List.of(first));
        var result = KnowledgeEvidenceAdmission.decide("q", "r", input, List.of(), null);
        input.clear();
        assertThat(result.preview().baseline()).containsExactly(first);
        assertThatThrownBy(() -> result.preview().baseline().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void fullBaselineCannotBeEvicted() {
        var baseline = java.util.stream.LongStream.rangeClosed(10, 15).mapToObj(i -> evidence(i, "原文")).toList();
        var result = KnowledgeEvidenceAdmission.decide("宠物保险多少钱", "餐补多少钱", baseline, List.of(second),
                review(Meaning.PASS, List.of(new Item(second, Applicability.ACCEPT))));
        assertThat(result.preview().baseline()).isEqualTo(baseline);
        assertThat(result.preview().candidates()).hasSize(6);
    }
}
