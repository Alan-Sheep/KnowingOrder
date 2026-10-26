package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.impl.*;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离H2真实授权SQL；无模型、搜索、真实MySQL、凭据或DDL迁移。 */
class KnowledgeConversationContextServiceTest {
    private EnterpriseDatabase db;
    private KnowledgeWorkspaceMapper mapper;
    private KnowledgeConversationContextService service;
    private EnterpriseMemberService members;
    private EnterpriseResourceService resources;
    private final AuthenticatedUserPrincipal owner = new AuthenticatedUserPrincipal(1, 1);
    private String chat;
    private String anchor;

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        db.factory.getConfiguration().addMapper(KnowledgeWorkspaceMapper.class);
        mapper = db.session.getMapper(KnowledgeWorkspaceMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(db.session.getMapper(SysUserMapper.class)),
                db.session.getMapper(EnterpriseDirectoryMapper.class));
        resources = new EnterpriseResourceServiceImpl(members, db.session.getMapper(EnterpriseResourceMapper.class));
        service = create(mapper);
        chat = chat(1, 101, 1, 401);
        anchor = job(chat, "那要找谁审批？", 100, "PENDING");
    }
    @AfterEach void close() throws Exception { db.close(); }
    private KnowledgeConversationContextService create(KnowledgeWorkspaceMapper store) {
        return new KnowledgeConversationContextService(store, members, resources);
    }
    private String chat(long enterprise, long member, long user, long space) {
        String id = UUID.randomUUID().toString();
        db.jdbc.update("INSERT INTO rag_knowledge_chat(id,enterprise_id,member_id,user_id,space_id,title,created_ms) VALUES(?,?,?,?,?,?,1)",
                id, enterprise, member, user, space, "合成测试");
        return id;
    }
    private String job(String chatId, String question, long time, String status) {
        String id = UUID.randomUUID().toString();
        db.jdbc.update("INSERT INTO rag_knowledge_job(id,chat_id,auth_version,question,status,created_ms,deadline_ms) VALUES(?,?,1,?,?,?,?)",
                id, chatId, question, status, time, time + 30000);
        return id;
    }
    private KnowledgeConversationContextService.Context read() {
        return service.read(owner, 1L, 401, anchor, TaskDeadline.start(Duration.ofSeconds(5)));
    }
    private void code(Runnable work, int expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(expected));
    }

    @Test void firstQuestionHasNoHistoryAndKeepsOriginalText() {
        var result = read();
        assertThat(result.requestId()).isEqualTo(anchor);
        assertThat(result.chatId()).isEqualTo(chat);
        assertThat(result.question()).isEqualTo("那要找谁审批？");
        assertThat(result.previous()).isEmpty();
    }
    @Test void includesPendingRunningAndCompletedQuestionsInReceptionOrder() {
        job(chat, "加班餐补上限多少？", 10, "RUNNING");
        job(chat, "还需要什么材料？", 20, "PENDING");
        job(chat, "仅限研发部门吗？", 30, "COMPLETED");
        assertThat(read().previous()).extracting("question")
                .containsExactly("加班餐补上限多少？", "还需要什么材料？", "仅限研发部门吗？");
    }
    @Test void neverIncludesCurrentSameMillisecondOrLaterQuestions() {
        job(chat, "并发同毫秒的问题", 100, "COMPLETED");
        job(chat, "之后的问题", 101, "COMPLETED");
        assertThat(read().previous()).isEmpty();
    }
    @Test void retainsEqualPriorReceptionTimesWithoutClaimingUuidIsTemporalOrder() {
        job(chat, "历史同时提交的问题甲", 10, "COMPLETED");
        job(chat, "历史同时提交的问题乙", 10, "RUNNING");
        assertThat(read().previous()).extracting("createdMs").containsExactly(10L, 10L);
        assertThat(read().previous()).extracting("question")
                .containsExactlyInAnyOrder("历史同时提交的问题甲", "历史同时提交的问题乙");
    }
    @Test void excludesFailedCancelledAndTimedOutQuestions() {
        for (String status : new String[]{"FAILED", "CANCELLED", "TIMED_OUT"}) job(chat, status, 10, status);
        assertThat(read().previous()).isEmpty();
    }
    @Test void selectsFourMostRecentThenReturnsChronologicalOrder() {
        for (int index = 1; index <= 6; index++) job(chat, "问题" + index, index, "COMPLETED");
        assertThat(read().previous()).extracting("question").containsExactly("问题3", "问题4", "问题5", "问题6");
    }
    @Test void limitsCharactersWithoutSplittingQuestionsOrSkippingToOldTopics() {
        job(chat, "很旧的短问题", 1, "COMPLETED");
        job(chat, "甲".repeat(1200), 2, "COMPLETED");
        job(chat, "乙".repeat(1200), 3, "COMPLETED");
        job(chat, "新问题", 4, "RUNNING");
        assertThat(read().previous()).extracting("question").containsExactly("乙".repeat(1200), "新问题");
    }
    @Test void exactCharacterLimitIsAllowed() {
        job(chat, "甲".repeat(1200), 1, "COMPLETED");
        job(chat, "乙".repeat(1200), 2, "COMPLETED");
        assertThat(read().previous()).hasSize(2);
    }
    @Test void doesNotReadStoredAnswersOrTreatThemAsEvidence() {
        String prior = job(chat, "用户的原问题", 1, "COMPLETED");
        db.jdbc.update("UPDATE rag_knowledge_job SET answer_json=? WHERE id=?", "不是有效JSON：不应解析的旧原文", prior);
        assertThat(read().previous()).extracting("question").containsExactly("用户的原问题");
        assertThat(read().toString()).doesNotContain("那要找谁", "用户的原问题", "旧原文");
        assertThat(read().previous().get(0).toString()).doesNotContain("用户的原问题");
    }
    @Test void cannotReadOtherMemberEnterpriseSpaceOrConversation() {
        job(chat(1, 102, 2, 401), "其他员工的问题", 1, "COMPLETED");
        job(chat(2, 201, 1, 403), "其他企业的问题", 1, "COMPLETED");
        job(chat(1, 101, 1, 404), "其他空间的问题", 1, "COMPLETED");
        job(chat(1, 101, 1, 401), "本人的其他会话", 1, "COMPLETED");
        assertThat(read().previous()).isEmpty();
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,102,'READ')");
        code(() -> service.read(new AuthenticatedUserPrincipal(2, 1), 1L, 401, anchor,
                TaskDeadline.start(Duration.ofSeconds(5))), 404);
        code(() -> service.read(owner, 2L, 403, anchor, TaskDeadline.start(Duration.ofSeconds(5))), 404);
        code(() -> service.read(owner, 1L, 404, anchor, TaskDeadline.start(Duration.ofSeconds(5))), 404);
    }
    @Test void revokedAccountCannotReadHistory() {
        db.jdbc.update("UPDATE sys_user SET auth_version=2 WHERE id=1");
        code(this::read, 401);
    }
    @Test void revocationDuringHistoryReadPreventsReturningSnapshot() {
        var observed = spy(mapper);
        doAnswer(call -> {
            var result = mapper.contextQuestions(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=101 AND knowledge_space_id=401");
            return result;
        }).when(observed).contextQuestions(any(), anyLong(), anyString());
        service = create(observed);
        code(this::read, 404);
    }
    @Test void databaseFailureIsNotSilentlyConvertedToEmptyHistory() {
        var observed = spy(mapper);
        doThrow(new DataAccessResourceFailureException("合成故障")).when(observed).contextQuestions(any(), anyLong(), anyString());
        service = create(observed);
        assertThatThrownBy(this::read).isInstanceOf(DataAccessResourceFailureException.class);
    }
    @Test void exhaustedParentBudgetPreventsDatabaseReads() {
        var budget = mock(TaskDeadline.class);
        when(budget.timeoutFor(any())).thenThrow(new BusinessException(ResultCode.PROCESS_TIMEOUT, "合成超时"));
        var observed = spy(mapper); service = create(observed);
        code(() -> service.read(owner, 1L, 401, anchor, budget), 504);
        verifyNoInteractions(observed);
    }
    @Test void resultIsImmutable() {
        job(chat, "原问题", 1, "COMPLETED");
        assertThatThrownBy(() -> read().previous().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"不是UUID", "' OR 1=1 --"})
    void invalidIdentifierDoesNotReachDatabase(String value) {
        var observed = spy(mapper); service = create(observed);
        code(() -> service.read(owner, 1L, 401, value, TaskDeadline.start(Duration.ofSeconds(5))), 400);
        verifyNoInteractions(observed);
    }
}
