package com.ragqa.rag.retrieval;

import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.ragqa.rag.retrieval.KnowledgeEvidenceFusion.Origin.*;
import static com.ragqa.rag.retrieval.KnowledgeEvidenceFusion.fuse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 合成快照的纯函数边界，不访问数据库/HTTP/模型，不把融合预览当答案或授权证明。 */
class KnowledgeEvidenceFusionTest {
    @Test
    void acceptsBothListsEmpty() {
        var result = fuse(List.of(), List.of());
        assertThat(result.baseline()).isEmpty();
        assertThat(result.candidates()).isEmpty();
        counts(result, 0, 0, 0);
    }

    @Test
    void keepsOriginalOrderInsteadOfSortingById() {
        var original = List.of(evidence(42), evidence(41));
        var result = fuse(original, List.of());
        assertThat(result.baseline()).containsExactlyElementsOf(original);
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(42L, 41L);
        assertThat(result.candidates()).extracting(KnowledgeEvidenceFusion.Candidate::origin)
                .containsExactly(ORIGINAL, ORIGINAL);
    }

    @Test
    void supplementalOnlyDoesNotInventOriginalEvidence() {
        var result = fuse(List.of(), List.of(evidence(43), evidence(41)));
        assertThat(result.baseline()).isEmpty();
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(43L, 41L);
        assertThat(result.candidates()).extracting(KnowledgeEvidenceFusion.Candidate::origin)
                .containsExactly(SUPPLEMENTAL, SUPPLEMENTAL);
        counts(result, 0, 0, 0);
    }

    @Test
    void mergesTheDocumentedExampleWithTraceableOrigins() {
        var original = List.of(evidence(41), evidence(42));
        var result = fuse(original, List.of(evidence(42), evidence(43)));
        assertThat(result.baseline()).containsExactlyElementsOf(original);
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(41L, 42L, 43L);
        assertThat(result.candidates()).extracting(KnowledgeEvidenceFusion.Candidate::origin)
                .containsExactly(ORIGINAL, BOTH, SUPPLEMENTAL);
        assertThat(result.candidates().get(1).evidence()).isSameAs(original.get(1));
        counts(result, 1, 0, 0);
    }

    @Test
    void preservesRepeatedOriginalItemsInTheBaselineButDeduplicatesPreview() {
        var original = List.of(evidence(42), evidence(42), evidence(41));
        var result = fuse(original, List.of(evidence(41), evidence(41), evidence(43), evidence(43)));
        assertThat(result.baseline()).containsExactlyElementsOf(original);
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(42L, 41L, 43L);
        assertThat(result.candidates()).extracting(KnowledgeEvidenceFusion.Candidate::origin)
                .containsExactly(ORIGINAL, BOTH, SUPPLEMENTAL);
        counts(result, 4, 0, 0);
    }

    @Test
    void neverEvictsOriginalsAndStillMarksBothAfterCapacityIsReached() {
        var original = LongStream.rangeClosed(41, 46).mapToObj(KnowledgeEvidenceFusionTest::evidence).toList();
        var result = fuse(original, List.of(evidence(47), evidence(46), evidence(48)));
        assertThat(result.baseline()).containsExactlyElementsOf(original);
        assertThat(result.candidates()).extracting(value -> value.evidence()).containsExactlyElementsOf(original);
        assertThat(result.candidates().get(5).origin()).isEqualTo(BOTH);
        counts(result, 1, 0, 2);
    }

