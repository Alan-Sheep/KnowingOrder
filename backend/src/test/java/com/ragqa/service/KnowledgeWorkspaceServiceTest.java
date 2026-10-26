package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.service.KnowledgeWorkspaceService.*;
import com.ragqa.service.impl.*;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实SQL和事务使用隔离H2；模型回答替身，无生产库、外部API或模型费用。 */
class KnowledgeWorkspaceServiceTest {
    private EnterpriseDatabase db;
    private KnowledgeWorkspaceService service;
    private KnowledgeWorkspaceMapper mapper;
    private KnowledgeAnswerService answers;
    private EnterpriseMemberService members;
    private EnterpriseResourceService resources;
    private KnowledgeManagementMapper management;
    private final AuthenticatedUserPrincipal owner = new AuthenticatedUserPrincipal(1, 1);
    private final AuthenticatedUserPrincipal peer = new AuthenticatedUserPrincipal(2, 1);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,102,'READ')");
        db.factory.getConfiguration().addMapper(KnowledgeWorkspaceMapper.class);
        db.factory.getConfiguration().addMapper(KnowledgeManagementMapper.class);
        var template = new SqlSessionTemplate(db.factory);
        mapper = template.getMapper(KnowledgeWorkspaceMapper.class);
        management = template.getMapper(KnowledgeManagementMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(template.getMapper(SysUserMapper.class)),
                template.getMapper(EnterpriseDirectoryMapper.class));
        resources = new EnterpriseResourceServiceImpl(members, template.getMapper(EnterpriseResourceMapper.class));
        answers = mock(KnowledgeAnswerService.class);
        when(answers.answerWithin(any(), anyLong(), anyLong(), anyString(), any())).thenReturn(answer(List.of()));
        service = create(mapper);
    }
    private KnowledgeWorkspaceService create(KnowledgeWorkspaceMapper store) {
        return new KnowledgeWorkspaceService(store, management, members, resources, answers, json,
                new DataSourceTransactionManager(db.dataSource), mock(KnowledgeFollowUpService.class));
    }
    @AfterEach void close() throws Exception { service.close(); db.close(); }
    private static String id() { return UUID.randomUUID().toString(); }
    private Submit request() { return new Submit(id(), id(), "餐补多少，谁审批？"); }
    private Job submit(Submit request) { return service.submit(owner, 1, 401, request); }
    private Answer answer(List<Evidence> evidence) {
        return new Answer("LOCAL_EVIDENCE", evidence.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_ONLY", "仅原文",
                List.of(new Part(1, "问题", "NO_EVIDENCE", List.of(), List.of("资料不足"))), evidence, 1);
    }
    private void code(Runnable work, int code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
    private Job finish(Submit request) {
        submit(request); service.dispatch();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() ->
                "COMPLETED".equals(service.job(owner, 1, 401, request.requestId()).status()));
        return service.job(owner, 1, 401, request.requestId());
    }

    @Test void duplicateSubmissionReturnsOriginalWithoutExtendingDeadline() {
        Submit request = request(); Job first = submit(request); Job second = submit(request);
        assertThat(second).isEqualTo(first);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_job", Integer.class)).isEqualTo(1);
        code(() -> submit(new Submit(request.chatId(), request.requestId(), "不同问题")), 409);
        assertThat(second.deadlineMs() - second.createdMs()).isEqualTo(30_000);
    }
    @Test void simultaneousDuplicateRequestsCreateOneJobAndKeepOriginalDeadline() throws Exception {
        Submit request = request();
        CountDownLatch start = new CountDownLatch(1);
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> { start.await(); return submit(request); });
            var second = callers.submit(() -> { start.await(); return submit(request); });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_job", Integer.class)).isEqualTo(1);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_chat", Integer.class)).isEqualTo(1);
        } finally { callers.shutdownNow(); }
    }
    @Test void simultaneousRequestsCannotExceedMemberLimit() throws Exception {
        submit(request());
        CountDownLatch start = new CountDownLatch(1);
        var callers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Integer> work = () -> {
            start.await();
            try { submit(request()); return 0; }
            catch (BusinessException ex) { return ex.getCode(); }
        };
        try {
            var first = callers.submit(work); var second = callers.submit(work); start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 503);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_job", Integer.class)).isEqualTo(2);
        } finally { callers.shutdownNow(); }
    }
    @Test void foreignRequestIdCollisionCannotCreateOrExposeAnotherMembersChat() {
        Submit request = request(); submit(request);
        code(() -> service.submit(peer, 1, 401, new Submit(id(), request.requestId(), "另一个员工的问题")), 409);
        assertThat(service.chats(peer, 1, 401)).isEmpty();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_job", Integer.class)).isEqualTo(1);
    }
    @Test void anotherMemberOrEnterpriseCannotReadTheSameIdentifiers() {
        Submit request = request(); submit(request);
        code(() -> service.job(peer, 1, 401, request.requestId()), 404);
        code(() -> service.history(peer, 1, 401, request.chatId()), 404);
        code(() -> service.cancel(peer, 1, 401, request.requestId()), 404);
        code(() -> service.job(owner, 2, 403, request.requestId()), 404);
        assertThat(service.chats(peer, 1, 401)).isEmpty();
    }
    @Test void twoActiveRequestsAreAllowedThirdIsRejectedWithoutCreatingChat() {
        submit(request()); submit(request()); Submit third = request();
        code(() -> submit(third), 503);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_chat", Integer.class)).isEqualTo(2);
    }
    @Test void chatAndJobCreationRollBackTogether() {
        var failing = spy(mapper);
        doThrow(new IllegalStateException("模拟任务写入失败")).when(failing).createJob(anyString(), anyString(), anyLong(), anyString(), anyLong(), anyLong());
        service.close(); service = create(failing);
        assertThatThrownBy(() -> submit(request())).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_chat", Integer.class)).isZero();
    }
    @Test void dispatcherPersistsAnswerAndDuplicateDispatchDoesNotRepeatModel() {
        Submit request = request(); Job completed = finish(request);
        service.dispatch(); submit(request);
        assertThat(completed.answer()).isNotNull();
        assertThat(service.history(owner, 1, 401, request.chatId())).hasSize(1);
        verify(answers, times(1)).answerWithin(any(), anyLong(), anyLong(), anyString(), any());
    }
    @Test void restartRestoresPendingButNeverReplaysAlreadyRunningCall() {
        Submit request = request(); submit(request);
        db.jdbc.update("UPDATE rag_knowledge_job SET status='RUNNING' WHERE id=?", request.requestId());
        service.close(); service = create(mapper); service.dispatch();
        verifyNoInteractions(answers);
        db.jdbc.update("UPDATE rag_knowledge_job SET created_ms=1,deadline_ms=2 WHERE id=?", request.requestId());
        assertThat(service.job(owner, 1, 401, request.requestId()).status()).isEqualTo("TIMED_OUT");
        service.dispatch(); verifyNoInteractions(answers);
    }
    @Test void pendingJobCanFinishAfterServiceObjectRestart() {
        Submit request = request(); submit(request); service.close(); service = create(mapper);
        service.dispatch();
        await().atMost(Duration.ofSeconds(5)).until(() -> "COMPLETED".equals(service.job(owner, 1, 401, request.requestId()).status()));
    }
    @Test void expiryStopsFurtherCallsAndCannotBeExtendedByPolling() {
        Submit request = request(); submit(request);
        db.jdbc.update("UPDATE rag_knowledge_job SET created_ms=1,deadline_ms=2 WHERE id=?", request.requestId());
        assertThat(service.job(owner, 1, 401, request.requestId()).status()).isEqualTo("TIMED_OUT");
        assertThat(submit(request).deadlineMs()).isEqualTo(2); service.dispatch(); verifyNoInteractions(answers);
    }
    @Test void cancellingPendingPreventsAnyModelCall() {
        Submit request = request(); submit(request);
        assertThat(service.cancel(owner, 1, 401, request.requestId()).status()).isEqualTo("CANCELLED");
        service.dispatch(); verifyNoInteractions(answers);
    }
    @Test void lateModelResultCannotOverwriteCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finishAttempted = new CountDownLatch(1);
        var observed = spy(mapper);
        doAnswer(call -> {
            try { return mapper.finish(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)); }
            finally { finishAttempted.countDown(); }
        }).when(observed).finish(anyString(), anyString(), any(), any(), anyLong());
        service.close(); service = create(observed);
        when(answers.answerWithin(any(), anyLong(), anyLong(), anyString(), any())).thenAnswer(call -> {
            entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return answer(List.of());
        });
        Submit request = request(); submit(request); service.dispatch();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        service.cancel(owner, 1, 401, request.requestId()); release.countDown();
        assertThat(finishAttempted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(db.jdbc.queryForObject("SELECT status FROM rag_knowledge_job WHERE id=?", String.class, request.requestId()))
                .isEqualTo("CANCELLED");
        assertThat(db.jdbc.queryForObject("SELECT answer_json FROM rag_knowledge_job WHERE id=?", String.class, request.requestId())).isNull();
    }
    @Test void revokedSessionOrSpaceCannotReadHistoryOrExecuteAcceptedJob() {
        Submit request = request(); submit(request);
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=101 AND knowledge_space_id=401");
        code(() -> service.history(owner, 1, 401, request.chatId()), 404);
        service.dispatch();
        await().atMost(Duration.ofSeconds(3)).until(() -> "FAILED".equals(db.jdbc.queryForObject(
                "SELECT status FROM rag_knowledge_job WHERE id=?", String.class, request.requestId())));
        verifyNoInteractions(answers);
    }
    @Test void updatedEvidenceHidesHistoricAnswerInsteadOfLeakingOldBody() {
        Evidence old = new Evidence(1L, 2L, 3L, 1, "旧制度", 0, "原文", 0, 2, 1, 1);
        when(answers.answerWithin(any(), anyLong(), anyLong(), anyString(), any())).thenReturn(answer(List.of(old)));
        when(answers.evidence(owner, 1L, 401L, 1L)).thenReturn(old);
        Submit request = request(); finish(request);
        when(answers.evidence(owner, 1L, 401L, 1L)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
        Job job = service.job(owner, 1, 401, request.requestId());
        assertThat(job.answer()).isNull(); assertThat(job.message()).contains("暂不展示");
    }
    @Test void userInputIsBoundAsDataNotSql() {
        Submit request = new Submit(id(), id(), "' OR 1=1 -- <script>alert(1)</script>");
        assertThat(submit(request).question()).isEqualTo(request.question());
        assertThat(service.chats(peer, 1, 401)).isEmpty();
    }
    @Test void feedbackIsIdempotentAndMaintainerResolutionIsVersioned() {
        Submit request = request(); finish(request);
        var feedback = new FeedbackRequest(id(), request.requestId(), null, "MISSING", "审批材料缺说明");
        assertThat(service.feedback(owner, 1, 401, feedback)).isEqualTo(service.feedback(owner, 1, 401, feedback));
        code(() -> service.feedback(peer, 1, 401, new FeedbackRequest(id(), request.requestId(), null, "MISSING", "说明")), 404);
        code(() -> service.feedbacks(owner, 1, 401), 404);
        db.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE member_id=101 AND knowledge_space_id=401");
        assertThat(service.feedbacks(owner, 1, 401)).hasSize(1);
        service.resolve(owner, 1, 401, feedback.id(), new Resolution(1, "RESOLVED", "已补充制度，待发布"));
        code(() -> service.resolve(owner, 1, 401, feedback.id(), new Resolution(1, "RESOLVED", "重复处理")), 409);
        assertThat(service.feedbacks(owner, 1, 401).get(0).revision()).isEqualTo(2);
    }
    @Test void feedbackCannotInventCitationOrChangeExistingRequest() {
        Submit request = request(); finish(request);
        code(() -> service.feedback(owner, 1, 401, new FeedbackRequest(id(), request.requestId(), 999L, "OTHER", "伪造引用")), 400);
        String id = id(); service.feedback(owner, 1, 401, new FeedbackRequest(id, request.requestId(), null, "OTHER", "原说明"));
        code(() -> service.feedback(owner, 1, 401, new FeedbackRequest(id, request.requestId(), null, "OTHER", "换说明")), 409);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "a\u0000b", "a\u200bb"})
    void invalidQuestionIsRejectedWithoutSideEffect(String question) {
        code(() -> submit(new Submit(id(), id(), question)), 400);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_job", Integer.class)).isZero();
    }
    @Test void failurePersistsSafeStatusWithoutThirdPartySecrets() {
        when(answers.answerWithin(any(), anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new BusinessException(ResultCode.SERVICE_BUSY, "SECRET_TOKEN"));
        Submit request = request(); submit(request); service.dispatch();
        await().atMost(Duration.ofSeconds(3)).until(() -> "FAILED".equals(service.job(owner, 1, 401, request.requestId()).status()));
        assertThat(service.job(owner, 1, 401, request.requestId()).message()).doesNotContain("SECRET_TOKEN");
        verify(answers, times(1)).answerWithin(any(), anyLong(), anyLong(), anyString(), any());
    }
}
