package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原始优先的离线实验预览，不接入正式回答，也不证明候选语义适用。
 * 调用方须提供同一可信成员及空间下的授权快照，并在展示前复核权限与当前发布版本。
 */
public final class KnowledgeEvidenceFusion {
    private static final int LIMIT = 6;
    private static final int OVERLAP = 120;

    private KnowledgeEvidenceFusion() { }

    public enum Origin { ORIGINAL, SUPPLEMENTAL, BOTH }

    public record Candidate(Evidence evidence, Origin origin) { }

    /** baseline逐项保留原始输入；candidates只供实验预览，三个排除计数互斥且按输入条目计数。 */
    public record Preview(List<Evidence> baseline, List<Candidate> candidates,
            int duplicateExcluded, int overlapExcluded, int capacityExcluded) {
        public Preview {
            baseline = List.copyOf(baseline);
            candidates = List.copyOf(candidates);
        }
    }

    /** 两路各最多6段；完整检查先于截断，重复、重叠、容量按此顺序归类，不改动输入。 */
    public static Preview fuse(List<Evidence> original, List<Evidence> supplemental) {
        List<Evidence> baseline = snapshot(original);
        List<Evidence> extra = snapshot(supplemental);
        Map<Long, Evidence> snapshots = new HashMap<>();
        verify(baseline, snapshots);
        verify(extra, snapshots);

        Set<Long> extraIds = new HashSet<>();
        for (Evidence evidence : extra) extraIds.add(evidence.chunkId());
        Map<Long, Candidate> selected = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        int duplicate = 0;
        int overlap = 0;
        int capacity = 0;
        for (Evidence evidence : baseline) {
            if (!seen.add(evidence.chunkId())) {
                duplicate++;
                continue;
            }
            selected.put(evidence.chunkId(), new Candidate(evidence,
                    extraIds.contains(evidence.chunkId()) ? Origin.BOTH : Origin.ORIGINAL));
        }
        for (Evidence evidence : extra) {
            if (!seen.add(evidence.chunkId())) {
                duplicate++;
            } else if (selected.values().stream().anyMatch(value -> overlaps(value.evidence(), evidence))) {
                overlap++;
            } else if (selected.size() == LIMIT) {
                capacity++;
            } else {
                selected.put(evidence.chunkId(), new Candidate(evidence, Origin.SUPPLEMENTAL));
            }
        }
        return new Preview(baseline, new ArrayList<>(selected.values()), duplicate, overlap, capacity);
    }

    private static List<Evidence> snapshot(List<Evidence> input) {
        if (input == null || input.size() > LIMIT)
            throw new BusinessException(ResultCode.PARAM_ERROR, "融合实验要求两路证据列表各不超过6段");
        // 不用List.copyOf吞掉空条目的业务错误；输入在调用期间须由调用方保持稳定。
        return new ArrayList<>(input);
    }

    private static void verify(List<Evidence> input, Map<Long, Evidence> snapshots) {
        for (Evidence evidence : input) {
            if (evidence == null || !positive(evidence.chunkId()) || !positive(evidence.documentId())
                    || !positive(evidence.versionId()) || evidence.versionNo() <= 0 || evidence.chunkIndex() < 0
                    || evidence.title() == null || evidence.title().isBlank()
                    || evidence.content() == null || evidence.content().isBlank() || evidence.content().length() > 1000
                    || evidence.startOffset() < 0 || evidence.endOffset() <= evidence.startOffset()
                    || (long) evidence.endOffset() - evidence.startOffset() != evidence.content().length()
                    || evidence.startLine() < 1 || evidence.endLine() < evidence.startLine())
                throw invalidSnapshot();
            // chunkId是全局主键；完整equals同时核对文档、版本、正文及全部溯源字段。
            Evidence previous = snapshots.putIfAbsent(evidence.chunkId(), evidence);
            if (previous != null && !previous.equals(evidence)) throw invalidSnapshot();
        }
    }

    private static boolean overlaps(Evidence first, Evidence second) {
        return first.documentId().equals(second.documentId()) && first.versionId().equals(second.versionId())
                && (long) Math.min(first.endOffset(), second.endOffset())
                    - Math.max(first.startOffset(), second.startOffset()) > OVERLAP;
    }

    private static boolean positive(Long id) { return id != null && id > 0; }

    private static BusinessException invalidSnapshot() {
        return new BusinessException(ResultCode.CONFLICT, "证据快照无效或不一致，请重新检索后再进行融合实验");
    }
}
