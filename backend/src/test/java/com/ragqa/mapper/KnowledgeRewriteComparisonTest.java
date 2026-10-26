package com.ragqa.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.ChatModel;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.rag.retrieval.KnowledgeRetrievalExperiment;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.rag.retrieval.QueryRewriter;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实授权服务、H2检索与实验编排；仅模型传输为手写输出替身，不测模型或生成答案。 */
class KnowledgeRewriteComparisonTest {
    private final ObjectMapper json = new ObjectMapper();

    record Lane(List<Long> retrievedIds, List<Long> missingTargetIds, List<Long> unannotatedIds) { }
    record Measurement(String id, String group, String variant, String question, String fixtureAlternative,
            String expectedEvidenceState, String gap, String unsafeReason, List<Long> targetEvidenceIds,
            String rewriteStatus, Lane baseline, Lane supplemental, Lane preview, List<Long> addedIds,
            List<Long> targetsLostAfterSupplement, List<Map<String, Object>> origins,
            Map<String, Integer> exclusions, int substituteCalls, int errorCode, String failureType,
            List<String> safetyErrors) { }

    @Test
    @Timeout(90)
    void compareSixteenReferencesAndEightUnsafeFixturesWithoutClaimingModelQuality() throws Exception {
        byte[] corpusBytes = resource("/eval/knowledge-v1.json");
        byte[] annotationBytes = resource("/eval/knowledge-rewrite-v1.json");
        var corpus = json.readValue(corpusBytes, KnowledgeGoldenSetTest.Corpus.class);
        JsonNode annotations = json.readTree(annotationBytes);
        assertThat(hash(corpusBytes)).isEqualTo(annotations.path("corpusSha256").asText());
        assertThat(hash(annotationBytes)).isEqualTo("2124a69416da884394c1b9e94deb5075d7082836446df5e8367c1fccee05e5f6");
        assertThat(corpus.documents()).hasSize(20);
        assertThat(annotations.path("cases").size()).isEqualTo(16);
        var rows = new ArrayList<Measurement>();
        for (JsonNode sample : annotations.path("cases")) {
            rows.add(evaluate(corpus.documents(), sample, false));
            if (sample.path("group").asText().equals("BOUNDARY"))
                rows.add(evaluate(corpus.documents(), sample, true));
        }
        List<Measurement> references = rows.stream().filter(row -> row.variant().equals("REFERENCE_FIXTURE")).toList();
        List<Measurement> unsafe = rows.stream().filter(row -> row.variant().equals("UNSAFE_FIXTURE")).toList();
        Map<String, Object> metrics = Map.of("references", summarize(references),
                "synonymReferences", summarize(references.stream().filter(row -> row.group().equals("SYNONYM")).toList()),
                "unsafeFixtures", Map.of("cases", unsafe.size(),
                        "acceptedByLexicalContract", unsafe.stream().filter(row -> row.rewriteStatus().equals("CANDIDATE")).count(),
                        "acceptedWithSupplementalEvidence", unsafe.stream().filter(row -> row.rewriteStatus().equals("CANDIDATE")
                                && !row.supplemental().retrievedIds().isEmpty()).count(),
                        "casesWithAddedEvidence", unsafe.stream().filter(row -> !row.addedIds().isEmpty()).count()));
        Instant now = Instant.now();
        Path report = Path.of("E:/Codex/test-artifacts/knowledge-golden-v1/rewrite-comparison-"
                + now.toString().replace(':', '-') + ".json");
        var artifact = new LinkedHashMap<String, Object>();
        artifact.put("evaluationMode", "H2_RETRIEVAL_WITH_HANDWRITTEN_MODEL_SUBSTITUTE");
        artifact.put("datasetVersion", annotations.path("version").asText());
        artifact.put("corpusSha256", hash(corpusBytes));
        artifact.put("annotationSha256", hash(annotationBytes));
        artifact.put("sourceSha256", sourceHashes());
        artifact.put("evaluatedAt", now.toString());
        artifact.put("realModelCalls", 0);
        artifact.put("substituteCalls", rows.stream().mapToInt(Measurement::substituteCalls).sum());
        artifact.put("answerQuality", "NOT_RUN");
        artifact.put("productionAdmission", "BLOCKED_KNOWN_SEMANTIC_DRIFT");
        artifact.put("metrics", metrics);
        artifact.put("cases", rows);
        artifact.put("limitations", "16条参考句和8条错误句均为手写替身，不是模型输出；只测给定表达的授权检索。"
                + "未标注候选不自动等于无关，不能计算答案正确率或语义精确率；不代表MySQL、真实API、性能或生产验收。");
        Files.createDirectories(report.getParent());
        // 保存全部失败与漏检后再断言，CREATE_NEW防止复跑覆盖旧证据。
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact)
                .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("人工检索表达离线对照：" + json.writeValueAsString(metrics));
        System.out.println("逐题报告：" + report.toAbsolutePath());
        assertThat(references).hasSize(16);
        assertThat(unsafe).hasSize(8);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.errorCode()).as("%s/%s：%s", row.id(), row.variant(), row.failureType()).isZero();
            assertThat(row.safetyErrors()).as("%s/%s安全检查", row.id(), row.variant()).isEmpty();
            assertThat(row.substituteCalls()).isEqualTo(1);
        });
    }

    private Measurement evaluate(List<KnowledgeGoldenSetTest.Document> documents, JsonNode sample,
            boolean unsafe) {
        List<String> safety = new ArrayList<>();
        List<Long> targets = new ArrayList<>();
        sample.path("expectedEvidenceIds").forEach(id -> targets.add(id.asLong()));
        String question = sample.path("question").asText();
        String alternative = sample.path(unsafe ? "unsafeAlternative" : "referenceAlternative").asText();
        long spaceId = sample.path("spaceId").asLong();
        var calls = new AtomicInteger();
        List<Evidence> baseline = List.of(), supplemental = List.of(), preview = List.of();
        List<Map<String, Object>> origins = List.of();
        Map<String, Integer> exclusions = Map.of();
        String status = "NOT_COMPLETED", failure = "";
        int error = 0;
        try (var db = new EnterpriseDatabase()) {
            db.seed();
            db.factory.getConfiguration().addMapper(KnowledgeRetrievalMapper.class);
            documents.forEach(document -> KnowledgeGoldenSetTest.seed(db, document));
            var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(db.session.getMapper(SysUserMapper.class)),
                    db.session.getMapper(EnterpriseDirectoryMapper.class));
            var resources = new EnterpriseResourceServiceImpl(members, db.session.getMapper(EnterpriseResourceMapper.class));
            var retriever = new RecordingRetriever(new KeywordKnowledgeRetriever(
                    db.session.getMapper(KnowledgeRetrievalMapper.class), resources));
            var client = mock(ResponsesClient.class);
            when(client.generate(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
                calls.incrementAndGet();
                String input = call.getArgument(1);
                if (!input.contains(json.writeValueAsString(Map.of("originalQuestion", question)))
                        || input.contains("expectedEvidenceIds") || input.contains("referenceAlternative")
                        || input.contains("unsafeReason")) safety.add("模型输入不是仅原问题的固定载荷");
                return json.createObjectNode().put("alternativeQuery", alternative).toString();
            });
            var rewriter = new QueryRewriter(members, resources,
                    new ChatModel(client, new PromptRegistry(), json), "1:401");
            var experiment = new KnowledgeRetrievalExperiment(members, resources, retriever, rewriter);
            // 模拟测试登录主体，再经真实服务取得可信成员；不声明验证了HTTP登录。
            var member = members.requireMember(new AuthenticatedUserPrincipal(1, 1), 1L);
            var result = experiment.run(member, spaceId, question, TaskDeadline.start(Duration.ofSeconds(30)));
            baseline = result.preview().baseline();
            supplemental = retriever.supplemental;
            preview = result.preview().candidates().stream().map(candidate -> candidate.evidence()).toList();
            origins = result.preview().candidates().stream().map(candidate -> Map.<String, Object>of(
                    "evidenceId", candidate.evidence().chunkId() - 3000, "origin", candidate.origin().name())).toList();
            exclusions = Map.of("duplicate", result.preview().duplicateExcluded(),
                    "overlap", result.preview().overlapExcluded(), "capacity", result.preview().capacityExcluded());
            status = result.rewrite().status().name();
            String expected = unsafe ? (sample.path("unsafeContractOutcome").asText().equals("ACCEPTED")
                    ? "CANDIDATE" : "UNAVAILABLE") : alternative.equals(question.strip()) ? "UNCHANGED" : "CANDIDATE";
            if (!status.equals(expected)) safety.add("改写状态与已冻结的手写输出预期不符");
            if (retriever.calls != (status.equals("CANDIDATE") ? 2 : 1)) safety.add("非候选补检或额外检索");
            if (!baseline.equals(retriever.original)) safety.add("原始基线被改动");
            for (List<Evidence> lane : List.of(baseline, supplemental, preview)) {
                if (lane.size() > 6 || lane.stream().map(Evidence::chunkId).distinct().count() != lane.size())
                    safety.add("数量或去重边界异常");
                for (Evidence item : lane) {
                    var fixture = documents.stream().filter(doc -> doc.id() == item.chunkId() - 3000).findFirst();
                    if (fixture.isEmpty() || !fixture.get().state().equals("LIVE") || fixture.get().spaceId() != spaceId
                            || !fixture.get().content().equals(item.content()) || item.versionId() != fixture.get().id() + 2000
                            || item.documentId() != fixture.get().id() + 1000) safety.add("检索返回越界或非当前有效的资料");
                }
            }
        } catch (Exception exception) {
            // 故障仍记入分母；不保存可能带凭据/正文的异常消息。
            error = exception instanceof BusinessException business ? business.getCode() : 500;
            failure = exception.getClass().getSimpleName();
        }
        Lane base = lane(baseline, targets), extra = lane(supplemental, targets), fused = lane(preview, targets);
        List<Long> added = fused.retrievedIds().stream().filter(id -> !base.retrievedIds().contains(id)).toList();
        List<Long> lost = targets.stream().filter(extra.retrievedIds()::contains)
                .filter(id -> !fused.retrievedIds().contains(id)).toList();
        return new Measurement(sample.path("id").asText(), sample.path("group").asText(),
                unsafe ? "UNSAFE_FIXTURE" : "REFERENCE_FIXTURE", question, alternative,
                sample.path("expectedStatus").asText(), sample.path("gap").asText(),
                unsafe ? sample.path("unsafeReason").asText() : "", List.copyOf(targets), status,
                base, extra, fused, added, lost, origins, exclusions, calls.get(), error, failure, List.copyOf(safety));
    }

    private Lane lane(List<Evidence> evidence, List<Long> targets) {
        List<Long> ids = evidence.stream().map(item -> item.chunkId() - 3000).toList();
        return new Lane(ids, targets.stream().filter(id -> !ids.contains(id)).toList(),
                ids.stream().filter(id -> !targets.contains(id)).toList());
    }

    private Map<String, Object> summarize(List<Measurement> rows) {
        var metrics = new LinkedHashMap<String, Object>();
        List<Measurement> positive = rows.stream().filter(row -> !row.targetEvidenceIds().isEmpty()).toList();
        int expected = positive.stream().mapToInt(row -> row.targetEvidenceIds().size()).sum();
        metrics.put("cases", rows.size());
        metrics.put("positiveCases", positive.size());
        metrics.put("failedCases", rows.stream().filter(row -> row.errorCode() != 0 || !row.safetyErrors().isEmpty()).count());
        for (String name : List.of("baseline", "supplemental", "preview")) {
            int hits = 0, complete = 0, noTargetWithCandidates = 0;
            for (Measurement row : rows) {
                Lane lane = switch (name) { case "baseline" -> row.baseline(); case "supplemental" -> row.supplemental(); default -> row.preview(); };
                if (row.errorCode() != 0 || !row.safetyErrors().isEmpty()) continue;
                if (row.targetEvidenceIds().isEmpty()) {
                    if (!lane.retrievedIds().isEmpty()) noTargetWithCandidates++;
                } else {
                    hits += row.targetEvidenceIds().size() - lane.missingTargetIds().size();
                    if (lane.missingTargetIds().isEmpty()) complete++;
                }
            }
            metrics.put(name, Map.of("targetEvidenceHit", counts(hits, expected),
                    "allTargetEvidenceHit", counts(complete, positive.size()),
                    "noTargetCasesWithCandidates", noTargetWithCandidates));
        }
        metrics.put("targetsLostAfterSupplement", rows.stream().mapToInt(row -> row.targetsLostAfterSupplement().size()).sum());
        return metrics;
    }

    private Map<String, Integer> counts(int numerator, int denominator) {
        return Map.of("numerator", numerator, "denominator", denominator);
    }

    private Map<String, String> sourceHashes() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String file : List.of("src/main/java/com/ragqa/rag/retrieval/KnowledgeRetrievalExperiment.java",
                "src/main/java/com/ragqa/rag/retrieval/QueryRewriter.java",
                "src/main/java/com/ragqa/rag/retrieval/QueryRewriteContract.java",
                "src/main/java/com/ragqa/rag/retrieval/KnowledgeEvidenceFusion.java",
                "src/main/java/com/ragqa/rag/retrieval/KeywordKnowledgeRetriever.java",
                "src/main/java/com/ragqa/mapper/KnowledgeRetrievalMapper.java",
                "src/test/java/com/ragqa/mapper/KnowledgeGoldenSetTest.java",
                "src/test/java/com/ragqa/mapper/KnowledgeRewriteComparisonTest.java"))
            hashes.put(file, hash(Files.readAllBytes(Path.of(file))));
        return hashes;
    }

    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少固定评测资源");
            return input.readAllBytes();
        }
    }

    /** 只记录真实检索返回的副本，不更改查询、范围、时限、排序或授权复核。 */
    private static final class RecordingRetriever implements KnowledgeRetriever {
        private final KnowledgeRetriever delegate;
        private int calls;
        private List<Evidence> original = List.of(), supplemental = List.of();

        private RecordingRetriever(KnowledgeRetriever delegate) { this.delegate = delegate; }

        @Override
        public List<Evidence> retrieve(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline) {
            List<Evidence> result = delegate.retrieve(member, spaceId, question, deadline);
            if (calls++ == 0) original = List.copyOf(result);
            else supplemental = List.copyOf(result);
            return result;
        }

        @Override
        public Evidence requireEvidence(EnterpriseMemberContext member, long spaceId, long chunkId) {
            return delegate.requireEvidence(member, spaceId, chunkId);
        }
    }
}
