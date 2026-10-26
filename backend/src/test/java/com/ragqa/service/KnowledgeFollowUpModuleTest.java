package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.chat.*;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.service.impl.*;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实授权SQL/事务/调度加固定模型替身；不是目标MySQL或真实模型语义成绩。 */
class KnowledgeFollowUpModuleTest {
    private EnterpriseDatabase db;
    private KnowledgeWorkspaceMapper mapper;
    private KnowledgeManagementMapper management;
    private EnterpriseMemberService members;
    private EnterpriseResourceService resources;
    private KnowledgeAnswerService answers;
    private KnowledgeFollowUpService followUps;
    private KnowledgeWorkspaceService workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model = mock(ChatModel.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final AuthenticatedUserPrincipal owner = new AuthenticatedUserPrincipal(1, 1);
    private String chat;
    private String parent;
    private String relation;
    private String verdict;
    private Evidence evidence;

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        db.factory.getConfiguration().addMapper(KnowledgeWorkspaceMapper.class);
        db.factory.getConfiguration().addMapper(KnowledgeManagementMapper.class);
        var sql = new SqlSessionTemplate(db.factory);
        mapper = sql.getMapper(KnowledgeWorkspaceMapper.class); management = sql.getMapper(KnowledgeManagementMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        resources = new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class));
        String content = "加班餐补每人每天30元，申请需主管审批。";
        evidence = new Evidence(1L, 2L, 3L, 1, "合成制度", 0, content, 0, content.length(), 1, 1);
        when(retriever.retrieve(any(), anyLong(), anyString(), any())).thenAnswer(call ->
                call.<String>getArgument(2).contains("餐补") ? List.of(evidence) : List.of());
        when(retriever.requireEvidence(any(), anyLong(), eq(1L))).thenReturn(evidence);
        relation = "FOLLOW_UP"; verdict = "ACCEPT";
        when(model.call(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            Map<String, Object> payload = call.getArgument(1);
            String name = call.getArgument(0);
            if (name.equals("follow-up")) {
                var output = json.createObjectNode().put("snapshotId", payload.get("snapshotId").toString()).put("relation", relation);
                if (relation.equals("FOLLOW_UP")) output.put("antecedentId", parent); else output.putNull("antecedentId");
                return output;
            }
            if (name.equals("follow-up-review")) return json.valueToTree(Map.of("snapshotId", payload.get("snapshotId"),
                    "antecedentId", payload.get("antecedentId"), "verdict", verdict));
            if (name.equals("knowledge-support")) return json.valueToTree(Map.of("snapshotId", payload.get("snapshotId"),
                    "verdicts", List.of(Map.of("id", "1:0", "verdict", "SUPPORTED"))));
            return json.valueToTree(Map.of("parts", List.of(Map.of("question", payload.get("question"), "status", "ANSWERED",
                    "paragraphs", List.of(Map.of("text", "申请需主管审批。", "citations", List.of(Map.of(
                            "sourceId", 1, "quote", "申请需主管审批")))), "gaps", List.of()))));
        });
        answers = new KnowledgeAnswerService(members, resources, retriever, new KnowledgeAnswerComposer(model), "1:401");
        configure(mapper, true);
        chat = UUID.randomUUID().toString(); parent = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        var member = members.requireMember(owner, 1L);
        mapper.createChat(member, 401, chat, "合成会话", now - 1000);
        mapper.createJob(parent, chat, 1, "加班餐补上限多少？", now - 1000, now + 29000);
        db.jdbc.update("UPDATE rag_knowledge_job SET status='COMPLETED' WHERE id=?", parent);
    }

    private void configure(KnowledgeWorkspaceMapper store, boolean enabled) {
        if (workspace != null) workspace.close();
        followUps = new KnowledgeFollowUpService(store, new KnowledgeConversationContextService(store, members, resources),
                members, resources, answers, new FollowUpResolver(model), json, enabled);
        workspace = new KnowledgeWorkspaceService(store, management, members, resources, answers, json,
                new DataSourceTransactionManager(db.dataSource), followUps);
    }
    @AfterEach void close() throws Exception { workspace.close(); answers.close(); db.close(); }
    private KnowledgeWorkspaceService.Submit request(String question) {
        return new KnowledgeWorkspaceService.Submit(chat, UUID.randomUUID().toString(), question);
    }
    private KnowledgeWorkspaceService.Job submit(KnowledgeWorkspaceService.Submit request) {
        return workspace.submit(owner, 1, 401, request);
    }
    private KnowledgeWorkspaceService.Job finish(KnowledgeWorkspaceService.Submit request) {
        submit(request); workspace.dispatch();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() ->
                !List.of("PENDING", "RUNNING").contains(workspace.job(owner, 1, 401, request.requestId()).status()));
        return workspace.job(owner, 1, 401, request.requestId());
    }
    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(10)); }

    @Test void receiptSnapshotIsImmutableAcrossDuplicatePostsAndLaterHistoryChanges() throws Exception {
        var request = request("那找谁审批？"); var first = submit(request);
        var member = members.requireMember(owner, 1L);
        var original = mapper.context(member, 401, request.requestId());
        db.jdbc.update("UPDATE rag_knowledge_job SET status='FAILED',question='后来改动' WHERE id=?", parent);
        assertThat(submit(request).deadlineMs()).isEqualTo(first.deadlineMs());
        assertThat(mapper.context(member, 401, request.requestId())).isEqualTo(original);
        var context = json.readValue(original.contextJson(), KnowledgeConversationContextService.Context.class);
        assertThat(context.previous()).extracting(KnowledgeWorkspaceMapper.ContextQuestion::question).containsExactly("加班餐补上限多少？");
        assertThat(followUps.resolve(owner, 1, 401, request.requestId(), request.question(), deadline()).status())
                .isEqualTo(FollowUpResolver.Status.FOLLOW_UP);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM rag_knowledge_context", Integer.class)).isEqualTo(1);
    }

    @Test void completeFlowUsesCurrentQuestionAndSeparateTopicAndPersistsReadableResult() {
        var request = request("那找谁审批？"); var job = finish(request);
        assertThat(job.status()).isEqualTo("COMPLETED"); assertThat(job.answer().status()).isEqualTo("ANSWERED");
        assertThat(job.question()).isEqualTo(request.question());
        assertThat(job.answer().parts().get(0).question()).isEqualTo(request.question());
        assertThat(job.answer().parts().get(0).paragraphs().get(0).text()).isEqualTo("申请需主管审批。");
        assertThat(job.answer().message()).contains("话题", "历史问题不作为制度依据");
        verify(model).call(eq("knowledge-follow-up"), argThat(value -> value instanceof Map<?, ?> map
                && request.question().equals(map.get("question")) && "加班餐补上限多少？".equals(map.get("antecedentQuestion"))), eq(3000), any());
        verify(model, times(4)).call(anyString(), any(), anyInt(), any());
        assertThat(workspace.history(owner, 1, 401, chat).get(0).answer()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings = {"REJECT", "UNKNOWN"})
    void uncertainTopicOnlyShowsCurrentEvidenceAndNeverGeneratesAnswer(String review) {
        verdict = review;
        var job = finish(request("那餐补找谁审批？"));
        assertThat(job.status()).isEqualTo("COMPLETED");
        assertThat(job.answer().status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(job.answer().message()).contains("未能可靠确定", "未生成确定答案");
        assertThat(job.answer().parts().get(0).paragraphs()).isEmpty();
        verify(model, never()).call(eq("knowledge"), any(), anyInt(), any());
        verify(retriever, times(1)).retrieve(any(), anyLong(), anyString(), any());
    }

    @Test void topicSwitchDoesNotRetrieveOrSendThePreviousQuestionToAnswering() {
        relation = "STANDALONE";
        var job = finish(request("年假几天？"));
        assertThat(job.answer().status()).isEqualTo("NO_EVIDENCE");
        verify(retriever, never()).retrieve(any(), anyLong(), eq("加班餐补上限多少？"), any());
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test void providerFailureKeepsOriginalEvidenceWithoutAnotherPaidAttempt() {
        doThrow(new BusinessException("SECRET_RESPONSE")).when(model).call(anyString(), any(), anyInt(), any());
        var job = finish(request("那餐补呢？"));
        assertThat(job.answer().status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(job.answer().message()).contains("未自动重试").doesNotContain("SECRET_RESPONSE");
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test void realEnterpriseModeDoesNotSendAnyHistoryOrQuestionToModel() {
        answers.close(); answers = new KnowledgeAnswerService(members, resources, retriever, new KnowledgeAnswerComposer(model), "");
        configure(mapper, true);
        var job = finish(request("那餐补呢？"));
        assertThat(job.answer().mode()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(job.answer().message()).contains("未获上下文外发许可"); verifyNoInteractions(model);
    }

    @Test void featureOffDoesNotNeedTheNewTableOrCaptureAnySnapshot() {
        var store = spy(mapper); configure(store, false);
        var job = finish(request("那餐补呢？"));
        assertThat(job.status()).isEqualTo("COMPLETED");
        verify(store, never()).saveContext(any(), anyLong(), anyString(), anyString(), anyString());
        verify(store, never()).context(any(), anyLong(), anyString());
        verify(model, never()).call(eq("follow-up"), any(), anyInt(), any());
    }

    @Test void snapshotFailureRollsBackNewChatAndTaskTogether() {
        var store = spy(mapper);
        doThrow(new DataAccessResourceFailureException("SAFE_TEST_FAILURE")).when(store)
                .saveContext(any(), anyLong(), anyString(), anyString(), anyString());
        configure(store, true);
        var request = new KnowledgeWorkspaceService.Submit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "餐补？");
        assertThatThrownBy(() -> submit(request)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM rag_knowledge_job WHERE id=?", Integer.class, request.requestId())).isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM rag_knowledge_chat WHERE id=?", Integer.class, request.chatId())).isZero();
        verifyNoInteractions(model);
    }

    @Test void missingLegacySnapshotNeverBackfillsFromNewHistory() {
        assertThat(followUps.resolve(owner, 1, 401, parent, "加班餐补上限多少？", deadline()).status())
                .isEqualTo(FollowUpResolver.Status.NO_SNAPSHOT); verifyNoInteractions(model);
    }

    @ParameterizedTest @ValueSource(strings = {"body", "hash", "task"})
    void corruptOrMisboundStoredSnapshotStopsBeforeAnyModel(String mutation) {
        var request = request("那找谁审批？"); submit(request);
        if (mutation.equals("body")) db.jdbc.update("UPDATE rag_knowledge_context SET context_json='SECRET_INVALID_JSON' WHERE job_id=?", request.requestId());
        else if (mutation.equals("hash")) db.jdbc.update("UPDATE rag_knowledge_context SET snapshot_id=? WHERE job_id=?", "0".repeat(64), request.requestId());
        else db.jdbc.update("UPDATE rag_knowledge_job SET question='不同问题' WHERE id=?", request.requestId());
        assertThatThrownBy(() -> followUps.resolve(owner, 1, 401, request.requestId(), request.question(), deadline()))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("SECRET_INVALID_JSON");
        verifyNoInteractions(model);
    }

    @Test void anotherAccountCannotReadSameSnapshotEvenWithValidSpaceGrant() {
        var request = request("那找谁审批？"); submit(request);
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,102,'READ')");
        var peer = new AuthenticatedUserPrincipal(2, 1);
        assertThat(mapper.context(members.requireMember(peer, 1L), 401, request.requestId())).isNull();
        assertThatThrownBy(() -> followUps.resolve(peer, 1, 401, request.requestId(), request.question(), deadline())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(model);
    }

    @Test void cancellationDuringResolutionCannotStartAnsweringOrOverwriteTerminalState() throws Exception {
        var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { released.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { exited.countDown(); }
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT);
        }).when(model).call(anyString(), any(), anyInt(), any());
        var request = request("那找谁审批？"); submit(request); workspace.dispatch();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try { assertThat(workspace.cancel(owner, 1, 401, request.requestId()).status()).isEqualTo("CANCELLED"); }
        finally { released.countDown(); }
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(workspace.job(owner, 1, 401, request.requestId()).status()).isEqualTo("CANCELLED"));
        verify(model, never()).call(eq("knowledge"), any(), anyInt(), any());
        verify(model, never()).call(eq("knowledge-follow-up"), any(), anyInt(), any());
    }

    @Test void revocationDuringModelCallIsNotReportedAsOrdinaryContextFallback() {
        var request = request("那找谁审批？"); submit(request);
        doAnswer(call -> {
            db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT);
        }).when(model).call(anyString(), any(), anyInt(), any());
        assertThatThrownBy(() -> followUps.resolve(owner, 1, 401, request.requestId(), request.question(), deadline()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }

    @Test void snapshotReadFailureIsNotSilentlyConvertedToNoHistory() {
        var request = request("那找谁审批？"); submit(request);
        var store = spy(mapper);
        doThrow(new DataAccessResourceFailureException("SAFE_STORAGE_FAILURE")).when(store).context(any(), anyLong(), anyString());
        configure(store, true);
        assertThatThrownBy(() -> followUps.resolve(owner, 1, 401, request.requestId(), request.question(), deadline()))
                .isInstanceOf(DataAccessResourceFailureException.class); verifyNoInteractions(model);
    }

    @Test void duplicateJsonKeysInPersistedSnapshotAreRejected() {
        var request = request("那找谁审批？"); submit(request);
        String body = mapper.context(members.requireMember(owner, 1L), 401, request.requestId()).contextJson();
        body = body.replaceFirst("\\{", "{\"question\":\"额外问题\",");
        db.jdbc.update("UPDATE rag_knowledge_context SET context_json=? WHERE job_id=?", body, request.requestId());
        assertThatThrownBy(() -> followUps.resolve(owner, 1, 401, request.requestId(), request.question(), deadline()))
                .isInstanceOf(BusinessException.class); verifyNoInteractions(model);
    }
}
