package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 有界精确余弦排序与两路RRF，仅处理调用方已经授权的编号和向量。
 * 不访问数据库、不调用模型，不证明证据充分；不能用来先全库召回再过滤权限。
 */
public final class HybridRetrievalRanking {
    private HybridRetrievalRanking() { }

    public record Entry(long chunkId, Vector vector) { }
    public record Similarity(long chunkId, double score) { }
    public record Fused(long chunkId, double score, Integer keywordRank, Integer vectorRank) { }

    /** 同模型、同维度才可比较；先缩放再归一化，避免平方溢出或极小数下溢。 */
    public static double cosine(Vector left, Vector right) {
        sameModel(left, right);
        return dot(unit(left), unit(right));
    }

    /**
     * 内核最多接收512段，返回最多24段；超容量直接拒绝，不能静默只查前512段。
     * 阈值由调用方显式提供，包含等于阈值的项；全部校验先于截断，重复编号拒绝。
     */
    public static List<Similarity> rank(Vector query, List<Entry> candidates, double threshold, int limit) {
        limit(limit);
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1
                || candidates == null || candidates.size() > 512) throw invalid();
        double[] queryUnit = unit(query);
        var input = new ArrayList<>(candidates);
        var seen = new HashSet<Long>();
        var scores = new ArrayList<Similarity>();
        for (Entry candidate : input) {
            if (candidate == null || candidate.chunkId() <= 0 || !seen.add(candidate.chunkId())) throw invalid();
            sameModel(query, candidate.vector());
            double score = dot(queryUnit, unit(candidate.vector()));
            if (score >= threshold) scores.add(new Similarity(candidate.chunkId(), score));
        }
        scores.sort(Comparator.comparingDouble(Similarity::score).reversed().thenComparingLong(Similarity::chunkId));
        return List.copyOf(scores.subList(0, Math.min(limit, scores.size())));
    }

    /**
     * RRF按名次倒数融合，避免把关键词计分与余弦分数直接相加；名次从1开始。
     * 两路各最多24条且内部不重复；平分按编号升序稳定返回。常数显式传入，不预设生产参数。
     */
    public static List<Fused> fuse(List<Long> keywords, List<Long> vectors, int smoothing, int limit) {
        limit(limit);
        if (smoothing < 1 || smoothing > 1000) throw invalid();
        List<Long> lexical = ids(keywords), semantic = ids(vectors);
        Map<Long, Integer> keywordRanks = positions(lexical), vectorRanks = positions(semantic);
        var all = new HashSet<>(keywordRanks.keySet()); all.addAll(vectorRanks.keySet());
        var result = new ArrayList<Fused>();
        for (Long id : all) {
            Integer a = keywordRanks.get(id), b = vectorRanks.get(id);
            double score = (a == null ? 0 : 1.0 / (smoothing + a)) + (b == null ? 0 : 1.0 / (smoothing + b));
            result.add(new Fused(id, score, a, b));
        }
        result.sort(Comparator.comparingDouble(Fused::score).reversed().thenComparingLong(Fused::chunkId));
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    private static double[] unit(Vector vector) {
        if (vector == null || vector.model() == null || vector.model().isBlank()
                || vector.values() == null || vector.values().isEmpty() || vector.values().size() > 2048) throw invalid();
        double scale = 0;
        for (Double value : vector.values()) {
            if (value == null || !Double.isFinite(value)) throw invalid();
            scale = Math.max(scale, Math.abs(value));
        }
        if (scale == 0) throw invalid();
        double[] result = new double[vector.values().size()]; double squares = 0;
        for (int i = 0; i < result.length; i++) {
            double value = vector.values().get(i) / scale;
            result[i] = value; squares += value * value;
        }
        double norm = Math.sqrt(squares);
        for (int i = 0; i < result.length; i++) result[i] /= norm;
        return result;
    }

    private static void sameModel(Vector left, Vector right) {
        if (left == null || right == null || left.model() == null || !left.model().equals(right.model())
                || left.values().size() != right.values().size()) throw invalid();
    }
    private static double dot(double[] left, double[] right) {
        double sum = 0;
        for (int i = 0; i < left.length; i++) sum += left[i] * right[i];
        return Math.max(-1, Math.min(1, sum));
    }
    private static List<Long> ids(List<Long> values) {
        if (values == null || values.size() > 24) throw invalid();
        var snapshot = new ArrayList<>(values); var seen = new HashSet<Long>();
        for (Long id : snapshot) if (id == null || id <= 0 || !seen.add(id)) throw invalid();
        return List.copyOf(snapshot);
    }
    private static Map<Long, Integer> positions(List<Long> values) {
        Map<Long, Integer> result = new HashMap<>();
        for (int i = 0; i < values.size(); i++) result.put(values.get(i), i + 1);
        return result;
    }
    private static void limit(int value) { if (value < 1 || value > 24) throw invalid(); }
    private static BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "检索排序输入、模型、维度或容量不符合契约");
    }
}
