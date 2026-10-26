package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.config.JacksonConfig;
import com.ragqa.controller.EnterpriseAnswerController;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.ChatModel;
import com.ragqa.rag.chat.KnowledgeAnswerComposer;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实模板、JSON校验、引用解析及服务编排；仅底层模型、身份和检索使用替身，不证明模型语义能力。 */
class KnowledgeAnswerEvidenceContractTest {
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final ResponsesClient client = mock(ResponsesClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AuthenticatedUserPrincipal user = new AuthenticatedUserPrincipal(1, 1);
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final Evidence meal = evidence(1, "餐补制度", "加班餐补每日30元，申请需主管审批。");
    private final Evidence trainingA = evidence(8, "培训补贴甲", "培训补贴每人每月200元。");
    private final Evidence trainingB = evidence(9, "培训补贴乙", "培训补贴每人每月300元。");
    private KnowledgeAnswerService service;

    @BeforeEach
    void setup() {
        when(members.requireMember(user, 1L)).thenReturn(member);
        when(members.requireCurrent(member)).thenReturn(member);
        when(retriever.requireEvidence(eq(member), eq(401L), anyLong())).thenAnswer(call ->
                Map.of(1L, meal, 8L, trainingA, 9L, trainingB).get(call.<Long>getArgument(2)));
        service = create("1:401");
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void irrelevantNonemptyCandidatesCanStillReturnNoEvidence() throws Exception {
        candidates(trainingA, trainingB);
        response(part("宠物保险额度多少？", "NO_EVIDENCE", List.of(), List.of("仅有培训补贴资料，缺少宠物保险依据。")));
        Answer answer = ask("宠物保险额度多少？");
        assertThat(answer.status()).isEqualTo("NO_EVIDENCE");
        assertThat(answer.evidence()).containsExactly(trainingA, trainingB);
        assertThat(answer.parts().get(0).paragraphs()).isEmpty();
        assertThat(answer.parts().get(0).gaps()).containsExactly("仅有培训补贴资料，缺少宠物保险依据。");
        verifyOnce();
        verify(client).generate(anyString(), contains("宠物保险额度多少？"), eq(3000), any());
    }

    @Test
    void partialAnswerKeepsKnownFactAndMissingConditionSeparate() throws Exception {
        candidates(meal);
        response(part("加班两小时餐补多少、多久到账？", "PARTIAL",
                List.of(paragraph("餐补每日30元；是否满足时长条件及到账时间无法确定。", 1, "加班餐补每日30元")),
                List.of("缺少加班时长资格条件。", "缺少到账时间。")));
        Answer answer = ask("加班两小时餐补多少、多久到账？");
        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.parts().get(0).gaps()).hasSize(2);
        assertThat(answer.parts().get(0).paragraphs().get(0).citations().get(0).evidence()).isEqualTo(meal);
        verifyOnce();
    }

    @Test
    void threeQuestionsKeepOriginalOrderAndAggregateToPartial() throws Exception {
        candidates(meal);
        response(part("餐补多少？", "ANSWERED", List.of(paragraph("每日30元。", 1, "加班餐补每日30元")), List.of()),
                part("如何申请、多久到账？", "PARTIAL", List.of(paragraph("需主管审批。", 1, "申请需主管审批")), List.of("缺少到账时间。")),
                part("宠物保险呢？", "NO_EVIDENCE", List.of(), List.of("缺少宠物保险依据。")));
        Answer answer = ask("餐补多少？如何申请、多久到账？宠物保险呢？");
        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.parts()).extracting(Part::status).containsExactly("ANSWERED", "PARTIAL", "NO_EVIDENCE");
        assertThat(answer.parts()).extracting(Part::question).containsExactly("餐补多少？", "如何申请、多久到账？", "宠物保险呢？");
        verifyOnce();
    }

