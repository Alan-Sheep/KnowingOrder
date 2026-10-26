package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.*;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.KnowledgeAnswerComposer;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAnswerServiceTest {
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final KnowledgeAnswerComposer composer = mock(KnowledgeAnswerComposer.class);
    private final AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(1, 1);
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final Evidence evidence = new Evidence(1L, 2L, 3L, 1, "虚构制度", 0, "加班餐补每日30元。", 0, 11, 1, 1);
    private KnowledgeAnswerService service;

    @BeforeEach
    void setup() {
        when(members.requireMember(user, 1L)).thenReturn(member);
        when(members.requireCurrent(member)).thenReturn(member);
        when(composer.review(anyString(), anyList(), any())).thenAnswer(call -> call.getArgument(1));
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of(evidence));
        when(retriever.requireEvidence(member, 401, 1)).thenReturn(evidence);
        service = create("");
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void defaultAndWrongSpaceNeverTransmitQuestionOrDocumentsToModel() {
        assertThat(service.mode(user, 1L, 401L)).isEqualTo("LOCAL_EVIDENCE");
        var answer = service.answer(user, 1L, 401L, "餐补多少？");
        assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(answer.parts().get(0).paragraphs()).isEmpty();
        verifyNoInteractions(composer);
        service.close();
        service = create("2:401,1:402");
        assertThat(service.answer(user, 1L, 401L, "餐补多少？").mode()).isEqualTo("LOCAL_EVIDENCE");
        verifyNoInteractions(composer);
    }

    @Test
    void explicitlyAllowedSyntheticSpaceCallsModelExactlyOnce() {
        service.close(); service = create("1:401");
        assertThat(service.mode(user, 1L, 401L)).isEqualTo("SYNTHETIC_MODEL");
        when(composer.compose(anyString(), anyList(), any())).thenReturn(List.of(new Part(1, "餐补多少？", "ANSWERED", List.of(), List.of())));
        assertThat(service.answer(user, 1L, 401L, "餐补多少？").status()).isEqualTo("ANSWERED");
        verify(composer, times(1)).compose(anyString(), anyList(), any());
        verify(retriever, times(3)).requireEvidence(member, 401, 1);
    }

    @Test
    void emptyRetrievalDoesNotCallModelOrPublicSearch() {
        service.close(); service = create("1:401");
        when(retriever.retrieve(any(), anyLong(), anyString(), any())).thenReturn(List.of());
        assertThat(service.answer(user, 1L, 401L, "交通报销？").status()).isEqualTo("NO_EVIDENCE");
        verifyNoInteractions(composer);
    }

    @Test
    void unavailableModelKeepsEvidenceAndReturnsExplicitFailureInsteadOfPretendingSuccess() {
        service.close(); service = create("1:401");
        when(composer.compose(anyString(), anyList(), any())).thenThrow(new BusinessException("UPSTREAM_SECRET_BODY"));
        var answer = service.answer(user, 1L, 401L, "餐补？");
        assertThat(answer.status()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(answer.evidence()).containsExactly(evidence);
        assertThat(answer.message()).doesNotContain("UPSTREAM_SECRET_BODY");
        verify(composer, times(1)).compose(anyString(), anyList(), any());
    }

    @Test
    void revokedEvidenceBeforeOrAfterModelCannotBeReturned() {
        service.close(); service = create("1:401");
        when(composer.compose(anyString(), anyList(), any())).thenAnswer(call -> {
            when(retriever.requireEvidence(member, 401, 1)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
            return List.of(new Part(1, "餐补？", "ANSWERED", List.of(), List.of()));
        });
        code(() -> service.answer(user, 1L, 401L, "餐补？"), 404);
        verify(composer, times(1)).compose(anyString(), anyList(), any());
    }

    @Test
    void membershipRevocationStopsBeforeModel() {
        service.close(); service = create("1:401");
        when(members.requireCurrent(member)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
        code(() -> service.answer(user, 1L, 401L, "餐补？"), 404);
        verifyNoInteractions(composer);
    }

    @Test
    void boundedExecutorRejectsThirdRequestWithoutUnboundedQueue() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(retriever.retrieve(any(), anyLong(), anyString(), any())).thenAnswer(call -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return List.of(evidence);
        });
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> service.answer(user, 1L, 401L, "餐补？"));
            var second = callers.submit(() -> service.answer(user, 1L, 401L, "审批？"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            code(() -> service.answer(user, 1L, 401L, "第三条？"), 503);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo("EVIDENCE_ONLY");
            assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo("EVIDENCE_ONLY");
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test
    void totalDeadlineTerminatesWaitAndDoesNotRetry() throws Exception {
        service.close(); service = new KnowledgeAnswerService(members, resources, retriever, composer, "", Duration.ofMillis(80));
        var interrupted = new CountDownLatch(1);
        when(retriever.retrieve(any(), anyLong(), anyString(), any())).thenAnswer(call -> {
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return List.of(evidence);
        });
        long started = System.nanoTime();
        code(() -> service.answer(user, 1L, 401L, "餐补？"), 504);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(retriever, times(1)).retrieve(any(), anyLong(), anyString(), any());
        verifyNoInteractions(composer);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\u0000", "\u0001bad"})
    void invalidQuestionIsRejectedBeforeAnyWork(String question) {
        code(() -> service.answer(user, 1L, 401L, question), 400);
        verifyNoInteractions(members, composer, retriever);
    }

    @Test
    void lengthAndIdentifiersAreValidated() {
        code(() -> service.answer(user, 1L, 401L, "字".repeat(1201)), 400);
        code(() -> service.answer(user, 1L, 0L, "餐补？"), 400);
        code(() -> service.evidence(user, 1L, 401L, 0L), 400);
    }

    @Test
    void acrossSpacesWithoutPermissionKeepsEvidenceAndNeverCallsModel() {
        acrossSpaces(2);
        var answer = service.answerAcross(user, 1L, "校园网密码怎么重置？");
        assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(answer.evidence()).hasSize(4);
        verifyNoInteractions(composer);
    }

    @Test
    void acrossSpacesUsesAtMostNineSourcesAndKeepsTheNinthCitation() {
        acrossSpaces(6);
        service.close(); service = create("1:401,1:402,1:403,1:404,1:405,1:406");
        when(composer.compose(anyString(), anyList(), any())).thenAnswer(call -> {
            List<Evidence> selected = call.getArgument(1);
            assertThat(selected).hasSize(9);
            return List.of(new Part(1, call.getArgument(0), "ANSWERED", List.of(new Paragraph("按指南重置。",
                    List.of(new Citation(9, selected.get(8), selected.get(8).content())))), List.of()));
        });
        var answer = service.answerAcross(user, 1L, "校园网密码怎么重置？");
        assertThat(answer.mode()).isEqualTo("SYNTHETIC_MODEL");
        assertThat(answer.status()).isEqualTo("ANSWERED");
        assertThat(answer.evidence()).hasSize(9);
        assertThat(answer.parts().get(0).paragraphs().get(0).citations().get(0).evidence())
                .isEqualTo(answer.evidence().get(8).evidence());
        verify(composer, times(1)).compose(anyString(), anyList(), any());
        verify(composer, times(1)).review(anyString(), anyList(), any());
    }

    @Test
    void discardedUnapprovedCandidateCannotDisableGenerationForApprovedFinalEvidence() {
        acrossSpaces(6);
        var discarded = new Evidence(999L, 999L, 999L, 1, "校园活动", 0, "校园活动通知。", 0, 7, 1, 1);
        when(retriever.retrieve(eq(member), eq(406L), anyString(), any())).thenReturn(List.of(discarded));
        service.close(); service = create("1:401,1:402,1:403,1:404,1:405");
        when(composer.compose(anyString(), anyList(), any())).thenAnswer(call -> {
            List<Evidence> selected = call.getArgument(1);
            assertThat(selected).hasSize(9).doesNotContain(discarded);
            return List.of(new Part(1, call.getArgument(0), "NO_EVIDENCE", List.of(), List.of("缺少具体步骤。")));
        });
        var answer = service.answerAcross(user, 1L, "校园网密码怎么重置？");
        assertThat(answer.evidence()).noneMatch(item -> item.spaceId().equals(406L));
        assertThat(answer.mode()).isEqualTo("SYNTHETIC_MODEL");
        verify(composer, times(1)).compose(anyString(), anyList(), any());
    }

    @Test
    void selectedUnapprovedSourceStillPreventsAllModelCalls() {
        acrossSpaces(2);
        service.close(); service = create("1:401");
        var answer = service.answerAcross(user, 1L, "校园网密码怎么重置？");
        assertThat(answer.evidence()).anyMatch(item -> item.spaceId().equals(402L));
        assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
        verifyNoInteractions(composer);
    }

    @Test
    void acrossRevocationAfterGenerationStopsBeforeSendingQuotesForReview() {
        acrossSpaces(1);
        service.close(); service = create("1:401");
        when(composer.compose(anyString(), anyList(), any())).thenAnswer(call -> {
            when(members.requireCurrent(member)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
            return List.of(new Part(1, call.getArgument(0), "NO_EVIDENCE", List.of(), List.of("缺少依据。")));
        });
        code(() -> service.answerAcross(user, 1L, "校园网密码怎么重置？"), 404);
        verify(composer, never()).review(anyString(), anyList(), any());
    }

    private void acrossSpaces(int count) {
        var spaces = new java.util.ArrayList<EnterpriseResources.KnowledgeSpace>();
        for (int index = 0; index < count; index++) {
            long space = 401L + index;
            spaces.add(new EnterpriseResources.KnowledgeSpace(space, "校园服务" + index, 1));
            var sources = new java.util.ArrayList<Evidence>();
            for (int rank = 0; rank < 2; rank++) {
                long id = 100L + index * 2 + rank;
                String content = "校园网密码重置请使用信息服务门户。";
                var source = new Evidence(id, id, id, 1, "校园网密码重置", rank, content, 0, content.length(), 1, 1);
                sources.add(source);
                when(retriever.requireEvidence(member, space, id)).thenReturn(source);
            }
            when(retriever.retrieve(eq(member), eq(space), anyString(), any())).thenReturn(List.copyOf(sources));
        }
        var page = new Page<EnterpriseResources.KnowledgeSpace>(1, 500, count);
        page.setRecords(spaces);
        when(resources.listSpaces(member, 1L, 500L)).thenReturn(PageResult.of(page));
    }

    private KnowledgeAnswerService create(String spaces) { return new KnowledgeAnswerService(members, resources, retriever, composer, spaces); }
    private void code(Runnable action, int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(expected));
    }
}
