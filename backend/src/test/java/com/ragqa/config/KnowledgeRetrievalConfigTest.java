package com.ragqa.config;

import com.ragqa.common.ResultCode;
import com.ragqa.controller.KnowledgeAnswerController;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.domain.dto.KnowledgeAnswers.Part;
import com.ragqa.exception.BusinessException;
import com.ragqa.exception.GlobalExceptionHandler;
import com.ragqa.mapper.KnowledgeRetrievalMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.mapper.KnowledgeVectorPublicationMapper;
import com.ragqa.rag.chat.KnowledgeAnswerComposer;
import com.ragqa.rag.chat.KnowledgeScopeResolver;
import com.ragqa.rag.retrieval.*;
import com.ragqa.rag.retrieval.QdrantVectorClient.Hit;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import com.ragqa.service.KnowledgeAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实Spring装配、回答编排和MVC/统一异常契约；身份解析、SQL、Embedding及Qdrant均为替身。
 * 不包含真实Servlet Security、MySQL、向量服务或模型质量；真实安全链另跑EnterpriseSecurityHttpTest。
 */
class KnowledgeRetrievalConfigTest {
    private static final String PREFIX = "knowingorder.retrieval.hybrid.";
    private static final String PATH = "/api/enterprises/1/knowledge-spaces/401/answers";
    private static final String[] ENABLED = {PREFIX + "enabled=true", PREFIX + "similarity-threshold=0.5",
            PREFIX + "rrf-smoothing=60", "knowingorder.embedding.enabled=true", "knowingorder.qdrant.enabled=true",
            "knowingorder.embedding.model=synthetic:1"};
    private static final AuthenticatedUserPrincipal USER = new AuthenticatedUserPrincipal(1, 1);
    private static final EnterpriseMemberContext MEMBER = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private static final String TEXT = "虚构制度：餐补每日三十元。";
    private static final Evidence EVIDENCE = new Evidence(1001L, 501L, 701L, 1, "虚构制度", 0, TEXT, 0, TEXT.length(), 1, 1);
    private static final Vector VECTOR = new Vector("synthetic:1", List.of(3.0, 4.0));
    private static final LocalEmbeddingClient.ModelSnapshot MODEL = new LocalEmbeddingClient.ModelSnapshot(VECTOR.model(), "a".repeat(64), 2);
    private final KnowledgeRetrievalMapper evidence = mock(KnowledgeRetrievalMapper.class);
    private final KnowledgeVectorPublicationMapper publications = mock(KnowledgeVectorPublicationMapper.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final LocalEmbeddingClient embedding = mock(LocalEmbeddingClient.class);
    private final QdrantVectorClient vectors = mock(QdrantVectorClient.class);
    private final KnowledgeAnswerComposer composer = mock(KnowledgeAnswerComposer.class);
    private ApplicationContextRunner runner;

    @BeforeEach void setup() throws Exception {
        runner = new ApplicationContextRunner().withUserConfiguration(KnowledgeRetrievalConfig.class)
                .withBean("keywordKnowledgeRetriever", KeywordKnowledgeRetriever.class, () -> new KeywordKnowledgeRetriever(evidence, resources))
                .withBean(KnowledgeRetrievalMapper.class, () -> evidence)
                .withBean(KnowledgeVectorPublicationMapper.class, () -> publications)
                .withBean(EnterpriseResourceService.class, () -> resources)
                .withBean(EnterpriseMemberService.class, () -> members)
                .withBean(LocalEmbeddingClient.class, () -> embedding)
                .withBean(QdrantVectorClient.class, () -> vectors)
                .withBean(KnowledgeAnswerComposer.class, () -> composer)
                .withBean(com.ragqa.rag.retrieval.ReviewedQueryExpansion.class, () -> {
                    var expansion = mock(com.ragqa.rag.retrieval.ReviewedQueryExpansion.class);
                    when(expansion.expand(any(), anyLong(), anyString(), anyList(), any())).thenAnswer(call -> call.getArgument(3));
                    return expansion;
                })
                .withBean(KnowledgeScopeResolver.class, () -> mock(KnowledgeScopeResolver.class))
                .withBean(KnowledgeAnswerService.class);
        when(members.requireMember(USER, 1L)).thenReturn(MEMBER);
        when(members.requireCurrent(MEMBER)).thenReturn(MEMBER);
        when(composer.review(anyString(), anyList(), any())).thenAnswer(call -> call.getArgument(1));
        when(evidence.search(eq(MEMBER), eq(401L), anyLong(), anyList())).thenReturn(List.of(EVIDENCE));
        when(evidence.evidence(eq(MEMBER), eq(401L), anyLong(), eq(1001L))).thenReturn(EVIDENCE);
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TEXT.getBytes(StandardCharsets.UTF_8)));
        String generation = UUID.randomUUID().toString();
        var manifest = VectorIndexManifest.capture(new Index("ko_selection_test", MODEL.model(), MODEL.revision(), 2),
                new Scope(1, 401, 501, 701, generation), 7, "b".repeat(64), 1,
                List.of(new Point(1001, contentHash, VECTOR)));
        long now = System.currentTimeMillis();
        var job = new Stored(UUID.randomUUID().toString(), generation, 1, 401, 501, 701, 103, 3, 1, 7,
                "c".repeat(64), "b".repeat(64), "ko_selection_test", MODEL.model(), MODEL.revision(), 2,
                1, "VERIFIED", null, null, manifest.sha256(), 1, now - 3000, now + 60_000, now - 1000, null);
        when(publications.candidates(eq(MEMBER), eq(401L), anyLong())).thenReturn(List.of(job));
        when(publications.candidate(eq(MEMBER), eq(401L), eq(501L), anyLong())).thenReturn(job);
        when(publications.entries(job.id())).thenReturn(manifest.expected());
        when(embedding.snapshot(any())).thenReturn(MODEL);
        when(embedding.embed(eq(List.of("餐补多少")), any())).thenReturn(List.of(VECTOR));
        when(vectors.query(eq(manifest.index()), eq(manifest.scope()), eq(VECTOR), eq(.5), eq(24), any()))
                .thenReturn(List.of(new Hit(1001, contentHash, .9)));
        when(composer.compose(anyString(), anyList(), any())).thenReturn(List.of(new Part(1, "餐补多少", "ANSWERED", List.of(), List.of())));
    }

    @Test void absentSwitchKeepsSameKeywordBeanAndStartupMakesNoCalls() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KnowledgeRetriever.class)).isSameAs(context.getBean("keywordKnowledgeRetriever"));
            verifyNoInteractions(evidence, publications, resources, members, embedding, vectors, composer);
        });
    }

    @Test void disabledHybridIgnoresUnusedParametersAndRetainsLocalOnlyAnswer() {
        runner.withPropertyValues(PREFIX + "enabled=false", PREFIX + "similarity-threshold=NaN", PREFIX + "rrf-smoothing=invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var answer = context.getBean(KnowledgeAnswerService.class).answer(USER, 1L, 401L, "餐补多少");
                    assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE"); assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
                    assertThat(answer.parts().get(0).gaps()).containsExactly("请阅读下方原文；检索命中不等于答案充分。");
                    verifyNoInteractions(publications, embedding, vectors, composer);
                });
    }

    @Test void enabledStrategyInjectsHybridWithoutCallingServicesOnStartup() {
        runner.withPropertyValues(ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KnowledgeRetriever.class)).isInstanceOf(HybridKnowledgeRetriever.class);
            assertThat(context.getBeansOfType(KnowledgeRetriever.class)).hasSize(2);
            verifyNoInteractions(evidence, publications, resources, members, embedding, vectors, composer);
        });
    }

    @Test void realAnswerServiceUsesSelectedHybridButDoesNotGainExternalModelPermission() {
        runner.withPropertyValues(ENABLED).run(context -> {
            var service = context.getBean(KnowledgeAnswerService.class);
            assertThat(service.mode(USER, 1L, 401L)).isEqualTo("LOCAL_EVIDENCE");
            var answer = service.answer(USER, 1L, 401L, "餐补多少");
            assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE"); assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
            assertThat(answer.evidence()).containsExactly(EVIDENCE);
            assertThat(answer.parts().get(0).gaps()).containsExactly("请阅读下方原文；检索命中不等于答案充分。");
            verify(embedding, times(2)).snapshot(any()); verify(embedding).embed(eq(List.of("餐补多少")), any());
            verify(vectors).query(any(), any(), any(), eq(.5), eq(24), any()); verifyNoInteractions(composer);
        });
    }

    @Test void syntheticModelWhitelistStaysIndependentAndRequiresCurrentEvidence() {
        runner.withPropertyValues(ENABLED).withPropertyValues("knowingorder.knowledge.synthetic-model-spaces=1:401").run(context -> {
            var service = context.getBean(KnowledgeAnswerService.class);
            assertThat(service.mode(USER, 1L, 401L)).isEqualTo("SYNTHETIC_MODEL");
            var answer = service.answer(USER, 1L, 401L, "餐补多少");
            assertThat(answer.mode()).isEqualTo("SYNTHETIC_MODEL"); assertThat(answer.status()).isEqualTo("ANSWERED");
            verify(composer, times(1)).compose(eq("餐补多少"), eq(List.of(EVIDENCE)), any());
            verify(evidence, atLeast(3)).evidence(eq(MEMBER), eq(401L), anyLong(), eq(1001L));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", "yes", "1", "auto", "RAW_CONFIGURATION_VALUE"})
    void invalidSwitchCannotSilentlyDisableOrLeakItsValue(String value) {
        runner.withPropertyValues(PREFIX + "enabled=" + value).run(context -> failure(context, "检索相关开关"));
    }

    @ParameterizedTest @ValueSource(strings = {"knowingorder.embedding.enabled=false", "knowingorder.qdrant.enabled=false",
            "knowingorder.embedding.enabled=invalid", "knowingorder.qdrant.enabled=invalid", "knowingorder.embedding.model="})
    void enabledHybridRequiresBothClientsAndLocalModel(String property) {
        runner.withPropertyValues(ENABLED).withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed(); verifyNoInteractions(embedding, vectors, publications, evidence);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"similarity-threshold=", "similarity-threshold=NaN", "similarity-threshold=Infinity",
            "similarity-threshold=-1.01", "similarity-threshold=1.01", "similarity-threshold=RAW_CONFIGURATION_VALUE",
            "rrf-smoothing=", "rrf-smoothing=0", "rrf-smoothing=1001", "rrf-smoothing=1.5", "rrf-smoothing=2147483648"})
    void enabledHybridRequiresExplicitValidRankingParameters(String property) {
        runner.withPropertyValues(ENABLED).withPropertyValues(PREFIX + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure()).getMessage()).doesNotContain("RAW_CONFIGURATION_VALUE");
            verifyNoInteractions(embedding, vectors, publications, evidence);
        });
    }

    @Test void missingRankingPropertiesHaveNoAutomaticProductionDefault() {
        runner.withPropertyValues(PREFIX + "enabled=true", "knowingorder.embedding.enabled=true", "knowingorder.qdrant.enabled=true",
                "knowingorder.embedding.model=synthetic:1").run(context -> failure(context, "显式配置"));
    }

    @ParameterizedTest @ValueSource(strings = {"-1:1", "1:1000"})
    void exactParameterBoundariesAreAcceptedWithoutNetwork(String pair) {
        String[] fields = pair.split(":");
        runner.withPropertyValues(ENABLED).withPropertyValues(PREFIX + "similarity-threshold=" + fields[0], PREFIX + "rrf-smoothing=" + fields[1])
                .run(context -> { assertThat(context).hasNotFailed(); verifyNoInteractions(embedding, vectors); });
    }

    @Test void missingIndexReturnsUserVisibleBusinessFailureAndDoesNotFallBackToKeyword() {
        when(publications.candidates(eq(MEMBER), eq(401L), anyLong())).thenReturn(List.of());
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"餐补多少\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尚无可用向量索引")))
                    .andExpect(jsonPath("$.data").doesNotExist());
            verifyNoInteractions(evidence, embedding, vectors, composer);
        });
    }

    @Test void unexpectedVectorFailureStays503WithoutRawPayloadRetriesOrFakeNoEvidenceAnswer() {
        when(vectors.query(any(), any(), any(), anyDouble(), anyInt(), any())).thenThrow(new IllegalStateException("RAW_UPSTREAM_CONTENT"));
        runner.withPropertyValues(ENABLED).run(context -> {
            String body = mvc(context).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"餐补多少\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("RAW_UPSTREAM", "NO_EVIDENCE", "EVIDENCE_ONLY", "manifest", "generation");
            verify(vectors, times(1)).query(any(), any(), any(), anyDouble(), anyInt(), any()); verifyNoInteractions(composer);
        });
    }

    @Test void deniedSpaceStopsBeforeAnyEmbeddingQueryEvenWhenHybridIsEnabled() {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问")).when(resources).requireSpace(MEMBER, 401L);
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"餐补多少\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(404));
            verifyNoInteractions(publications, evidence, embedding, vectors, composer);
        });
    }

    @Test void bothBranchesEmptyRemainNoEvidenceAndNeverCallComposer() {
        when(evidence.search(eq(MEMBER), eq(401L), anyLong(), anyList())).thenReturn(List.of());
        when(vectors.query(any(), any(), any(), anyDouble(), anyInt(), any())).thenReturn(List.of());
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"餐补多少\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.mode").value("LOCAL_EVIDENCE"))
                    .andExpect(jsonPath("$.data.status").value("NO_EVIDENCE"))
                    .andExpect(jsonPath("$.data.parts[0].gaps[0]").value("当前授权空间的检索没有命中有效依据。"));
            verifyNoInteractions(composer);
        });
    }

    @Test void existingModeHttpContractStaysCompatibleWithFrontendParser() {
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(get(PATH + "/mode")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("LOCAL_EVIDENCE"));
            verifyNoInteractions(publications, evidence, embedding, vectors, composer);
        });
    }

    @Test void oversizedQuestionIsRejectedAtHttpBoundaryBeforeRetrieval() {
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + "问".repeat(1201) + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            verifyNoInteractions(publications, evidence, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retrievalModeReportsActualBeanRatherThanChangedEnvironmentWithoutProbing(boolean hybrid) {
        var selected = hybrid ? runner.withPropertyValues(ENABLED) : runner;
        selected.run(context -> {
            // 启动后修改属性来源不能伪造已装配策略；本功能没有热切换。
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("late-test-change",
                    Map.of(PREFIX + "enabled", Boolean.toString(!hybrid))));
            mvc(context).perform(get(PATH + "/retrieval-mode").param("mode", hybrid ? "KEYWORD" : "HYBRID"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value(hybrid ? "HYBRID" : "KEYWORD"));
            verify(members).requireMember(USER, 1L); verify(resources).requireSpace(MEMBER, 401L);
            verifyNoInteractions(evidence, publications, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retrievalModeStaysIndependentOfSyntheticModelPermission(boolean hybrid) {
        var selected = hybrid ? runner.withPropertyValues(ENABLED) : runner;
        selected.withPropertyValues("knowingorder.knowledge.synthetic-model-spaces=1:401").run(context -> {
            var web = mvc(context);
            web.perform(get(PATH + "/mode")).andExpect(jsonPath("$.data").value("SYNTHETIC_MODEL"));
            web.perform(get(PATH + "/retrieval-mode"))
                    .andExpect(jsonPath("$.data").value(hybrid ? "HYBRID" : "KEYWORD"));
            verifyNoInteractions(evidence, publications, embedding, vectors, composer);
        });
    }

    @Test void retrievalModeIsNotAReadinessProbeAndRechecksSpaceGrantOnEachRead() {
        when(publications.candidates(eq(MEMBER), eq(401L), anyLong())).thenReturn(List.of());
        runner.withPropertyValues(ENABLED).run(context -> {
            var web = mvc(context);
            web.perform(get(PATH + "/retrieval-mode")).andExpect(jsonPath("$.data").value("HYBRID"));
            doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问"))
                    .when(resources).requireSpace(MEMBER, 401L);
            web.perform(get(PATH + "/retrieval-mode")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").doesNotExist());
            verify(members, times(2)).requireMember(USER, 1L);
            verify(resources, times(2)).requireSpace(MEMBER, 401L);
            verifyNoInteractions(evidence, publications, embedding, vectors, composer);
        });
    }

    @Test void retrievalModeRejectsCrossEnterpriseMemberBeforeReadingSpaceOrStrategy() {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问"))
                .when(members).requireMember(USER, 2L);
        runner.withPropertyValues(ENABLED).run(context -> {
            mvc(context).perform(get(PATH.replace("enterprises/1/", "enterprises/2/") + "/retrieval-mode"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.data").doesNotExist());
            verifyNoInteractions(resources, evidence, publications, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void retrievalModeRejectsNonpositiveSpaceBeforeAnyAuthorizationRead(long space) {
        runner.run(context -> {
            mvc(context).perform(get(PATH.replace("spaces/401/", "spaces/" + space + "/") + "/retrieval-mode"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
            verifyNoInteractions(members, resources, evidence, publications, embedding, vectors, composer);
        });
    }

    @Test void retrievalModeRejectsNullSpaceInServiceBoundary() {
        runner.run(context -> {
            assertThatThrownBy(() -> context.getBean(KnowledgeAnswerService.class).retrievalMode(USER, 1L, null))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
            verifyNoInteractions(members, resources, evidence, publications, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"9223372036854775808", "abc", "1 OR 1=1"})
    void retrievalModeRejectsMalformedSpaceBeforeService(String space) {
        runner.run(context -> {
            mvc(context).perform(get(PATH.replace("spaces/401/", "spaces/" + space + "/") + "/retrieval-mode"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            verifyNoInteractions(members, resources, evidence, publications, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void retrievalModeHasNoMutationEndpoint(String method) {
        runner.run(context -> {
            mvc(context).perform(request(org.springframework.http.HttpMethod.valueOf(method), PATH + "/retrieval-mode"))
                    .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(405));
            verifyNoInteractions(members, resources, evidence, publications, embedding, vectors, composer);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retrievalModeRecognizesSpringJdkProxyWithoutCallingRetriever(boolean hybrid) {
        var selected = hybrid ? runner.withPropertyValues(ENABLED) : runner;
        selected.run(context -> {
            var factory = new ProxyFactory(context.getBean(KnowledgeRetriever.class));
            factory.setInterfaces(KnowledgeRetriever.class);
            factory.setProxyTargetClass(false);
            var proxied = (KnowledgeRetriever) factory.getProxy();
            assertThat(AopUtils.isJdkDynamicProxy(proxied)).isTrue();
            var service = new KnowledgeAnswerService(members, resources, proxied, composer, "");
            try {
                mvc(service).perform(get(PATH + "/retrieval-mode"))
                        .andExpect(jsonPath("$.data").value(hybrid ? "HYBRID" : "KEYWORD"));
                verifyNoInteractions(evidence, publications, embedding, vectors, composer);
            } finally { service.close(); }
        });
    }

    @Test void unknownRetrieverFailsExplicitlyAfterAuthorizationAndDoesNotExposeItsClass() throws Exception {
        var unknown = mock(KnowledgeRetriever.class);
        var service = new KnowledgeAnswerService(members, resources, unknown, composer, "");
        try {
            String body = mvc(service).perform(get(PATH + "/retrieval-mode"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("KeywordKnowledgeRetriever", "HybridKnowledgeRetriever", "Mockito", "generation");
            verify(members).requireMember(USER, 1L); verify(resources).requireSpace(MEMBER, 401L);
            verifyNoInteractions(unknown, evidence, publications, embedding, vectors, composer);
        } finally { service.close(); }
    }

    private static MockMvc mvc(AssertableApplicationContext context) {
        return mvc(context.getBean(KnowledgeAnswerService.class));
    }

    private static MockMvc mvc(KnowledgeAnswerService service) {
        return MockMvcBuilders.standaloneSetup(new KnowledgeAnswerController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.hasParameterAnnotation(AuthenticationPrincipal.class); }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
                            WebDataBinderFactory factory) { return USER; }
                }).build();
    }

    private void failure(AssertableApplicationContext context, String message) {
        assertThat(context).hasFailed();
        Throwable cause = root(context.getStartupFailure());
        assertThat(cause).isInstanceOf(IllegalArgumentException.class);
        assertThat(cause.getMessage()).contains(message).doesNotContain("RAW_CONFIGURATION_VALUE");
        verifyNoInteractions(embedding, vectors, publications, evidence);
    }

    private static Throwable root(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
