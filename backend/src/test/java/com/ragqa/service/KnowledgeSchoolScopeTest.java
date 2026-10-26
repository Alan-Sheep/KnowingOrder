package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.ContextQuestion;
import com.ragqa.rag.chat.*;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际服务、模板、严格解析与引用校验；供应商和检索替身不证明语义识别质量。 */
class KnowledgeSchoolScopeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResponsesClient client = mock(ResponsesClient.class);
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(1, 1);
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, null, "EMPLOYEE", 1, 1, 1);
    private final Evidence evidence = new Evidence(1L, 1L, 1L, 1, "虚构奖学金制度", 0,
            "奖学金每人1000元。", 0, 11, 1, 1);
    private final ChatModel model = new ChatModel(client, new PromptRegistry(), json);
    private KnowledgeAnswerService service;
    private List<String> questions = List.of();
    private List<String> labels = List.of();
    private String mutation = "";
    private boolean revoke;
    private boolean revokeAfterAnswer;
    private final List<ObjectNode> payloads = new ArrayList<>();
    private int classifications;
    private int generations;

    @BeforeEach
    void setup() {
        when(members.requireMember(user, 1L)).thenReturn(member);
        when(members.requireCurrent(member)).thenReturn(member);
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of(evidence));
        when(retriever.requireEvidence(member, 401, 1)).thenReturn(evidence);
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            String input = call.getArgument(1);
            ObjectNode payload = (ObjectNode) json.readTree(input.substring(input.indexOf('{')));
            if (call.<Integer>getArgument(2) == 1200) {
                var review = json.createObjectNode().put("snapshotId", payload.path("snapshotId").asText());
                var verdicts = review.putArray("verdicts");
                for (var claim : payload.path("input").path("claims"))
                    verdicts.addObject().put("id", claim.path("id").asText()).put("verdict", "SUPPORTED");
                return json.writeValueAsString(review);
            }
            payloads.add(payload);
            TaskDeadline budget = call.getArgument(3);
            if (call.<Integer>getArgument(2) == 2000) {
                classifications++;
                assertThat(budget.remaining()).isLessThanOrEqualTo(Duration.ofSeconds(5));
                assertThat(payload.has("memberId")).isFalse();
                assertThat(payload.has("sources")).isFalse();
                if (revoke) when(members.requireCurrent(member)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
                if (mutation.equals("timeout")) throw new BusinessException(ResultCode.PROCESS_TIMEOUT);
                if (mutation.equals("empty")) return "";
                if (mutation.equals("null")) return "null";
                ObjectNode output = json.createObjectNode().put("snapshotId", payload.path("snapshotId").asText());
                var parts = output.putArray("parts");
                for (int i = 0; i < questions.size(); i++)
                    parts.addObject().put("question", questions.get(i)).put("scope", labels.get(i));
                switch (mutation) {
                    case "snapshot" -> output.put("snapshotId", "other");
                    case "extra" -> output.put("debug", "禁止额外字段");
                    default -> { }
                }
                if (mutation.equals("coverage")) ((ObjectNode) parts.get(0)).put("question", "偷换问题");
                String body = json.writeValueAsString(output);
                return mutation.equals("trailing") ? body + "{}" : mutation.equals("duplicate")
                        ? body.replace("\"parts\":", "\"parts\":[],\"parts\":") : body;
            }
            generations++;
            if (revokeAfterAnswer) when(members.requireCurrent(member)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
            var output = json.createObjectNode();
            var parts = output.putArray("parts");
            for (var question : payload.path("questions")) {
                String value = question.asText();
                ObjectNode part = parts.addObject().put("question", mutation.equals("fixed") ? value + "！" : value);
                if (value.contains("到账")) {
                    part.put("status", "NO_EVIDENCE"); part.putArray("paragraphs");
                    part.putArray("gaps").add("资料没有到账时间。");
                } else {
                    part.put("status", "ANSWERED"); part.putArray("gaps");
                    part.putArray("paragraphs").addObject().put("text", "每人1000元。")
                            .putArray("citations").addObject().put("sourceId", 1).put("quote", "奖学金每人1000元");
                }
            }
            return json.writeValueAsString(output);
        });
        create("1", "1:401");
    }

    private void create(String enterprises, String spaces) {
        if (service != null) service.close();
        service = new KnowledgeAnswerService(members, resources, retriever, new KnowledgeAnswerComposer(model), spaces,
                new KnowledgeScopeResolver(model, enterprises));
    }

    @AfterEach void close() { service.close(); }
    private Answer ask() { return service.answer(user, 1L, 401L, String.join("", questions)); }
    private void scenario(List<String> fragments, String... scopes) { questions = fragments; labels = List.of(scopes); }

    @Test void outsideNeverRetrievesOrGenerates() {
        scenario(List.of("宠物保险怎么买？"), "OUT_OF_SCOPE");
        Answer result = ask();
        assertThat(result.status()).isEqualTo("OUT_OF_SCOPE");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.parts().get(0).gaps().get(0)).contains("不会自动发送");
        assertThat(classifications).isEqualTo(1); assertThat(generations).isZero();
        verifyNoInteractions(retriever);
    }

    @Test void mixedThreePreserveOrderAndUseOneGeneration() {
        scenario(List.of("奖学金多少？", "宠物保险怎么买？", "多久到账？"), "IN_SCOPE", "OUT_OF_SCOPE", "IN_SCOPE");
        Answer result = ask();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.parts()).extracting(Part::question).containsExactlyElementsOf(questions);
        assertThat(result.parts()).extracting(Part::status).containsExactly("ANSWERED", "OUT_OF_SCOPE", "NO_EVIDENCE");
        assertThat(result.parts()).extracting(Part::id).containsExactly(1, 2, 3);
        assertThat(result.parts().get(0).paragraphs().get(0).citations().get(0).evidence()).isEqualTo(evidence);
        verify(retriever).retrieve(eq(member), eq(401L), eq("奖学金多少？多久到账？"), any());
        assertThat(classifications).isEqualTo(1); assertThat(generations).isEqualTo(1);
    }

    @Test void schoolPetInsuranceCourseIsNotAForbiddenKeyword() {
        scenario(List.of("本校开设宠物保险课程吗？"), "IN_SCOPE");
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of());
        assertThat(ask().status()).isEqualTo("NO_EVIDENCE");
        verify(retriever).retrieve(eq(member), eq(401L), eq(questions.get(0)), any());
        assertThat(generations).isZero();
    }

    @Test void missingEvidenceRemainsInsideAndFansOutToAllInsideQuestions() {
        scenario(List.of("奖学金多少？", "多久到账？", "宠物保险怎么买？"), "IN_SCOPE", "IN_SCOPE", "OUT_OF_SCOPE");
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of());
        Answer result = ask();
        assertThat(result.status()).isEqualTo("NO_EVIDENCE");
        assertThat(result.parts()).extracting(Part::status).containsExactly("NO_EVIDENCE", "NO_EVIDENCE", "OUT_OF_SCOPE");
        assertThat(generations).isZero();
    }

    @Test void unknownDoesNotBecomeOutsideOrMissingEvidence() {
        scenario(List.of("这个能办吗？"), "UNKNOWN");
        assertThat(ask().status()).isEqualTo("SCOPE_UNKNOWN");
        verifyNoInteractions(retriever); assertThat(generations).isZero();
    }

    @Test void outsideAndUnknownTogetherMustNotClaimAnyQuestionWasAnswered() {
        scenario(List.of("宠物保险呢？", "那个呢？"), "OUT_OF_SCOPE", "UNKNOWN");
        Answer result = ask();
        assertThat(result.status()).isEqualTo("SCOPE_UNKNOWN");
        assertThat(result.parts()).extracting(Part::status).containsExactly("OUT_OF_SCOPE", "SCOPE_UNKNOWN");
        verifyNoInteractions(retriever); assertThat(generations).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "empty", "null", "snapshot", "coverage", "extra", "trailing", "duplicate", "label"})
    void failedClassificationIsVisibleAndNotRetried(String fault) {
        mutation = fault; scenario(List.of("奖学金多少？"), fault.equals("label") ? "SCHOOL" : "IN_SCOPE");
        Answer result = ask();
        assertThat(result.status()).isEqualTo("SCOPE_UNAVAILABLE");
        assertThat(result.parts().get(0).paragraphs()).isEmpty();
        assertThat(classifications).isEqualTo(1); assertThat(generations).isZero();
        verifyNoInteractions(retriever);
    }

    @Test void localSpaceSendsNothingToClassifier() {
        create("1", ""); scenario(List.of("奖学金多少？"), "IN_SCOPE");
        Answer result = ask();
        assertThat(result.mode()).isEqualTo("LOCAL_EVIDENCE");
        assertThat(result.status()).isEqualTo("SCOPE_UNAVAILABLE");
        verifyNoInteractions(client, retriever);
    }

    @Test void unconfiguredEnterpriseKeepsOriginalLocalPath() {
        create("2", ""); scenario(List.of("奖学金多少？"), "IN_SCOPE");
        assertThat(ask().status()).isEqualTo("EVIDENCE_ONLY");
        verifyNoInteractions(client);
    }

    @Test void unauthenticatedOrUnauthorizedSpaceNeverCallsModel() {
        scenario(List.of("奖学金多少？"), "IN_SCOPE");
        doThrow(new BusinessException(ResultCode.NOT_FOUND)).when(resources).requireSpace(member, 401L);
        assertThatThrownBy(this::ask).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client, retriever);
    }

    @Test void revocationDuringClassificationStopsBeforeRetrieval() {
        revoke = true; scenario(List.of("奖学金多少？"), "IN_SCOPE");
        assertThatThrownBy(this::ask).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
        verifyNoInteractions(retriever); assertThat(generations).isZero();
    }

    @Test void changedFixedBoundariesFailWithoutRetry() {
        mutation = "fixed"; scenario(List.of("奖学金多少？", "宠物保险呢？"), "IN_SCOPE", "OUT_OF_SCOPE");
        assertThat(ask().parts()).extracting(Part::status).containsExactly("MODEL_UNAVAILABLE", "OUT_OF_SCOPE");
        assertThat(classifications).isEqualTo(1); assertThat(generations).isEqualTo(1);
    }

    @Test void exhaustedParentBudgetCannotBeRenewedByScopeStage() {
        scenario(List.of("奖学金多少？"), "IN_SCOPE");
        TaskDeadline expired = TaskDeadline.start(Duration.ofNanos(1));
        assertThatThrownBy(() -> service.answerWithin(user, 1L, 401L, questions.get(0), expired))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(client, retriever);
    }

    @Test void unknownPartDoesNotBlockKnownInsidePart() {
        scenario(List.of("奖学金多少？", "那个呢？"), "IN_SCOPE", "UNKNOWN");
        assertThat(ask().parts()).extracting(Part::status).containsExactly("ANSWERED", "SCOPE_UNKNOWN");
        assertThat(generations).isEqualTo(1);
    }

    @Test void threeInsideQuestionsWithoutEvidenceRemainThreeSeparateGaps() {
        scenario(List.of("奖学金多少？", "多久到账？", "怎么申请？"), "IN_SCOPE", "IN_SCOPE", "IN_SCOPE");
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of());
        Answer result = ask();
        assertThat(result.status()).isEqualTo("NO_EVIDENCE");
        assertThat(result.parts()).extracting(Part::question).containsExactlyElementsOf(questions);
        assertThat(result.parts()).allSatisfy(part -> assertThat(part.paragraphs()).isEmpty());
        assertThat(generations).isZero();
    }

    @Test void trustedAntecedentIsSeparateInBothCallsAndOnlyRetrievesWithinAuthorizedSpace() {
        scenario(List.of("多久到账？"), "IN_SCOPE");
        var context = new FollowUpResolver.Outcome(FollowUpResolver.Status.FOLLOW_UP,
                new ContextQuestion("11111111-1111-4111-8111-111111111111", "奖学金多少？", 1000), "关联校内话题。");
        Answer result = service.answerWithin(user, 1L, 401L, questions.get(0), TaskDeadline.start(Duration.ofSeconds(30)), context);
        assertThat(result.status()).isEqualTo("NO_EVIDENCE");
        assertThat(payloads).hasSize(2).allSatisfy(payload ->
                assertThat(payload.path("antecedentQuestion").asText()).isEqualTo("奖学金多少？"));
        verify(retriever).retrieve(eq(member), eq(401L), eq("多久到账？"), any());
        verify(retriever).retrieve(eq(member), eq(401L), eq("奖学金多少？"), any());
    }

    @Test void unresolvedContextIsNotSentAndDoesNotGenerateDefiniteConclusion() {
        scenario(List.of("多久到账？"), "IN_SCOPE");
        Answer result = service.answerWithin(user, 1L, 401L, questions.get(0), TaskDeadline.start(Duration.ofSeconds(30)),
                FollowUpResolver.fallback(FollowUpResolver.Status.UNRESOLVED));
        assertThat(result.status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(payloads).hasSize(1).allSatisfy(payload -> assertThat(payload.has("antecedentQuestion")).isFalse());
        assertThat(generations).isZero();
    }

    @Test void revokedDuringAnswerCannotReturnEvenValidScopeAndCitations() {
        revokeAfterAnswer = true; scenario(List.of("奖学金多少？"), "IN_SCOPE");
        assertThatThrownBy(this::ask).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
        assertThat(generations).isEqualTo(1);
    }

    @Test void schoolProfileIsBoundByServerPropertyAndDefaultsToDisabled() {
        var runner = new ApplicationContextRunner().withBean(ChatModel.class, () -> model).withBean(KnowledgeScopeResolver.class);
        runner.run(context -> assertThat(context.getBean(KnowledgeScopeResolver.class).applies(1)).isFalse());
        runner.withPropertyValues("knowingorder.knowledge.school-enterprises=1,2").run(context -> {
            var resolver = context.getBean(KnowledgeScopeResolver.class);
            assertThat(resolver.applies(1)).isTrue(); assertThat(resolver.applies(2)).isTrue();
            assertThat(resolver.applies(3)).isFalse();
        });
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "school", "9223372036854775808"})
    void invalidOrganizationConfigurationFailsAtStartup(String value) {
        assertThatThrownBy(() -> new KnowledgeScopeResolver(model, value)).isInstanceOf(IllegalArgumentException.class);
    }
}