    @Test
    void conflictTakesPrecedenceAndKeepsBothVersionSnapshots() throws Exception {
        candidates(meal, trainingA, trainingB);
        response(part("餐补多少？", "ANSWERED", List.of(paragraph("每日30元。", 1, "加班餐补每日30元")), List.of()),
                part("培训补贴多少？", "CONFLICT", List.of(paragraph("甲条款200元。", 2, "培训补贴每人每月200元"),
                        paragraph("乙条款300元。", 3, "培训补贴每人每月300元")), List.of("需知识负责人核对适用条款，不能任选一个额度。")));
        Answer answer = ask("餐补多少？培训补贴多少？");
        assertThat(answer.status()).isEqualTo("CONFLICT");
        assertThat(answer.parts().get(1).paragraphs()).flatExtracting(Paragraph::citations)
                .extracting(Citation::evidence).containsExactly(trainingA, trainingB);
        verifyOnce();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "trailing", "null", "unknownSource", "inventedQuote", "noCitation",
        "noGap", "oneSourceConflict", "noEvidenceWithParagraph", "omittedQuestion", "html", "unknownField"})
    void invalidOutputBecomesVisibleFailureWithoutRetry(String mutation) throws Exception {
        candidates(meal);
        ObjectNode root = json.createObjectNode();
        ObjectNode item = part("餐补多少？", "ANSWERED", List.of(paragraph("每日30元。", 1, "加班餐补每日30元")), List.of());
        root.putArray("parts").add(item);
        ObjectNode paragraph = (ObjectNode) item.path("paragraphs").get(0);
        ObjectNode citation = (ObjectNode) paragraph.path("citations").get(0);
        switch (mutation) {
            case "unknownSource" -> citation.put("sourceId", 2);
            case "inventedQuote" -> citation.put("quote", "宠物保险每日300元");
            case "noCitation" -> paragraph.putArray("citations");
            case "noGap" -> item.put("status", "PARTIAL");
            case "oneSourceConflict" -> { item.put("status", "CONFLICT"); item.putArray("gaps").add("冲突"); }
            case "noEvidenceWithParagraph" -> { item.put("status", "NO_EVIDENCE"); item.putArray("gaps").add("缺依据"); }
            case "omittedQuestion" -> item.put("question", "餐补");
            case "html" -> paragraph.put("text", "<script>invalid</script>");
            case "unknownField" -> root.put("unexpected", "FAKE_PRIVATE_BODY");
            default -> { }
        }
        String output = switch (mutation) {
            case "duplicate" -> "{\"parts\":[],\"parts\":[]}";
            case "trailing" -> json.writeValueAsString(root) + " {}";
            case "null" -> null;
            default -> json.writeValueAsString(root);
        };
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenReturn(output);
        Answer answer = ask("餐补多少？");
        assertThat(answer.status()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(answer.message()).contains("未自动重试").doesNotContain("FAKE_PRIVATE_BODY");
        assertThat(answer.evidence()).containsExactly(meal);
        assertThat(answer.parts().get(0).paragraphs()).isEmpty();
        verifyOnce();
    }

    @Test
    void providerFailureDoesNotExposeBodyOrSilentlyRetry() {
        candidates(meal);
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenThrow(new BusinessException("FAKE_PRIVATE_BODY"));
        Answer answer = ask("餐补多少？");
        assertThat(answer.status()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(answer.toString()).doesNotContain("FAKE_PRIVATE_BODY");
        verifyOnce();
    }

    @Test
    void revocationAfterCompositionPreventsReturningEvenValidAnswer() throws Exception {
        candidates(meal);
        String output = json.writeValueAsString(Map.of("parts", List.of(part("餐补多少？", "ANSWERED",
                List.of(paragraph("每日30元。", 1, "加班餐补每日30元")), List.of()))));
        when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            when(retriever.requireEvidence(member, 401, 1)).thenThrow(new BusinessException(ResultCode.NOT_FOUND));
            return output;
        });
        assertThatThrownBy(() -> ask("餐补多少？")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
        verifyOnce();
    }

    @Test
    void defaultLocalModeDoesNotGenerateAnswerFromIrrelevantCandidates() {
        service.close(); service = create("");
        candidates(trainingA, trainingB);
        Answer answer = ask("宠物保险额度多少？");
        assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(answer.message()).contains("尚未生成或判定最终答案");
        assertThat(answer.parts().get(0).paragraphs()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void unsupportedConclusionIsHiddenWhenTheIndependentReviewRejectsIt() throws Exception {
        // 替身明确拒绝，验证拦截接线；不宣称真实模型已能正确判断全部不相干问题。
        candidates(meal);
        response(part("宠物保险额度多少？", "ANSWERED",
                List.of(paragraph("宠物保险每日30元。", 1, "加班餐补每日30元")), List.of()));
        when(client.generate(anyString(), anyString(), eq(1200), any())).thenAnswer(call -> {
            String input = call.getArgument(1);
            var payload = json.readTree(input.substring(input.indexOf('{')));
            return json.writeValueAsString(Map.of("snapshotId", payload.path("snapshotId").asText(), "verdicts",
                    List.of(Map.of("id", "1:0", "verdict", "UNSUPPORTED"))));
        });
        Answer answer = ask("宠物保险额度多少？");
        assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
        assertThat(answer.parts().get(0).paragraphs()).isEmpty();
        assertThat(meal.content()).doesNotContain("宠物保险");
        verifyOnce();
    }

    @Test
    void crossSpaceHttpRouteReturnsNineSourcesAndRealTemplateCompositionWithReviewedCitation() throws Exception {
        String question = "校园网密码忘了怎么重置？";
        String content = "忘记密码时通过官方入口核验预留联系方式后重置。";
        var spaces = new java.util.ArrayList<EnterpriseResources.KnowledgeSpace>();
        for (int index = 0; index < 5; index++) {
            long space = 401L + index;
            spaces.add(new EnterpriseResources.KnowledgeSpace(space, "校园服务" + index, 1));
            var sources = new java.util.ArrayList<Evidence>();
            for (int rank = 0; rank < 2; rank++) {
                Evidence source = evidence(100L + index * 2 + rank, "校园网密码重置指南", content);
                sources.add(source);
                when(retriever.requireEvidence(member, space, source.chunkId())).thenReturn(source);
            }
            when(retriever.retrieve(eq(member), eq(space), anyString(), any())).thenReturn(List.copyOf(sources));
        }
        var page = new Page<EnterpriseResources.KnowledgeSpace>(1, 500, spaces.size());
        page.setRecords(spaces);
        when(resources.listSpaces(member, 1L, 500L)).thenReturn(PageResult.of(page));
        service.close(); service = create("1:401,1:402,1:403,1:404,1:405");
        response(part(question, "ANSWERED", List.of(paragraph("通过官方入口核验预留联系方式后重置密码。", 9, content)), List.of()));
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        // 这里只替代身份参数，HTTP路由、真实模板、引文校验、复核和序列化均使用生产代码；安全链另由原测试验证。
        var mvc = MockMvcBuilders.standaloneSetup(new EnterpriseAnswerController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(AuthenticatedUserPrincipal.class);
                    }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory binder) { return user; }
                }).build();
        mvc.perform(post("/api/enterprises/1/answers").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("question", question))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("SYNTHETIC_MODEL"))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.evidence.length()").value(9))
                .andExpect(jsonPath("$.data.evidence[8].spaceId").isString())
                .andExpect(jsonPath("$.data.evidence[8].evidence.chunkId").isString())
                .andExpect(jsonPath("$.data.parts[0].paragraphs[0].citations[0].sourceId").value(9));
        verify(client, times(1)).generate(anyString(), contains(question), eq(3000), any());
        verify(client, times(1)).generate(anyString(), anyString(), eq(1200), any());
    }

    private KnowledgeAnswerService create(String spaces) {
        return new KnowledgeAnswerService(members, resources, retriever,
                new KnowledgeAnswerComposer(new ChatModel(client, new PromptRegistry(), json)), spaces);
    }

    private Answer ask(String question) { return service.answer(user, 1L, 401L, question); }
    private void candidates(Evidence... sources) {
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of(sources));
    }
    private void verifyOnce() { verify(client, times(1)).generate(anyString(), anyString(), eq(3000), any()); }
    private void response(ObjectNode... parts) throws Exception {
        when(client.generate(anyString(), anyString(), anyInt(), any()))
                .thenReturn(json.writeValueAsString(Map.of("parts", List.of(parts))));
        when(client.generate(anyString(), anyString(), eq(1200), any())).thenAnswer(call -> {
            String input = call.getArgument(1);
            var payload = json.readTree(input.substring(input.indexOf('{')));
            var output = json.createObjectNode().put("snapshotId", payload.path("snapshotId").asText());
            var verdicts = output.putArray("verdicts");
            for (var claim : payload.path("input").path("claims"))
                verdicts.addObject().put("id", claim.path("id").asText()).put("verdict", "SUPPORTED");
            return json.writeValueAsString(output);
        });
    }
    private ObjectNode part(String question, String status, List<ObjectNode> paragraphs, List<String> gaps) {
        ObjectNode node = json.createObjectNode().put("question", question).put("status", status);
        node.set("paragraphs", json.valueToTree(paragraphs));
        node.set("gaps", json.valueToTree(gaps));
        return node;
    }
    private ObjectNode paragraph(String text, int source, String quote) {
        ObjectNode node = json.createObjectNode().put("text", text);
        node.putArray("citations").addObject().put("sourceId", source).put("quote", quote);
        return node;
    }
    private static Evidence evidence(long id, String title, String content) {
        return new Evidence(id, id, id, 1, title, 0, content, 0, content.length(), 1, 1);
    }
}
