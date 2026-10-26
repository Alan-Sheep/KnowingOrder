package com.ragqa.rag.retrieval;

import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 补充证据准入的纯决策；判定来源必须由受控服务提供，本类不具备自动语义识别能力。 */
public final class KnowledgeEvidenceAdmission {
    private KnowledgeEvidenceAdmission() { }

    public enum Meaning { PASS, BLOCK, UNKNOWN }
    public enum Applicability { ACCEPT, REJECT, UNKNOWN }
    public record Item(Evidence snapshot, Applicability applicability) { }
    public record Review(String originalQuestion, String alternativeQuery, Meaning meaning, List<Item> items) {
        public Review { items = items == null ? List.of() : List.copyOf(items); }
    }
    public record Decision(String reason, KnowledgeEvidenceFusion.Preview preview) { }

    /** 完整输入一致性先于过滤；缺失、错绑和未知均保留原始基线，不从候选数推断可答。 */
    public static Decision decide(String question, String alternative, List<Evidence> original,
            List<Evidence> supplemental, Review review) {
        // 校验两路全部快照，不能用准入剔除隐藏同编号内容冲突或超量输入。
        KnowledgeEvidenceFusion.fuse(original, supplemental);
        var baseline = KnowledgeEvidenceFusion.fuse(original, List.of());
        if (question == null || question.isBlank() || alternative == null || alternative.isBlank()
                || review == null || !question.equals(review.originalQuestion())
                || !alternative.equals(review.alternativeQuery()) || review.meaning() == null) {
            return new Decision("UNVERIFIED", baseline);
        }
        if (review.meaning() != Meaning.PASS) return new Decision(review.meaning().name(), baseline);
        Map<Long, Evidence> candidates = new HashMap<>();
        for (Evidence item : supplemental) candidates.put(item.chunkId(), item);
        Map<Long, Applicability> accepted = new HashMap<>();
        for (Item item : review.items()) {
            if (item == null || item.snapshot() == null || item.applicability() == null
                    || !Objects.equals(candidates.get(item.snapshot().chunkId()), item.snapshot())
                    || accepted.putIfAbsent(item.snapshot().chunkId(), item.applicability()) != null) {
                return new Decision("INVALID_REVIEW", baseline);
            }
        }
        List<Evidence> eligible = supplemental.stream()
                .filter(item -> accepted.get(item.chunkId()) == Applicability.ACCEPT).toList();
        return new Decision(eligible.isEmpty() ? "BASELINE_ONLY" : "REVIEWED_SUPPLEMENT",
                KnowledgeEvidenceFusion.fuse(original, eligible));
    }
}
