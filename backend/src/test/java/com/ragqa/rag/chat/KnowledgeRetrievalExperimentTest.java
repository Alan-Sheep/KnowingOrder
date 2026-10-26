package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources.KnowledgeSpace;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.KnowledgeEvidenceFusion.Origin;
import com.ragqa.rag.retrieval.KnowledgeRetrievalExperiment;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.rag.retrieval.QueryRewriter;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实改写模板/严格契约/融合，身份、检索和供应商传输为替身；不连接数据库或外部模型。 */
@Timeout(10)
class KnowledgeRetrievalExperimentTest {
    private static final String QUESTION = "夜里多干活的伙食津贴一天最多能领几块钱？";
    private static final String ALTERNATIVE = "加班餐补每人每天最多能领取多少？";
    private static final Instant RECEIVED = Instant.parse("2026-10-19T00:00:00Z");
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final ResponsesClient client = mock(ResponsesClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model = new ChatModel(client, new PromptRegistry(), json);
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong permissionVersion = new AtomicLong(1);
    private final Map<Long, Evidence> current = new HashMap<>();
    private List<Evidence> original = List.of(evidence(41));
    private List<Evidence> supplemental = List.of(evidence(42));
    private Consumer<Step> hook = step -> { };
    private boolean memberRevoked;
    private boolean spaceRevoked;
    private final AtomicBoolean evidenceRead = new AtomicBoolean();
    private String output;
    private final BusinessException denied = new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问");

    enum Step { ORIGINAL, MODEL, SUPPLEMENTAL, EVIDENCE, FINAL_AUTH }

    @BeforeEach
    void prepareSyntheticFixture() {
        output = output(ALTERNATIVE);
        current.put(41L, evidence(41));
        current.put(42L, evidence(42));
        when(members.requireCurrent(member)).thenAnswer(call -> {
            if (memberRevoked) throw denied;
            return member;
        });
        when(resources.requireSpace(member, 401L)).thenAnswer(call -> {
            if (evidenceRead.get()) hook.accept(Step.FINAL_AUTH);
            if (spaceRevoked) throw denied;
            return new KnowledgeSpace(401L, "虚构餐补空间", permissionVersion.get());
        });
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenAnswer(call -> {
            boolean baseline = QUESTION.equals(call.getArgument(2));
            if (!baseline) assertThat(call.<String>getArgument(2)).isEqualTo(ALTERNATIVE);
            hook.accept(baseline ? Step.ORIGINAL : Step.SUPPLEMENTAL);
            return baseline ? original : supplemental;
        });
        when(retriever.requireEvidence(eq(member), eq(401L), anyLong())).thenAnswer(call -> {
            evidenceRead.set(true);
            hook.accept(Step.EVIDENCE);
            Evidence result = current.get(call.<Long>getArgument(2));
            if (result == null) throw denied;
            return result;
        });
        when(client.generate(anyString(), anyString(), eq(1000), any())).thenAnswer(call -> {
            hook.accept(Step.MODEL);
            return output;
        });
    }

    @Test
    void combinesRealComponentsWithOriginalPriorityOneModelCallAndFinalSnapshotChecks() {
        supplemental = List.of(evidence(41), evidence(42));
        TaskDeadline parent = parent();
        var report = experiment("1:401").run(member, 401L, QUESTION, parent);
        assertThat(report.rewrite().status()).isEqualTo(QueryRewriter.Status.CANDIDATE);
        assertThat(report.preview().baseline()).containsExactly(evidence(41));
        assertThat(report.preview().candidates()).extracting(candidate -> candidate.evidence().chunkId())
                .containsExactly(41L, 42L);
        assertThat(report.preview().candidates()).extracting(candidate -> candidate.origin())
                .containsExactly(Origin.BOTH, Origin.SUPPLEMENTAL);
        var order = inOrder(retriever, client);
        order.verify(retriever).retrieve(member, 401L, QUESTION, parent);
        order.verify(client).generate(anyString(), anyString(), eq(1000), any());
        order.verify(retriever).retrieve(member, 401L, ALTERNATIVE, parent);
        order.verify(retriever).requireEvidence(member, 401L, 41L);
        order.verify(retriever).requireEvidence(member, 401L, 42L);
        order.verifyNoMoreInteractions();
        var payload = ArgumentCaptor.forClass(String.class);
        verify(client).generate(anyString(), payload.capture(), eq(1000), any());
        assertThat(payload.getValue()).contains(QUESTION).doesNotContain("虚构资料", "enterpriseId", "memberId", "spaceId");
        assertThat(report.rewrite().message()).contains("不代表语义等价");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_PERMITTED", "UNCHANGED", "BAD_JSON", "SUPPLIER_BUSY", "SUPPLIER_AUTH", "STAGE_TIMEOUT"})
    void absentCandidateKeepsBaselineWithoutSupplementOrRetry(String reason) {
        String allowed = reason.equals("NOT_PERMITTED") ? "" : "1:401";
        if (reason.equals("UNCHANGED")) output = output(QUESTION);
        if (reason.equals("BAD_JSON")) output = "{} {}";
        hook = step -> {
            if (step != Step.MODEL) return;
            if (reason.startsWith("SUPPLIER")) throw new BusinessException(
                    reason.equals("SUPPLIER_AUTH") ? ResultCode.UNAUTHORIZED : ResultCode.SERVICE_BUSY,
                    "fake-private-provider-body");
            if (reason.equals("STAGE_TIMEOUT")) ticks.addAndGet(Duration.ofSeconds(6).toNanos());
        };
        var report = experiment(allowed).run(member, 401L, QUESTION, parent());
        assertThat(report.rewrite().status().name()).isEqualTo(
                List.of("NOT_PERMITTED", "UNCHANGED").contains(reason) ? reason : "UNAVAILABLE");
        assertThat(report.rewrite().alternativeQuery()).isEmpty();
        assertThat(report.rewrite().message()).doesNotContain("fake-private-provider-body");
        assertThat(report.preview().baseline()).containsExactly(evidence(41));
        assertThat(report.preview().candidates()).extracting(candidate -> candidate.origin()).containsExactly(Origin.ORIGINAL);
        verify(retriever, times(1)).retrieve(eq(member), eq(401L), anyString(), any());
        verify(retriever).requireEvidence(member, 401L, 41L);
        verify(client, times(reason.equals("NOT_PERMITTED") ? 0 : 1)).generate(anyString(), anyString(), anyInt(), any());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true"})
    void emptyResultsRemainExperimentalWithoutUpgradingAnAnswer(boolean emptyOriginal, boolean emptySupplement) {
        if (emptyOriginal) original = List.of();
        if (emptySupplement) supplemental = List.of();
        var report = experiment("1:401").run(member, 401L, QUESTION, parent());
        assertThat(report.preview().baseline()).isEqualTo(original);
        assertThat(report.preview().candidates()).hasSize((emptyOriginal ? 0 : 1) + (emptySupplement ? 0 : 1));
        assertThat(report.rewrite().status()).isEqualTo(QueryRewriter.Status.CANDIDATE);
        verify(retriever, times(2)).retrieve(eq(member), eq(401L), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"ORIGINAL", "MODEL", "SUPPLEMENTAL", "EVIDENCE"})
    void revokedMemberRejectsInsteadOfReturningBaselineOrPreview(Step where) {
        hook = step -> { if (step == where) memberRevoked = true; };
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        if (where == Step.ORIGINAL) verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(Step.class)
    void revokedSpaceRejectsAtEverySensitiveStageIncludingFinalCheck(Step where) {
        hook = step -> { if (step == where) spaceRevoked = true; };
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        if (where == Step.ORIGINAL) verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(Step.class)
    void permissionVersionChangeRejectsEvenIfMemberStillCanRead(Step where) {
        hook = step -> { if (step == where) permissionVersion.incrementAndGet(); };
        assertConflict(() -> experiment("1:401").run(member, 401L, QUESTION, parent()));
        if (where == Step.ORIGINAL) verifyNoInteractions(client);
    }

    @Test
    void modelFailureCannotHideRevocation() {
        hook = step -> {
            if (step == Step.MODEL) {
                memberRevoked = true;
                throw new BusinessException(ResultCode.SERVICE_BUSY);
            }
        };
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        verify(retriever, times(1)).retrieve(eq(member), eq(401L), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"ORIGINAL", "SUPPLEMENTAL", "EVIDENCE", "FINAL_AUTH"})
    void storageFailuresAreNotConvertedIntoNoEvidenceOrModelDowngrade(Step where) {
        var failure = new IllegalStateException("存储替身故障");
        hook = step -> { if (step == where) throw failure; };
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(failure);
        if (where == Step.ORIGINAL) verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(value = ResultCode.class, names = {"UNAUTHORIZED", "NOT_FOUND", "CONFLICT", "SYSTEM_ERROR"})
    void initialMemberFailureDoesNotStartRetrievalOrModel(ResultCode code) {
        var failure = new BusinessException(code);
        when(members.requireCurrent(member)).thenThrow(failure);
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(failure);
        verifyNoInteractions(resources, retriever, client);
    }

    @Test
    void initialSpaceDenialDoesNotStartRetrievalOrModel() {
        spaceRevoked = true;
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        verifyNoInteractions(retriever, client);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidSpaces")
    void inconsistentSpaceSnapshotCannotStartRetrieval(KnowledgeSpace space) {
        when(resources.requireSpace(member, 401L)).thenReturn(space);
        assertConflict(() -> experiment("1:401").run(member, 401L, QUESTION, parent()));
        verifyNoInteractions(retriever, client);
    }

    static Stream<KnowledgeSpace> invalidSpaces() {
        return Stream.of(new KnowledgeSpace(402L, "其他空间", 1), new KnowledgeSpace(401L, "虚构空间", 0));
    }

    @ParameterizedTest
    @MethodSource("changedSnapshots")
    void completeEvidenceSnapshotIsRechecked(Evidence changed) {
        current.put(41L, changed);
        assertConflict(() -> experiment("1:401").run(member, 401L, QUESTION, parent()));
    }

    static Stream<Evidence> changedSnapshots() {
        Evidence e = evidence(41);
        return Stream.of(
                new Evidence(99L, e.documentId(), e.versionId(), 1, e.title(), 0, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, 999L, e.versionId(), 1, e.title(), 0, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), 999L, 1, e.title(), 0, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 2, e.title(), 0, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, "虚构新标题", 0, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 1, e.content(), 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 0, "替换资料41", 0, e.endOffset(), 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 0, e.content(), 1, e.endOffset() + 1, 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 0, e.content() + "字", 0, e.endOffset() + 1, 1, 1),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 0, e.content(), 0, e.endOffset(), 2, 2),
                new Evidence(41L, e.documentId(), e.versionId(), 1, e.title(), 0, e.content(), 0, e.endOffset(), 1, 2));
    }

    @Test
    void withdrawnEvidenceIsNotReturnedAsAnEmptySuccessfulPreview() {
        current.remove(41L);
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
    }

    @Test
    void capacityExcludedEvidenceStillMustBeCurrentAndAuthorized() {
        original = Stream.iterate(41L, id -> id + 1).limit(6).map(KnowledgeRetrievalExperimentTest::evidence).toList();
        original.forEach(e -> current.put(e.chunkId(), e));
        supplemental = List.of(evidence(47));
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        verify(retriever).requireEvidence(member, 401L, 47L);
    }

    @Test
    void overlapExcludedEvidenceStillMustBeCurrentAndAuthorized() {
        Evidence a = new Evidence(41L, 141L, 241L, 1, "虚构制度", 0, "甲".repeat(200), 0, 200, 1, 1);
        Evidence b = new Evidence(42L, 141L, 241L, 1, "虚构制度", 1, "甲".repeat(200), 50, 250, 1, 1);
        original = List.of(a);
        supplemental = List.of(b);
        current.put(41L, a);
        current.remove(42L);
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent())).isSameAs(denied);
        verify(retriever).requireEvidence(member, 401L, 42L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidRetrievalListNeverProducesAReport(boolean originalInvalid) {
        if (originalInvalid) original = null;
        else supplemental = null;
        assertCode(() -> experiment("1:401").run(member, 401L, QUESTION, parent()), 400);
        if (originalInvalid) verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retrievalOverCapacityIsRejectedInsteadOfSilentlyTruncated(boolean originalInvalid) {
        List<Evidence> tooMany = Stream.iterate(41L, id -> id + 1).limit(7)
                .map(KnowledgeRetrievalExperimentTest::evidence).toList();
        if (originalInvalid) original = tooMany;
        else supplemental = tooMany;
        assertCode(() -> experiment("1:401").run(member, 401L, QUESTION, parent()), 400);
        if (originalInvalid) verifyNoInteractions(client);
    }

    @Test
    void malformedBaselineIsRejectedBeforeModelCall() {
        original = new ArrayList<>();
        original.add(null);
        assertConflict(() -> experiment("1:401").run(member, 401L, QUESTION, parent()));
        verifyNoInteractions(client);
    }

    @Test
    void conflictingSnapshotAcrossRoutesRejectsBeforeAnyFinalEvidenceDelivery() {
        Evidence e = evidence(41);
        supplemental = List.of(new Evidence(41L, e.documentId(), e.versionId(), 1, "冲突标题", 0,
                e.content(), 0, e.endOffset(), 1, 1));
        assertConflict(() -> experiment("1:401").run(member, 401L, QUESTION, parent()));
        verify(retriever, never()).requireEvidence(any(), anyLong(), anyLong());
    }

    @Test
    void snapshotsStayStableWhenRetrieverOwnedListsChangeInLaterStages() {
        original = new ArrayList<>(original);
        supplemental = new ArrayList<>(supplemental);
        hook = step -> {
            if (step == Step.MODEL) original.clear();
            if (step == Step.EVIDENCE) supplemental.clear();
        };
        var report = experiment("1:401").run(member, 401L, QUESTION, parent());
        assertThat(report.preview().baseline()).containsExactly(evidence(41));
        assertThat(report.preview().candidates()).extracting(candidate -> candidate.evidence().chunkId())
                .containsExactly(41L, 42L);
        verify(retriever).requireEvidence(member, 401L, 42L);
    }

    @ParameterizedTest
    @EnumSource(Step.class)
    void parentExpiryStopsEachStageWithoutRenewal(Step where) {
        hook = step -> { if (step == where) ticks.addAndGet(Duration.ofSeconds(20).toNanos()); };
        TaskDeadline parent = parent();
        assertCode(() -> experiment("1:401").run(member, 401L, QUESTION, parent), 504);
        assertThat(parent.remaining()).isZero();
        if (where == Step.ORIGINAL) verifyNoInteractions(client);
        if (where == Step.MODEL) verify(retriever, times(1)).retrieve(eq(member), eq(401L), anyString(), any());
    }

    @Test
    void alreadyExpiredParentDoesNotStartAuthorizationOrRetrieval() {
        TaskDeadline parent = parent();
        ticks.addAndGet(Duration.ofSeconds(20).toNanos());
        assertCode(() -> experiment("1:401").run(member, 401L, QUESTION, parent), 504);
        verifyNoInteractions(members, resources, retriever, client);
    }

    @ParameterizedTest
    @EnumSource(Step.class)
    void interruptionRejectsReportAndPreservesCancellationSignal(Step where) {
        try {
            hook = step -> { if (step == where) Thread.currentThread().interrupt(); };
            assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent()))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("取消");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            if (where == Step.ORIGINAL) verifyNoInteractions(client);
        } finally { Thread.interrupted(); }
    }

    @Test
    void alreadyInterruptedParentDoesNotStartAnyWork() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, parent()))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("取消");
            verifyNoInteractions(members, resources, retriever, client);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void modelAndBothRetrievalsConsumeTheOriginalBudget() {
        hook = step -> {
            if (step == Step.ORIGINAL) ticks.addAndGet(Duration.ofSeconds(7).toNanos());
            if (step == Step.MODEL) ticks.addAndGet(Duration.ofSeconds(2).toNanos());
            if (step == Step.SUPPLEMENTAL) ticks.addAndGet(Duration.ofSeconds(3).toNanos());
        };
        TaskDeadline parent = parent();
        experiment("1:401").run(member, 401L, QUESTION, parent);
        verify(retriever, times(2)).retrieve(eq(member), eq(401L), anyString(), same(parent));
        var stage = ArgumentCaptor.forClass(TaskDeadline.class);
        verify(client).generate(anyString(), anyString(), eq(1000), stage.capture());
        assertThat(stage.getValue().receivedAt()).isEqualTo(RECEIVED);
        assertThat(stage.getValue().deadlineAt()).isEqualTo(RECEIVED.plusSeconds(12));
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(8));
    }

    @ParameterizedTest
    @MethodSource("invalidQuestions")
    void invalidInputNeverStartsSideEffects(String question) {
        assertCode(() -> experiment("1:401").run(member, 401L, question, parent()), 400);
        verifyNoInteractions(members, resources, retriever, client);
    }

    static Stream<String> invalidQuestions() {
        return Stream.of(null, "", " \t\n", "\u00a0", "\u200b", "\u0000", "\ud800", "\u2028", "字".repeat(1201));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidSpaceNeverStartsSideEffects(Long spaceId) {
        assertCode(() -> experiment("1:401").run(member, spaceId, QUESTION, parent()), 400);
        verifyNoInteractions(members, resources, retriever, client);
    }

    @Test
    void missingIdentityOrParentDoesNotCreateAnImplicitBudget() {
        assertCode(() -> experiment("1:401").run(null, 401L, QUESTION, parent()), 401);
        assertThatThrownBy(() -> experiment("1:401").run(member, 401L, QUESTION, null))
                .isInstanceOf(NullPointerException.class).hasMessage("任务时限不能为空");
        verifyNoInteractions(members, resources, retriever, client);
    }

    @Test
    void concurrentEnterprisesUseTheirOwnQueriesScopesAndSnapshots() throws Exception {
        var other = new EnterpriseMemberContext(2, 102, 2, null, "EMPLOYEE", 1, 1, 1);
        when(members.requireCurrent(other)).thenReturn(other);
        when(resources.requireSpace(other, 402L)).thenReturn(new KnowledgeSpace(402L, "虚构第二空间", 1));
        when(retriever.retrieve(eq(other), eq(402L), anyString(), any())).thenReturn(List.of(evidence(99)));
        when(retriever.requireEvidence(other, 402L, 99L)).thenReturn(evidence(99));
        CountDownLatch arrived = new CountDownLatch(2);
        when(client.generate(anyString(), anyString(), eq(1000), any())).thenAnswer(call -> {
            arrived.countDown();
            if (!arrived.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未会合");
            return output(call.<String>getArgument(1).contains(QUESTION) ? ALTERNATIVE : "入职工牌领取流程");
        });
        var runner = experiment("1:401,2:402");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> runner.run(member, 401L, QUESTION, TaskDeadline.start(Duration.ofSeconds(10))));
            var second = pool.submit(() -> runner.run(other, 402L, "新来公司的工牌怎么领？", TaskDeadline.start(Duration.ofSeconds(10))));
            assertThat(first.get(3, TimeUnit.SECONDS).preview().baseline()).containsExactly(evidence(41));
            var otherReport = second.get(3, TimeUnit.SECONDS);
            assertThat(otherReport.preview().baseline()).containsExactly(evidence(99));
            assertThat(otherReport.rewrite().alternativeQuery()).isEqualTo("入职工牌领取流程");
            verify(retriever).retrieve(eq(other), eq(402L), eq("入职工牌领取流程"), any());
            verify(retriever, never()).requireEvidence(eq(other), eq(402L), eq(41L));
            verify(retriever, never()).requireEvidence(eq(member), eq(401L), eq(99L));
            verify(client, times(2)).generate(anyString(), anyString(), eq(1000), any());
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private KnowledgeRetrievalExperiment experiment(String allowed) {
        return new KnowledgeRetrievalExperiment(members, resources, retriever,
                new QueryRewriter(members, resources, model, allowed));
    }

    private TaskDeadline parent() {
        return TaskDeadline.start(Duration.ofSeconds(20), Clock.fixed(RECEIVED, ZoneOffset.UTC), ticks::get);
    }

    private String output(String alternative) {
        return json.createObjectNode().put("alternativeQuery", alternative).toString();
    }

    private static Evidence evidence(long id) {
        String content = "虚构资料" + id;
        return new Evidence(id, id + 100, id + 200, 1, "虚构餐补制度", 0, content, 0, content.length(), 1, 1);
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(409);
            assertThat(error.getMessage()).doesNotContain(QUESTION, ALTERNATIVE, "虚构资料");
            assertThat(error.getCause()).isNull();
        });
    }

    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