    @Test
    void fillsOnlyRemainingSpaceInSupplementalOrder() {
        var original = LongStream.rangeClosed(41, 45).mapToObj(KnowledgeEvidenceFusionTest::evidence).toList();
        var result = fuse(original, List.of(evidence(49), evidence(47), evidence(48)));
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId())
                .containsExactly(41L, 42L, 43L, 44L, 45L, 49L);
        counts(result, 0, 0, 2);
    }

    @Test
    void doesNotRefilterOverlappingOriginals() {
        var original = List.of(fragment(41, 1, 11, 0, 200), fragment(42, 1, 11, 50, 200));
        assertThat(fuse(original, List.of()).candidates()).extracting(value -> value.evidence())
                .containsExactlyElementsOf(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 79, 80, 81, 199, 200, 201})
    void appliesTheStrictOneHundredTwentyUtf16OverlapBoundary(int start) {
        var result = fuse(List.of(fragment(41, 1, 11, 0, 200)), List.of(fragment(42, 1, 11, start, 200)));
        boolean excluded = start < 80;
        assertThat(result.candidates()).hasSize(excluded ? 1 : 2);
        counts(result, 0, excluded ? 1 : 0, 0);
    }

    @Test
    void filtersAgainstAcceptedSupplementalEvidenceAndStillFillsLaterSpace() {
        var result = fuse(List.of(), List.of(fragment(41, 1, 11, 0, 200),
                fragment(42, 1, 11, 50, 200), fragment(43, 1, 11, 200, 200)));
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(41L, 43L);
        counts(result, 0, 1, 0);
    }

    @Test
    void countsRepeatedExcludedItemsOncePerDispositionWithoutReconsideringThem() {
        var first = fragment(41, 1, 11, 0, 200);
        var overlapping = fragment(42, 1, 11, 50, 200);
        var result = fuse(List.of(first, first), List.of(first, overlapping, overlapping,
                evidence(43), evidence(43), evidence(44)));
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(41L, 43L, 44L);
        counts(result, 4, 1, 0);
        assertThat(result.candidates().size() + result.duplicateExcluded() + result.overlapExcluded()
                + result.capacityExcluded()).isEqualTo(8);
    }

    @Test
    void classifiesOverlapBeforeCapacityAndDuplicateBeforeOverlap() {
        var original = new ArrayList<>(LongStream.rangeClosed(41, 45)
                .mapToObj(KnowledgeEvidenceFusionTest::evidence).toList());
        original.add(fragment(46, 1, 11, 0, 200));
        var result = fuse(original, List.of(fragment(47, 1, 11, 50, 200),
                fragment(46, 1, 11, 0, 200), evidence(48), evidence(48)));
        assertThat(result.candidates()).hasSize(6);
        counts(result, 2, 1, 1);
    }

    @Test
    void doesNotCollapseDifferentVersionsOrDocumentsJustBecauseContentMatches() {
        var original = fragment(41, 1, 11, 0, 200);
        var result = fuse(List.of(original), List.of(fragment(42, 1, 12, 0, 200),
                fragment(43, 2, 13, 0, 200)));
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(41L, 42L, 43L);
        counts(result, 0, 0, 0);
        // 纯函数不选“现行版本”；调用方必须另行验证当前发布指针。
    }

    @Test
    void handlesUtf16OffsetsAndLargeValidNumericValues() {
        var original = fragment(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE - 200, 200);
        var next = fragment(42, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE - 80, 80);
        assertThat(fuse(List.of(original), List.of(next)).candidates()).hasSize(2);
        var emoji = new Evidence(43L, 1L, 11L, 1, "合成资料", 0, "😀".repeat(500), 0, 1000, 1, 1);
        assertThat(fuse(List.of(emoji), List.of()).baseline()).containsExactly(emoji);
    }

    @ParameterizedTest(name = "{0}={1}，原始路={2}")
    @MethodSource("invalidEvidence")
    void rejectsMalformedSnapshotsOnEitherRoute(String field, Object value, boolean originalRoute) {
        Evidence invalid = field.equals("item") ? null : change(evidence(41), field, value);
        var bad = Arrays.asList(invalid);
        assertRejected(originalRoute ? bad : List.of(), originalRoute ? List.of() : bad, 409);
    }

    static Stream<Arguments> invalidEvidence() {
        return Stream.of(Arguments.of("item", null), Arguments.of("chunkId", null), Arguments.of("chunkId", 0L),
                Arguments.of("documentId", null), Arguments.of("documentId", -1L),
                Arguments.of("versionId", null), Arguments.of("versionId", 0L), Arguments.of("versionNo", 0),
                Arguments.of("chunkIndex", -1), Arguments.of("title", null), Arguments.of("title", " "),
                Arguments.of("content", null), Arguments.of("content", ""), Arguments.of("content", " "),
                Arguments.of("content", "知".repeat(1001)), Arguments.of("startOffset", -1),
                Arguments.of("endOffset", 0), Arguments.of("endOffset", 201),
                Arguments.of("startLine", 0), Arguments.of("endLine", 0))
                .flatMap(args -> Stream.of(true, false).map(route -> Arguments.of(args.get()[0], args.get()[1], route)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsNullAndOversizedListsBeforeSilentlyTruncating(boolean originalRoute) {
        var tooMany = LongStream.rangeClosed(41, 47).mapToObj(KnowledgeEvidenceFusionTest::evidence).toList();
        assertRejected(originalRoute ? null : List.of(), originalRoute ? List.of() : null, 400);
        assertRejected(originalRoute ? tooMany : List.of(), originalRoute ? List.of() : tooMany, 400);
        var duplicates = java.util.Collections.nCopies(7, evidence(41));
        assertRejected(originalRoute ? duplicates : List.of(), originalRoute ? List.of() : duplicates, 400);
    }

    @ParameterizedTest(name = "快照变化：{0}")
    @MethodSource("conflictingEvidence")
    void rejectsEveryChangedSnapshotFieldEvenAtTheEndOfAnAlreadyFullPreview(String field, Object value) {
        var original = LongStream.rangeClosed(41, 46).mapToObj(KnowledgeEvidenceFusionTest::evidence).toList();
        Evidence changed = change(evidence(46), field, value);
        assertRejected(original, List.of(evidence(47), changed), 409);
        assertRejected(List.of(), List.of(evidence(46), changed), 409);
        assertRejected(List.of(evidence(46), changed), List.of(), 409);
    }

    static Stream<Arguments> conflictingEvidence() {
        return Stream.of(Arguments.of("documentId", 999L), Arguments.of("versionId", 999L),
                Arguments.of("versionNo", 2), Arguments.of("title", "另一份合成资料"), Arguments.of("chunkIndex", 2),
                Arguments.of("content", "异".repeat(200)), Arguments.of("startLine", 2), Arguments.of("endLine", 2));
    }

    @Test
    void rejectsChangedButIndividuallyValidCoordinateSnapshots() {
        var shifted = fragment(41, 141, 241, 1, 200);
        assertRejected(List.of(evidence(41)), List.of(shifted), 409);
        var original = new Evidence(41L, 141L, 241L, 1, "合成资料", 0, "知".repeat(200), 0, 200, 1, 3);
        assertRejected(List.of(original), List.of(change(original, "startLine", 2)), 409);
    }

    @Test
    void checksMalformedTailEvenWhenItWouldNotFit() {
        var original = LongStream.rangeClosed(41, 46).mapToObj(KnowledgeEvidenceFusionTest::evidence).toList();
        assertRejected(original, Arrays.asList(evidence(47), null), 409);
    }

    @Test
    void copiesInputsAndReturnsUnmodifiableCollectionsWithoutChangingCallerLists() {
        var original = new ArrayList<>(List.of(evidence(41)));
        var extra = new ArrayList<>(List.of(evidence(42)));
        var result = fuse(original, extra);
        assertThat(original).containsExactly(evidence(41));
        assertThat(extra).containsExactly(evidence(42));
        original.clear();
        extra.clear();
        assertThat(result.baseline()).containsExactly(evidence(41));
        assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(41L, 42L);
        assertThatThrownBy(() -> result.baseline().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        var candidates = new ArrayList<>(result.candidates());
        var copy = new KnowledgeEvidenceFusion.Preview(result.baseline(), candidates, 0, 0, 0);
        candidates.clear();
        assertThat(copy.candidates()).hasSize(2);
    }

    @Test
    void repeatedAndConcurrentCallsNeverShareSelectionState() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<KnowledgeEvidenceFusion.Preview>>();
            for (int i = 0; i < 40; i++) {
                long id = 1000L + i * 10;
                tasks.add(() -> fuse(List.of(evidence(id)), List.of(evidence(id + 1), evidence(id))));
            }
            var futures = pool.invokeAll(tasks, 5, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                long id = 1000L + i * 10;
                var result = futures.get(i).get(1, TimeUnit.SECONDS);
                assertThat(result.candidates()).extracting(value -> value.evidence().chunkId()).containsExactly(id, id + 1);
                assertThat(result.candidates()).extracting(KnowledgeEvidenceFusion.Candidate::origin)
                        .containsExactly(BOTH, SUPPLEMENTAL);
                counts(result, 1, 0, 0);
                assertThat(fuse(List.of(evidence(id)), List.of(evidence(id + 1), evidence(id)))).isEqualTo(result);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Evidence evidence(long id) { return fragment(id, id + 100, id + 200, 0, 200); }

    private static Evidence fragment(long id, long document, long version, int start, int length) {
        return new Evidence(id, document, version, 1, "合成资料", 0, "知".repeat(length), start, start + length, 1, 1);
    }

    private static Evidence change(Evidence source, String field, Object value) {
        return new Evidence(field.equals("chunkId") ? (Long) value : source.chunkId(),
                field.equals("documentId") ? (Long) value : source.documentId(),
                field.equals("versionId") ? (Long) value : source.versionId(),
                field.equals("versionNo") ? (int) value : source.versionNo(),
                field.equals("title") ? (String) value : source.title(),
                field.equals("chunkIndex") ? (int) value : source.chunkIndex(),
                field.equals("content") ? (String) value : source.content(),
                field.equals("startOffset") ? (int) value : source.startOffset(),
                field.equals("endOffset") ? (int) value : source.endOffset(),
                field.equals("startLine") ? (int) value : source.startLine(),
                field.equals("endLine") ? (int) value : source.endLine());
    }

    private static void counts(KnowledgeEvidenceFusion.Preview result, int duplicate, int overlap, int capacity) {
        assertThat(result.duplicateExcluded()).isEqualTo(duplicate);
        assertThat(result.overlapExcluded()).isEqualTo(overlap);
        assertThat(result.capacityExcluded()).isEqualTo(capacity);
    }

    private static void assertRejected(List<Evidence> original, List<Evidence> supplemental, int code) {
        assertThatThrownBy(() -> fuse(original, supplemental)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).isIn("融合实验要求两路证据列表各不超过6段",
                    "证据快照无效或不一致，请重新检索后再进行融合实验");
            assertThat(error.getCause()).isNull();
        });
    }
}
