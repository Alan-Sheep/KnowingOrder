package com.ragqa.rag.retrieval;

import com.ragqa.exception.BusinessException;
import com.ragqa.rag.retrieval.HybridRetrievalRanking.Entry;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static com.ragqa.rag.retrieval.HybridRetrievalRanking.*;
import static org.assertj.core.api.Assertions.*;

/** 仅合成数学向量，不把排序断言当作Embedding语义质量、数据库或正式问答验收。 */
class HybridRetrievalRankingTest {
    private Vector vector(double... values) { return new Vector("synthetic:1", Arrays.stream(values).boxed().toList()); }
    private void invalid(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(400));
    }

    @Test void identicalOrthogonalOppositeAndScaledVectorsHaveKnownCosines() {
        assertThat(cosine(vector(3, 4), vector(30, 40))).isCloseTo(1, within(1e-15));
        assertThat(cosine(vector(1, 0), vector(0, 1))).isZero();
        assertThat(cosine(vector(1, 0), vector(-1, 0))).isEqualTo(-1);
        assertThat(cosine(vector(1, 0), vector(3, 4))).isCloseTo(.6, within(1e-15));
    }
    @Test void hugeAndSubnormalFiniteCoordinatesDoNotOverflowOrUnderflow() {
        assertThat(cosine(vector(Double.MAX_VALUE, Double.MAX_VALUE), vector(1, 1))).isCloseTo(1, within(1e-15));
        assertThat(cosine(vector(Double.MIN_VALUE, 0), vector(1, 0))).isEqualTo(1);
        assertThat(cosine(vector(1e-300, 1e-300), vector(1e300, 1e300))).isCloseTo(1, within(1e-15));
    }
    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteCoordinatesAreRejected(double value) { invalid(() -> cosine(vector(value), vector(1))); }
    @Test void invalidModelsDimensionsAndZeroNormNeverBecomeScores() {
        invalid(() -> cosine(null, vector(1)));
        invalid(() -> cosine(new Vector(null, List.of(1.0)), vector(1)));
        invalid(() -> cosine(new Vector(" ", List.of(1.0)), new Vector(" ", List.of(1.0))));
        invalid(() -> cosine(new Vector("other", List.of(1.0)), vector(1)));
        invalid(() -> cosine(vector(1), vector(1, 0)));
        invalid(() -> cosine(vector(), vector()));
        invalid(() -> cosine(vector(0, -0.0), vector(1, 0)));
        var tooWide = new Vector("synthetic:1", java.util.Collections.nCopies(2049, 1.0));
        invalid(() -> cosine(tooWide, tooWide));
    }
    @Test void rankingThresholdIsInclusiveStableAndDoesNotRewriteInputs() {
        var candidates = new ArrayList<>(List.of(new Entry(9, vector(0, 1)), new Entry(5, vector(3, 4)),
                new Entry(3, vector(3, 4)), new Entry(8, vector(-1, 0))));
        var before = List.copyOf(candidates);
        var ranked = rank(vector(1, 0), candidates, .6, 24);
        assertThat(ranked).extracting(Similarity::chunkId).containsExactly(3L, 5L);
        assertThat(candidates).isEqualTo(before);
        assertThatThrownBy(ranked::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(rank(vector(1, 0), candidates, .7, 24)).isEmpty();
        assertThat(rank(vector(1, 0), List.of(), 0, 1)).isEmpty();
    }
    @Test void invalidTailIsNotHiddenByTopKOrLowSimilarity() {
        invalid(() -> rank(vector(1), List.of(new Entry(1, vector(1)), new Entry(2, vector(0))), 1, 1));
        invalid(() -> rank(vector(1), List.of(new Entry(1, vector(1)), new Entry(1, vector(-1))), 1, 1));
        invalid(() -> rank(vector(1), Arrays.asList(new Entry(1, vector(1)), null), 1, 1));
        invalid(() -> rank(vector(1), List.of(new Entry(0, vector(1))), 0, 1));
        invalid(() -> rank(vector(1), List.of(new Entry(1, new Vector("other", List.of(-1.0)))), 1, 1));
    }
    @ParameterizedTest @ValueSource(doubles = {-1.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidThresholdIsRejected(double value) { invalid(() -> rank(vector(1), List.of(), value, 1)); }
    @ParameterizedTest @ValueSource(ints = {0, -1, 25, Integer.MAX_VALUE})
    void outputLimitIsBoundedForBothAlgorithms(int limit) {
        invalid(() -> rank(vector(1), List.of(), 0, limit));
        invalid(() -> fuse(List.of(), List.of(), 60, limit));
    }
    @Test void exactRankingAccepts512AndRefusesPartialSearchAt513() {
        var input = IntStream.rangeClosed(1, 512).mapToObj(id -> new Entry(id, vector(1))).toList();
        assertThat(rank(vector(1), input, 1, 24)).hasSize(24).first().extracting(Similarity::chunkId).isEqualTo(1L);
        var overflow = new ArrayList<>(input); overflow.add(new Entry(513, vector(1)));
        invalid(() -> rank(vector(1), overflow, 1, 24));
        invalid(() -> rank(vector(1), null, 0, 1));
    }
    @Test void fusionUsesOneBasedRanksAndPromotesCrossRouteAgreement() {
        var result = fuse(List.of(10L, 20L), List.of(30L, 20L), 60, 24);
        assertThat(result).extracting(Fused::chunkId).containsExactly(20L, 10L, 30L);
        assertThat(result.get(0).score()).isCloseTo(2.0 / 62, within(1e-15));
        assertThat(result.get(0).keywordRank()).isEqualTo(2);
        assertThat(result.get(0).vectorRank()).isEqualTo(2);
        assertThat(result.get(1).vectorRank()).isNull();
    }
    @Test void oneOrBothEmptyRoutesAreLegalAndReturnedSnapshotsAreImmutable() {
        assertThat(fuse(List.of(), List.of(), 60, 1)).isEmpty();
        var input = new ArrayList<>(List.of(9L, 8L));
        var result = fuse(List.of(), input, 60, 1); input.clear();
        assertThat(result).extracting(Fused::chunkId).containsExactly(9L);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(fuse(List.of(9L, 8L), List.of(), 60, 24)).extracting(Fused::chunkId).containsExactly(9L, 8L);
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 1001, Integer.MAX_VALUE})
    void smoothingMustBeExplicitAndBounded(int value) { invalid(() -> fuse(List.of(), List.of(), value, 1)); }
    @Test void invalidOrDuplicateIdsCannotInflateRanksOrHideAfterTruncation() {
        invalid(() -> fuse(null, List.of(), 60, 1));
        invalid(() -> fuse(List.of(1L, 1L), List.of(), 60, 1));
        invalid(() -> fuse(List.of(), List.of(2L, 2L), 60, 1));
        invalid(() -> fuse(Arrays.asList(1L, null), List.of(), 60, 1));
        invalid(() -> fuse(List.of(), List.of(-1L), 60, 1));
        invalid(() -> fuse(IntStream.rangeClosed(1, 25).mapToObj(Long::valueOf).toList(), List.of(), 60, 1));
    }
    @Test void independentParallelCallsHaveNoSharedRankingState() {
        var result = IntStream.range(0, 100).parallel().mapToObj(i -> fuse(List.of(10L, 20L), List.of(30L, 20L), 60, 3)).toList();
        assertThat(result).allSatisfy(rows -> assertThat(rows).extracting(Fused::chunkId).containsExactly(20L, 10L, 30L));
    }
}
