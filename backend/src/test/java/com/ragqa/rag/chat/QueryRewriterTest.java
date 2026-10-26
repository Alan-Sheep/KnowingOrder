package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources.KnowledgeSpace;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.QueryRewriter;
import com.ragqa.rag.retrieval.QueryRewriter.Status;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 真实模板、严格解析与改写器，只有授权服务和供应商传输使用替身；无真实外发。 */
@Timeout(10)
class QueryRewriterTest {
    private static final String QUESTION = "夜里多干活的伙食津贴一天最多能领几块钱？";
    private static final String ALTERNATIVE = "加班餐补每人每天最多能领取多少？";
    private static final Instant RECEIVED = Instant.parse("2026-10-18T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final ResponsesClient client = mock(ResponsesClient.class);
    private final ChatModel model = new ChatModel(client, new PromptRegistry(), json);
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final AtomicLong ticks = new AtomicLong();

    @BeforeEach
    void authorizeFixture() {
        when(members.requireCurrent(member)).thenReturn(member);
        when(resources.requireSpace(member, 401L)).thenReturn(new KnowledgeSpace(401L, "虚构空间", 1));
        respond(ALTERNATIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2:401", "1:402", "11:401", "1:4010"})
    void absentOrDifferentExactPermissionNeverSendsQuestion(String allowed) {
        var outcome = create(allowed).rewrite(member, 401L, QUESTION, parent());
        assertThat(outcome.status()).isEqualTo(Status.NOT_PERMITTED);
        assertThat(outcome.alternativeQuery()).isEmpty();
        verify(members).requireCurrent(member);
        verify(resources).requireSpace(member, 401L);
        verifyNoInteractions(client);
    }

    @Test
    void authorizedCandidateUsesExactPayloadOneCallAndChecksPermissionInOrder() throws Exception {
        TaskDeadline parent = parent();
        ticks.addAndGet(Duration.ofSeconds(7).toNanos());
        var outcome = create(" 1:401 , 1:401 , ").rewrite(member, 401L, QUESTION, parent);
        assertThat(outcome.status()).isEqualTo(Status.CANDIDATE);
        assertThat(outcome.alternativeQuery()).isEqualTo(ALTERNATIVE);
        assertThat(outcome.message()).contains("不代表语义等价");
        ArgumentCaptor<TaskDeadline> stage = ArgumentCaptor.forClass(TaskDeadline.class);
        var order = inOrder(members, resources, client);
        order.verify(members).requireCurrent(member);
        order.verify(resources).requireSpace(member, 401L);
        order.verify(client).generate(anyString(), anyString(), eq(1000), stage.capture());
        order.verify(members).requireCurrent(member);
        order.verify(resources).requireSpace(member, 401L);
        order.verifyNoMoreInteractions();
        assertThat(stage.getValue()).isNotSameAs(parent);
        assertThat(stage.getValue().receivedAt()).isEqualTo(parent.receivedAt());
        assertThat(stage.getValue().deadlineAt()).isEqualTo(RECEIVED.plusSeconds(12));
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(13));
        var input = ArgumentCaptor.forClass(String.class);
        verify(client).generate(anyString(), input.capture(), eq(1000), any());
        assertThat(input.getValue()).contains(json.writeValueAsString(Map.of("originalQuestion", QUESTION)))
                .doesNotContain("enterpriseId", "memberId", "spaceId", "credential", "documents");
    }

    @Test
    void parentRemainingTwoSecondsCapsActualModelArgument() {
        TaskDeadline parent = parent();
        ticks.addAndGet(Duration.ofSeconds(18).toNanos());
        create("1:401").rewrite(member, 401L, QUESTION, parent);
        var stage = ArgumentCaptor.forClass(TaskDeadline.class);
        verify(client).generate(anyString(), anyString(), eq(1000), stage.capture());
        assertThat(stage.getValue().remaining()).isEqualTo(Duration.ofSeconds(2));
        assertThat(stage.getValue().deadlineAt()).isEqualTo(parent.deadlineAt());
    }

    @ParameterizedTest
    @EnumSource(value = ResultCode.class, names = {"UNAUTHORIZED", "NOT_FOUND", "CONFLICT"})
    void memberRejectionIsNotConvertedToModelDowngrade(ResultCode code) {
        var failure = new BusinessException(code);
        when(members.requireCurrent(member)).thenThrow(failure);
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, parent())).isSameAs(failure);
        verifyNoInteractions(resources, client);
    }

