package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.KnowledgeRetrievalConfig;
import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.chat.KnowledgeAnswerComposer;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.HybridKnowledgeRetriever;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 显式启用的真实本机Embedding/Qdrant联合验收，仅使用虚构制度和隔离H2。
 * 不下载模型、不调用回答模型、不读取开发库凭据、不修改应用开关。
 * 随机模型别名隔离生产集合；仅清理本次拥有的别名和集合，不删除原模型。
 */
@EnabledIfEnvironmentVariable(named = "VECTOR_CHAIN_IT", matches = "true")
class KnowledgeVectorChainIT {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private static final List<String> TEXT = List.of("虚构制度：餐补每日三十元。", "虚构制度：需主管审批。");
    private final ObjectMapper json = new ObjectMapper();
    private final String alias = "ko_chain_it_" + UUID.randomUUID().toString().replace("-", "") + ":latest";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void realEmbeddingBuildPublishHybridRebuildWithdrawAndRevoke() throws Exception {
        String source = System.getenv().getOrDefault("VECTOR_CHAIN_MODEL", "bge-m3:latest");
        assertThat(source).matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}");
        var installed = json.readTree(local("GET", "/api/tags", null));
        assertThat(installed.path("models").findValuesAsText("name"))
                .as("须先自行安装指定模型；本测试不会拉取模型").contains(source).doesNotContain(alias);
        var vectors = new QdrantVectorClient(json, true, "http://127.0.0.1:6333");
        QdrantVectorClient.Index ownedIndex = null;
        boolean aliasAttempted = false;
        Throwable originalFailure = null;
        try (var db = new EnterpriseDatabase()) {
            aliasAttempted = true;
            local("POST", "/api/copy", Map.of("source", source, "destination", alias));
            var embedding = new LocalEmbeddingClient(json, true, "http://127.0.0.1:11434", alias);
            var snapshot = embedding.snapshot(deadline());
            assertThat(snapshot.model()).isEqualTo(alias);
            assertThat(snapshot.dimensions()).isBetween(1, 2048);
            System.out.printf("真实向量链路：模型元数据通过，维度=%d%n", snapshot.dimensions());
            db.seed();
            try (var connection = db.dataSource.getConnection()) {
                for (String file : List.of("20261019_04_knowledge_vector_jobs.sql",
                        "20261020_01_knowledge_vector_manifests.sql", "20261020_02_knowledge_vector_publication.sql")) {
                    String sql = Files.readString(Path.of("sql/migrations", file), StandardCharsets.UTF_8)
                            .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                            .replaceAll("(?i)CHARACTER SET ascii", "");
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                }
            }
            db.factory.getConfiguration().addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
            for (var type : List.of(KnowledgeDocumentMapper.class, KnowledgeManagementMapper.class,
                    KnowledgeVectorJobMapper.class, KnowledgeVectorManifestMapper.class,
                    KnowledgeVectorPublicationMapper.class, KnowledgeRetrievalMapper.class))
                db.factory.getConfiguration().addMapper(type);
            var sql = new SqlSessionTemplate(db.factory);
            var jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
            var publications = sql.getMapper(KnowledgeVectorPublicationMapper.class);
            var management = sql.getMapper(KnowledgeManagementMapper.class);
            var documents = sql.getMapper(KnowledgeDocumentMapper.class);
            var evidence = sql.getMapper(KnowledgeRetrievalMapper.class);
            var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                    sql.getMapper(EnterpriseDirectoryMapper.class));
            var resources = new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class));
            var manager = new DataSourceTransactionManager(db.dataSource);
            seedDocument(db);
            var submissions = new KnowledgeVectorJobService(jobs, management, documents, members, embedding, manager, true);
            var publication = new KnowledgeVectorPublicationService(jobs, publications, management, members,
                    embedding, vectors, manager, true);
            var settings = new MockEnvironment().withProperty("knowingorder.retrieval.hybrid.enabled", "true")
                    .withProperty("knowingorder.embedding.enabled", "true").withProperty("knowingorder.qdrant.enabled", "true")
                    .withProperty("knowingorder.embedding.model", alias)
                    // 仅验收流程，阈值不用于证明生产召回质量；相似度质量需另跑黄金集。
                    .withProperty("knowingorder.retrieval.hybrid.similarity-threshold", "0.5")
                    .withProperty("knowingorder.retrieval.hybrid.rrf-smoothing", "60");
            var retriever = new KnowledgeRetrievalConfig().selectedKnowledgeRetriever(
                    new KeywordKnowledgeRetriever(evidence, resources), evidence, publications, resources,
                    embedding, vectors, settings);
            assertThat(retriever).isInstanceOf(HybridKnowledgeRetriever.class);
            var composer = mock(KnowledgeAnswerComposer.class);
            var worker = new KnowledgeVectorWorker(jobs, sql.getMapper(KnowledgeVectorManifestMapper.class),
                    management, documents, members, embedding, vectors, manager, true);
            try {
                var answers = new KnowledgeAnswerService(members, resources, retriever, composer, "");
                try {
                    String first = UUID.randomUUID().toString();
                    var request = new KnowledgeVectorJobService.Submit(first, 7L);
                    assertThat(submissions.submit(ADMIN, 1, 401, 501, 701, request).status()).isEqualTo("QUEUED");
                    assertThat(submissions.submit(ADMIN, 1, 401, 501, 701, request).id()).isEqualTo(first);
                    assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job", Integer.class)).isEqualTo(1);
                    var stored = jobs.find(first);
                    var index = new QdrantVectorClient.Index(stored.collectionName(), stored.modelName(),
                            stored.modelRevision(), stored.dimensions());
                    assertThat(vectors.inspect(index, deadline())).as("不得接管已有集合").isFalse();
                    ownedIndex = index;
                    long began = System.nanoTime();
                    assertThat(worker.runOnce()).isTrue();
                    assertThat(jobs.find(first).status()).as("真实构建失败码=%s", jobs.find(first).failureCode()).isEqualTo("VERIFIED");
                    assertThat(jobs.find(first).verifiedChunks()).isEqualTo(2);
                    assertThat(publication.read(ADMIN, 1, 401, 501).status()).isEqualTo("UNPUBLISHED");
                    System.out.printf("真实向量链路：构建及清单核验通过，耗时=%dms，未自动发布%n", elapsed(began));
                    var ready = publication.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish(first, 0L));
                    assertThat(ready.status()).isEqualTo("READY");
                    assertThat(ready.revision()).isEqualTo(1);
                    assertThat(answers.retrievalMode(ADMIN, 1L, 401L)).isEqualTo("HYBRID");
                    began = System.nanoTime();
                    var answer = answers.answer(ADMIN, 1L, 401L, "餐补每日多少，需要谁审批？");
                    assertThat(answer.mode()).isEqualTo("LOCAL_EVIDENCE");
                    assertThat(answer.status()).isEqualTo("EVIDENCE_ONLY");
                    assertThat(answer.evidence()).extracting(Evidence::content).containsExactlyInAnyOrderElementsOf(TEXT);
                    assertThat(answer.evidence()).allSatisfy(row -> {
                        assertThat(row.versionId()).isEqualTo(701L);
                        assertThat(row.endOffset() - row.startOffset()).isEqualTo(row.content().length());
                        assertThat(String.join("\n", TEXT).substring(row.startOffset(), row.endOffset())).isEqualTo(row.content());
                    });
                    verifyNoInteractions(composer);
                    System.out.printf("真实向量链路：混合检索和来源复核通过，耗时=%dms，回答模型调用=0%n", elapsed(began));
                    String paraphrase = "晚饭能报销多少钱";
                    var member = members.requireMember(ADMIN, 1L);
                    assertThat(new KeywordKnowledgeRetriever(evidence, resources)
                            .retrieve(member, 401, paraphrase, deadline())).as("语义样例不得靠关键词命中").isEmpty();
                    var semantic = retriever.retrieve(member, 401, paraphrase, deadline());
                    assertThat(semantic).extracting(Evidence::chunkId).contains(1001L);
                    System.out.printf("真实向量链路：同义问法关键词命中=0，语义召回=%d条，含餐补原文%n", semantic.size());
                    String second = UUID.randomUUID().toString();
                    submissions.submit(ADMIN, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(second, 7L));
                    assertThat(jobs.find(second).generation()).isNotEqualTo(jobs.find(first).generation());
                    assertThat(worker.runOnce()).isTrue();
                    assertThat(jobs.find(second).status()).isEqualTo("VERIFIED");
                    var replaced = publication.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish(second, 1L));
                    assertThat(replaced.revision()).isEqualTo(2);
                    assertThat(replaced.jobId()).isEqualTo(second);
                    assertThatThrownBy(() -> publication.publish(ADMIN, 1, 401, 501,
                            new KnowledgeVectorPublicationService.Publish(first, 1L)))
                            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
                    var withdrawn = publication.withdraw(ADMIN, 1, 401, 501,
                            new KnowledgeVectorPublicationService.Withdraw(second, 2L));
                    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
                    assertThat(withdrawn.revision()).isEqualTo(3);
                    assertThatThrownBy(() -> answers.answer(ADMIN, 1L, 401L, "餐补多少"))
                            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
                    db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
                    assertThatThrownBy(() -> answers.evidence(ADMIN, 1L, 401L, 1001L))
                            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
                    verifyNoInteractions(composer);
                    System.out.println("真实向量链路：重建切代、旧CAS冲突、撤回不回退、撤权拒绝通过");
                } finally { answers.close(); }
            } finally { worker.close(); }
        } catch (Exception | AssertionError ex) {
            originalFailure = ex;
            throw ex;
        } finally {
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
                    assertThat(json.readTree(local("GET", "/api/tags", null))
                            .path("models").findValuesAsText("name")).contains(source).doesNotContain(alias);
                }
            } catch (Exception | AssertionError ex) {
                if (cleanupFailure == null) cleanupFailure = ex;
                else cleanupFailure.addSuppressed(ex);
            }
            if (cleanupFailure != null) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else throw new AssertionError("测试专属资源清理未确认，须人工核对", cleanupFailure);
            }
        }
    }

    private void seedDocument(EnterpriseDatabase db) throws Exception {
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN')");
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=701 WHERE id=501");
        byte[] original = String.join("\n", TEXT).getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                  parse_status,publication_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'虚构制度.md',?,?,?,?,'READY','PUBLISHED',2,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), hash, "c".repeat(64), original.length);
        int offset = 0;
        for (int n = 0; n < TEXT.size(); n++) {
            db.jdbc.update("""
                    INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                    VALUES(?,501,701,?,?,?,?,?,?)
                    """, 1001 + n, n, TEXT.get(n), offset, offset + TEXT.get(n).length(), n + 1, n + 1);
            offset += TEXT.get(n).length() + 1;
        }
    }

    private String local(String method, String path, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("本机模型夹具操作%s须成功，禁止自动下载或重试", path).isEqualTo(200);
        return response.body();
    }

    private static TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(30)); }
    private static long elapsed(long began) { return (System.nanoTime() - began) / 1_000_000; }
}
