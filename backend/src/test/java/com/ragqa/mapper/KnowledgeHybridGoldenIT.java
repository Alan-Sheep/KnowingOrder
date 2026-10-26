package com.ragqa.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.HybridKnowledgeRetriever;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.service.KnowledgeVectorJobService;
import com.ragqa.service.KnowledgeVectorPublicationService;
import com.ragqa.service.KnowledgeVectorWorker;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/** 固定合成集比较真实本机混合检索与关键词；H2、不调用回答模型、不调参、不下载模型。 */
@EnabledIfEnvironmentVariable(named = "VECTOR_CHAIN_IT", matches = "true")
class KnowledgeHybridGoldenIT {
    private static final double THRESHOLD = 0.5;
    private static final int SMOOTHING = 60;
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private final ObjectMapper json = new ObjectMapper();
    private final String alias = "ko_golden_it_" + UUID.randomUUID().toString().replace("-", "") + ":latest";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Lane(List<Long> retrievedIds, List<Long> missingIds, List<Long> unannotatedIds,
            int errorCode, String failureType, List<String> safetyErrors, long elapsedMs) { }
    record Comparison(String id, String category, String question, List<Long> expectedIds,
            int expectedError, Lane keyword, Lane hybrid) { }

    @Test
    @Timeout(180)
    void compareFixedTwentyFourCasesWithoutClaimingAnswerQuality() throws Exception {
        byte[] corpusBytes;
        try (var input = getClass().getResourceAsStream("/eval/knowledge-v1.json")) {
            corpusBytes = Objects.requireNonNull(input).readAllBytes();
        }
        var corpus = json.readValue(corpusBytes, KnowledgeGoldenSetTest.Corpus.class);
        assertThat(corpus.version()).isEqualTo("knowledge-synthetic-v1");
        assertThat(corpus.documents()).hasSize(20).extracting(KnowledgeGoldenSetTest.Document::id).doesNotHaveDuplicates();
        assertThat(corpus.cases()).hasSize(24).extracting(KnowledgeGoldenSetTest.Sample::id).doesNotHaveDuplicates();
        String source = System.getenv().getOrDefault("VECTOR_CHAIN_MODEL", "bge-m3:latest");
        assertThat(source).matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}");
        assertThat(json.readTree(local("GET", "/api/tags", null)).path("models").findValuesAsText("name"))
                .as("须先安装原模型；本测试不会拉取模型").contains(source).doesNotContain(alias);
        var vectors = new QdrantVectorClient(json, true, "http://127.0.0.1:6333");
        QdrantVectorClient.Index ownedIndex = null;
        boolean aliasAttempted = false;
        Throwable failure = null;
        var rows = new ArrayList<Comparison>();
        try (var db = new EnterpriseDatabase()) {
            aliasAttempted = true;
            local("POST", "/api/copy", Map.of("source", source, "destination", alias));
            var embedding = new LocalEmbeddingClient(json, true, "http://127.0.0.1:11434", alias);
            var model = embedding.snapshot(deadline());
            db.seed();
            try (var connection = db.dataSource.getConnection()) {
                for (String file : List.of("20261019_04_knowledge_vector_jobs.sql",
                        "20261020_01_knowledge_vector_manifests.sql", "20261020_02_knowledge_vector_publication.sql")) {
                    String migration = Files.readString(Path.of("sql/migrations", file), StandardCharsets.UTF_8)
                            .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                            .replaceAll("(?i)CHARACTER SET ascii", "");
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
                }
            }
            db.factory.getConfiguration().addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
            for (var type : List.of(KnowledgeDocumentMapper.class, KnowledgeManagementMapper.class,
                    KnowledgeVectorJobMapper.class, KnowledgeVectorManifestMapper.class,
                    KnowledgeVectorPublicationMapper.class, KnowledgeRetrievalMapper.class))
                db.factory.getConfiguration().addMapper(type);
            var sql = new SqlSessionTemplate(db.factory);
            var jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
            var documents = sql.getMapper(KnowledgeDocumentMapper.class);
            var management = sql.getMapper(KnowledgeManagementMapper.class);
            var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                    sql.getMapper(EnterpriseDirectoryMapper.class));
            var resources = new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class));
            var manager = new DataSourceTransactionManager(db.dataSource);
            corpus.documents().forEach(document -> {
                KnowledgeGoldenSetTest.seed(db, document);
                db.jdbc.update("UPDATE rag_document_version SET content_sha256=?,file_size=? WHERE id=?",
                        hash(document.content().getBytes(StandardCharsets.UTF_8)),
                        document.content().getBytes(StandardCharsets.UTF_8).length, 2000 + document.id());
            });
            // 仅管理员负责构建；不改变受测员工原有READ/部门权限，也不授权402/403。
            db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) "
                    + "VALUES(1,401,103,'MAINTAIN'),(1,404,103,'MAINTAIN')");
            var submission = new KnowledgeVectorJobService(jobs, management, documents, members, embedding, manager, true);
            var publication = new KnowledgeVectorPublicationService(jobs, sql.getMapper(KnowledgeVectorPublicationMapper.class),
                    management, members, embedding, vectors, manager, true);
            var worker = new KnowledgeVectorWorker(jobs, sql.getMapper(KnowledgeVectorManifestMapper.class),
                    management, documents, members, embedding, vectors, manager, true);
            try {
                List<KnowledgeGoldenSetTest.Document> indexed = corpus.documents().stream()
                        .filter(document -> document.state().equals("LIVE") && (document.spaceId() == 401 || document.spaceId() == 404)).toList();
                assertThat(indexed).hasSize(11);
                for (var document : indexed) {
                    String id = UUID.randomUUID().toString();
                    submission.submit(ADMIN, 1, document.spaceId(), 1000 + document.id(), 2000 + document.id(),
                            new KnowledgeVectorJobService.Submit(id, 1L));
                    var job = jobs.find(id);
                    var index = new QdrantVectorClient.Index(job.collectionName(), job.modelName(), job.modelRevision(), job.dimensions());
                    if (ownedIndex == null) {
                        assertThat(vectors.inspect(index, deadline())).as("不得接管已有集合").isFalse();
                        ownedIndex = index;
                    } else assertThat(index).isEqualTo(ownedIndex);
                    assertThat(worker.runOnce()).isTrue();
                    assertThat(jobs.find(id).status()).as("构建失败码=%s", jobs.find(id).failureCode()).isEqualTo("VERIFIED");
                    assertThat(publication.publish(ADMIN, 1, document.spaceId(), 1000 + document.id(),
                            new KnowledgeVectorPublicationService.Publish(id, 0L)).status()).isEqualTo("READY");
                }
                System.out.printf("企业真实检索对照：已构建并发布%d份合成资料，维度=%d，阈值=%.1f，RRF=%d%n",
                        indexed.size(), model.dimensions(), THRESHOLD, SMOOTHING);
                var keyword = new KeywordKnowledgeRetriever(sql.getMapper(KnowledgeRetrievalMapper.class), resources);
                var hybrid = new HybridKnowledgeRetriever(sql.getMapper(KnowledgeRetrievalMapper.class),
                        sql.getMapper(KnowledgeVectorPublicationMapper.class), resources, embedding, vectors, THRESHOLD, SMOOTHING);
                for (var sample : corpus.cases()) {
                    var identity = new AuthenticatedUserPrincipal(sample.member().equals("OWNER") ? 1 : 2, 1);
                    EnterpriseMemberContext member = members.requireMember(identity, 1L);
                    List<Long> expected = sample.parts().stream().flatMap(part -> part.evidenceIds().stream()).distinct().toList();
                    boolean revoke = sample.change().equals("REVOKE");
                    if (revoke) db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
                    try {
                        Lane baseline = evaluate(keyword, member, corpus.documents(), sample, expected);
                        Lane combined = evaluate(hybrid, member, corpus.documents(), sample, expected);
                        rows.add(new Comparison(sample.id(), sample.category(), sample.question(), expected,
                                sample.expectedError(), baseline, combined));
                        System.out.printf("企业真实检索对照：%s 关键词=%s 混合=%s 目标=%s 错误码=%d/%d%n",
                                sample.id(), baseline.retrievedIds(), combined.retrievedIds(), expected,
                                baseline.errorCode(), combined.errorCode());
                    } finally {
                        if (revoke) db.jdbc.update("UPDATE rag_knowledge_grant SET status=0 WHERE knowledge_space_id=401");
                    }
                }
                var artifact = new LinkedHashMap<String, Object>();
                artifact.put("datasetVersion", corpus.version());
                artifact.put("datasetSha256", hash(corpusBytes));
                artifact.put("evaluatedAt", Instant.now().toString());
                artifact.put("model", Map.of("source", source, "revision", model.revision(), "dimensions", model.dimensions()));
                artifact.put("parameters", Map.of("similarityThreshold", THRESHOLD, "rrfSmoothing", SMOOTHING,
                        "indexedDocuments", indexed.size(), "expectedCases", 24, "retrievalLimit", 6));
                artifact.put("sourceSha256", sources());
                artifact.put("engines", "H2 + real local Ollama + real Qdrant; keyword first, hybrid second");
                artifact.put("answerModelCalls", 0);
                artifact.put("answerQuality", "NOT_RUN");
                var metrics = Map.of("keyword", summarize(rows, Comparison::keyword), "hybrid", summarize(rows, Comparison::hybrid));
                artifact.put("metrics", metrics);
                artifact.put("cases", rows);
                artifact.put("limitations", "固定合成语料、固定参数的一次检索对照；未标注候选不自动等于无关，候选非空不等于可回答。"
                        + "仅11份有效授权资料建向量，其余状态和越权记录仍在H2；不是MySQL、生成答案、冷启动、生产P95或容量验收。");
                Path report = Path.of("E:/Codex/test-artifacts/knowledge-golden-v1/hybrid-comparison-"
                        + Instant.now().toString().replace(':', '-') + ".json");
                Files.createDirectories(report.getParent());
                Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(artifact)
                        .replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                System.out.println("企业真实检索对照指标：" + json.writeValueAsString(metrics));
                System.out.println("逐题报告：" + report);
                // 先保留全部坏例再检查安全；质量改善不设未经确认的最低分，也不删去漏召回题。
                assertThat(rows).hasSize(24).allSatisfy(row -> {
                    assertThat(row.keyword().safetyErrors()).as("%s关键词安全", row.id()).isEmpty();
                    assertThat(row.hybrid().safetyErrors()).as("%s混合安全", row.id()).isEmpty();
                });
            } finally { worker.close(); }
        } catch (Exception | AssertionError ex) {
            failure = ex;
            throw ex;
        } finally {
            // 超时中断后也尝试有限清理；测试自身的超时失败不会被当成成功。
            Thread.interrupted();
            Throwable cleanupFailure = null;
            try {
                if (ownedIndex != null && vectors.inspect(ownedIndex, deadline())) {
                    vectors.deleteCollection(ownedIndex, deadline());
                    assertThat(vectors.inspect(ownedIndex, deadline())).isFalse();
                }
            } catch (Exception | AssertionError ex) { cleanupFailure = ex; }
            try {
                if (aliasAttempted) {
                    local("DELETE", "/api/delete", Map.of("model", alias));
                    assertThat(json.readTree(local("GET", "/api/tags", null)).path("models").findValuesAsText("name"))
                            .contains(source).doesNotContain(alias);
                }
            } catch (Exception | AssertionError ex) {
                if (cleanupFailure == null) cleanupFailure = ex;
                else cleanupFailure.addSuppressed(ex);
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw new AssertionError("合成评测资源清理未确认", cleanupFailure);
            }
        }
    }

    private Lane evaluate(KnowledgeRetriever retriever, EnterpriseMemberContext member,
            List<KnowledgeGoldenSetTest.Document> documents, KnowledgeGoldenSetTest.Sample sample, List<Long> expected) {
        long started = System.nanoTime();
        int code = 0;
        String failure = "";
        List<Long> actual = List.of();
        var safety = new ArrayList<String>();
        try {
            List<Evidence> hits = retriever.retrieve(member, sample.spaceId(), sample.question(), deadline());
            actual = hits.stream().map(hit -> hit.chunkId() - 3000).toList();
            if (actual.size() > 6 || actual.stream().distinct().count() != actual.size()) safety.add("片段数量或重复异常");
            for (Evidence hit : hits) {
                var source = documents.stream().filter(document -> document.id() == hit.chunkId() - 3000).findFirst();
                if (source.isEmpty() || !source.get().state().equals("LIVE") || source.get().spaceId() != sample.spaceId()
                        || !source.get().content().equals(hit.content()) || hit.documentId() != source.get().id() + 1000
                        || hit.versionId() != source.get().id() + 2000 || hit.startOffset() != 0
                        || hit.endOffset() != hit.content().length()
                        || !retriever.requireEvidence(member, sample.spaceId(), hit.chunkId()).equals(hit))
                    safety.add("越权、非当前版本或错误引用");
            }
        } catch (BusinessException ex) { code = ex.getCode(); }
        catch (Exception ex) { code = 500; failure = ex.getClass().getSimpleName(); }
        if (code != sample.expectedError()) safety.add("错误码不符：" + code);
        if (sample.expectedError() != 0 && !actual.isEmpty()) safety.add("拒绝访问仍返回片段");
        List<Long> found = actual;
        return new Lane(actual, expected.stream().filter(id -> !found.contains(id)).toList(),
                actual.stream().filter(id -> !expected.contains(id)).toList(), code, failure, List.copyOf(safety),
                (System.nanoTime() - started) / 1_000_000);
    }

    private Map<String, Object> summarize(List<Comparison> rows, Function<Comparison, Lane> selector) {
        int positive = 0, relevant = 0, found = 0, full = 0, denied = 0, deniedCorrect = 0;
        int noEvidence = 0, withCandidates = 0, failures = 0;
        long totalMs = 0;
        double reciprocal = 0;
        for (Comparison row : rows) {
            Lane lane = selector.apply(row);
            totalMs += lane.elapsedMs();
            if (row.expectedError() != 0) {
                denied++;
                if (lane.safetyErrors().isEmpty()) deniedCorrect++;
            } else if (!row.expectedIds().isEmpty()) {
                positive++;
                relevant += row.expectedIds().size();
                if (lane.errorCode() == 0) {
                    found += row.expectedIds().size() - lane.missingIds().size();
                    if (lane.missingIds().isEmpty()) full++;
                    for (int n = 0; n < lane.retrievedIds().size(); n++) {
                        if (row.expectedIds().contains(lane.retrievedIds().get(n))) { reciprocal += 1.0 / (n + 1); break; }
                    }
                }
            } else {
                noEvidence++;
                if (!lane.retrievedIds().isEmpty()) withCandidates++;
            }
            if (!lane.safetyErrors().isEmpty()) failures++;
        }
        return Map.of("totalCases", rows.size(), "positiveCases", positive,
                "relevantEvidenceRecallAt6", counts(found, relevant), "allEvidenceHit", counts(full, positive),
                "accessDenied", counts(deniedCorrect, denied), "noEvidenceCasesWithCandidates", counts(withCandidates, noEvidence),
                "meanReciprocalRankAt6", positive == 0 ? 0 : reciprocal / positive,
                "safetyFailureCases", failures, "totalRetrievalAndReferenceCheckMs", totalMs);
    }

    private Map<String, Object> counts(int found, int total) {
        return Map.of("numerator", found, "denominator", total, "ratio", total == 0 ? 0 : (double) found / total);
    }

    private Map<String, String> sources() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String file : List.of("schema.sql", "src/main/java/com/ragqa/rag/retrieval/KeywordKnowledgeRetriever.java",
                "src/main/java/com/ragqa/rag/retrieval/HybridKnowledgeRetriever.java",
                "src/main/java/com/ragqa/rag/retrieval/HybridRetrievalRanking.java",
                "src/main/java/com/ragqa/rag/retrieval/LocalEmbeddingClient.java",
                "src/main/java/com/ragqa/rag/retrieval/QdrantVectorClient.java",
                "src/main/java/com/ragqa/mapper/KnowledgeRetrievalMapper.java",
                "src/main/java/com/ragqa/mapper/KnowledgeVectorPublicationMapper.java",
                "src/test/java/com/ragqa/mapper/KnowledgeGoldenSetTest.java",
                "src/test/java/com/ragqa/mapper/KnowledgeHybridGoldenIT.java"))
            hashes.put(file, hash(Files.readAllBytes(Path.of(file))));
        return hashes;
    }

    private String local(String method, String path, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("本机合成评测夹具操作须成功：%s", path).isEqualTo(200);
        return response.body();
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException("运行环境缺少SHA-256", ex); }
    }
    private static TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(30)); }
}