    @Test
    void inaccessibleSpaceRejectsBeforeAnySupplierCallEvenWithoutExternalPermission() {
        var failure = new BusinessException(ResultCode.NOT_FOUND);
        when(resources.requireSpace(member, 401L)).thenThrow(failure);
        assertThatThrownBy(() -> create("").rewrite(member, 401L, QUESTION, parent())).isSameAs(failure);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(value = ResultCode.class, names = {"UNAUTHORIZED", "SYSTEM_ERROR", "SERVICE_BUSY", "PROCESS_TIMEOUT", "BIZ_ERROR"})
    void supplierFailureHasSafeVisibleStateAndNoRetry(ResultCode code) {
        when(client.generate(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new BusinessException(code, "fake-private-provider-body"));
        assertUnavailable(create("1:401").rewrite(member, 401L, QUESTION, parent()));
        verify(client, times(1)).generate(anyString(), anyString(), eq(1000), any());
        verifyNoMoreInteractions(client);
        verify(members, times(2)).requireCurrent(member);
        verify(resources, times(2)).requireSpace(member, 401L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "{} {}", "{\"alternativeQuery\":\"a\",\"alternativeQuery\":\"b\"}",
            "{\"alternativeQuery\":null}", "{\"alternativeQuery\":\"fake-private-provider-body\",\"spaceId\":999}",
            "{\"alternativeQuery\":\"加班餐补每天领取30元\"}"})
    void malformedOrConstraintChangingOutputIsDiscardedWithoutRetry(String output) {
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenReturn(output);
        assertUnavailable(create("1:401").rewrite(member, 401L, QUESTION, parent()));
        verify(client, times(1)).generate(anyString(), anyString(), eq(1000), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void unchangedExpressionDoesNotProduceAnAdditionalQuery() {
        respond(QUESTION);
        var outcome = create("1:401").rewrite(member, 401L, QUESTION, parent());
        assertThat(outcome.status()).isEqualTo(Status.UNCHANGED);
        assertThat(outcome.alternativeQuery()).isEmpty();
        verify(client, times(1)).generate(anyString(), anyString(), eq(1000), any());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void revocationAfterSupplierSuccessOrFailureStillRejectsEntireOperation(boolean revokeMember,
            boolean supplierSucceeds) {
        var denied = new BusinessException(ResultCode.NOT_FOUND);
        if (revokeMember) when(members.requireCurrent(member)).thenReturn(member).thenThrow(denied);
        else when(resources.requireSpace(member, 401L)).thenReturn(new KnowledgeSpace(401L, "虚构空间", 1)).thenThrow(denied);
        if (!supplierSucceeds) when(client.generate(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new BusinessException(ResultCode.SERVICE_BUSY));
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, parent())).isSameAs(denied);
        verify(client, times(1)).generate(anyString(), anyString(), eq(1000), any());
    }

    @Test
    void authorizationStorageFailureIsNotHiddenBySupplierDowngrade() {
        var unavailable = new IllegalStateException("授权存储故障");
        when(resources.requireSpace(member, 401L)).thenReturn(new KnowledgeSpace(401L, "虚构空间", 1))
                .thenThrow(unavailable);
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenThrow(new BusinessException("供应商故障"));
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, parent())).isSameAs(unavailable);
    }

    @Test
    void stageExpiredAfterSupplierResponseDiscardsCandidateWhileParentCanContinue() {
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            ticks.addAndGet(Duration.ofSeconds(6).toNanos());
            return output(ALTERNATIVE);
        });
        TaskDeadline parent = parent();
        assertUnavailable(create("1:401").rewrite(member, 401L, QUESTION, parent));
        assertThat(parent.remaining()).isEqualTo(Duration.ofSeconds(14));
        verify(client, times(1)).generate(anyString(), anyString(), eq(1000), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentExpiryBeforeOrDuringCallRejectsInsteadOfReturningDowngrade(boolean duringCall) {
        TaskDeadline parent = parent();
        if (duringCall) when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            ticks.addAndGet(Duration.ofSeconds(20).toNanos());
            return output(ALTERNATIVE);
        });
        else ticks.addAndGet(Duration.ofSeconds(20).toNanos());
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, parent))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(504);
                    assertThat(error.getCause()).isNull();
                });
        verify(client, times(duringCall ? 1 : 0)).generate(anyString(), anyString(), anyInt(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionBeforeOrDuringCallPreservesFlagAndDoesNotDowngrade(boolean duringCall) {
        try {
            if (duringCall) when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
                Thread.currentThread().interrupt();
                return output(ALTERNATIVE);
            });
            else Thread.currentThread().interrupt();
            assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, parent()))
                    .isInstanceOf(BusinessException.class).hasMessage("本次处理已取消");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(client, times(duringCall ? 1 : 0)).generate(anyString(), anyString(), anyInt(), any());
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest
    @MethodSource("invalidQuestions")
    void invalidInputNeverReachesAuthorizationOrSupplier(String question) {
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, question, parent()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        verifyNoInteractions(members, resources, client);
    }

    static Stream<String> invalidQuestions() {
        return Stream.of(null, "", " \t\n", "\u00a0", "\u200b", "\u0000", "\ud800", "\u2028", "字".repeat(1201));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidSpaceIdentifierNeverStartsWork(Long spaceId) {
        assertThatThrownBy(() -> create("1:401").rewrite(member, spaceId, QUESTION, parent()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        verifyNoInteractions(members, resources, client);
    }

    @Test
    void missingIdentityAndDeadlineCannotStartSupplierCall() {
        assertThatThrownBy(() -> create("1:401").rewrite(null, 401L, QUESTION, parent()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(401));
        assertThatThrownBy(() -> create("1:401").rewrite(member, 401L, QUESTION, null))
                .isInstanceOf(NullPointerException.class).hasMessage("任务时限不能为空");
        verifyNoInteractions(members, resources, client);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0:401", "1:0", "1", "1:401:2", "-1:401", "1:0401",
            "9223372036854775808:401", "1:9223372036854775808", "1:401,invalid"})
    void malformedServerPermissionCannotSilentlyEnableCalls(String allowed) {
        assertThatThrownBy(() -> create(allowed)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        verifyNoInteractions(members, resources, client);
    }

    @Test
    void concurrentPermittedRequestsKeepPayloadAndOutcomeIndependent() throws Exception {
        var other = new EnterpriseMemberContext(2, 102, 2, null, "EMPLOYEE", 1, 1, 1);
        when(members.requireCurrent(other)).thenReturn(other);
        when(resources.requireSpace(other, 402L)).thenReturn(new KnowledgeSpace(402L, "另一虚构空间", 1));
        CountDownLatch entered = new CountDownLatch(2);
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!entered.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未会合");
            return output(call.<String>getArgument(1).contains(QUESTION) ? ALTERNATIVE : "入职工牌领取流程");
        });
        QueryRewriter rewriter = create("1:401,2:402");
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> rewriter.rewrite(member, 401L, QUESTION, TaskDeadline.start(Duration.ofSeconds(10))));
            var second = callers.submit(() -> rewriter.rewrite(other, 402L, "新来公司的工牌怎么领？", TaskDeadline.start(Duration.ofSeconds(10))));
            assertThat(first.get(3, TimeUnit.SECONDS).alternativeQuery()).isEqualTo(ALTERNATIVE);
            assertThat(second.get(3, TimeUnit.SECONDS).alternativeQuery()).isEqualTo("入职工牌领取流程");
            verify(members, times(2)).requireCurrent(member);
            verify(members, times(2)).requireCurrent(other);
            verify(resources, times(2)).requireSpace(member, 401L);
            verify(resources, times(2)).requireSpace(other, 402L);
            verify(client, times(2)).generate(anyString(), anyString(), eq(1000), any());
        } finally {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertUnavailable(QueryRewriter.Outcome outcome) {
        assertThat(outcome.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(outcome.alternativeQuery()).isEmpty();
        assertThat(outcome.message()).contains("语义扩展未完成", "原问题", "未自动重试")
                .doesNotContain("fake-private-provider-body", QUESTION, ALTERNATIVE);
    }

    private void respond(String value) {
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenReturn(output(value));
    }

    private String output(String value) {
        return json.createObjectNode().put("alternativeQuery", value).toString();
    }

    private QueryRewriter create(String allowed) {
        return new QueryRewriter(members, resources, model, allowed);
    }

    private TaskDeadline parent() {
        return TaskDeadline.start(Duration.ofSeconds(20), Clock.fixed(RECEIVED, ZoneOffset.UTC), ticks::get);
    }
}
