package com.ragqa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.controller.KnowledgeVectorJobController;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeDocumentMapper;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper;
import com.ragqa.mapper.KnowledgeVectorManifestMapper;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.KnowledgeVectorWorker;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回环HTTP真实登录/鉴权/服务/事务/SQL，隔离H2；模型与Qdrant使用虚构方法替身，无真实推理。 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knowingorder.qdrant.enabled=true", "knowingorder.embedding.enabled=false",
        "knowingorder.security.login-per-address-per-minute=1000", "knowingorder.security.login-global-per-minute=2000",
        "knowingorder.knowledge.synthetic-model-spaces=", "spring.sql.init.mode=never",
        "logging.level.com.ragqa=ERROR", "server.address=127.0.0.1"
})
class KnowledgeVectorJobHttpTest {
    // 仅隔离H2中的虚构测试口令，不属于开发或生产账号。
    private static final String PASSWORD = "Fictional-job-http#2026";
    private static final String BASE = "/api/enterprises/1/knowledge-spaces/401/documents/501/versions/701/vector-jobs";
    private static final String PUBLICATION = "/api/enterprises/1/knowledge-spaces/401/documents/501/vector-publication";
    private static final LocalEmbeddingClient.ModelSnapshot MODEL = new LocalEmbeddingClient.ModelSnapshot("synthetic:1", "a".repeat(64), 2);
    private static final EnterpriseDatabase DB = database();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private KnowledgeVectorJobMapper jobs;
    @Autowired private KnowledgeVectorManifestMapper manifests;
    @Autowired private KnowledgeManagementMapper management;
    @Autowired private KnowledgeDocumentMapper documents;
    @Autowired private EnterpriseMemberService members;
    @Autowired private PlatformTransactionManager manager;
    @MockBean private LocalEmbeddingClient embedding;
    @MockBean private QdrantVectorClient vectors;
    // 禁止定时调度干扰边界场景；闭环用例显式创建真实执行器并只执行一次。
    @MockBean private KnowledgeVectorWorker scheduledWorker;
    private final Map<Long, QdrantVectorClient.Stored> points = new ConcurrentHashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB.dataSource::getURL);
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "sa");
        properties.add("spring.datasource.password", () -> "");
    }

    private static EnterpriseDatabase database() {
        try {
            var db = new EnterpriseDatabase(); db.seed();
            try (var connection = db.dataSource.getConnection()) {
                for (String name : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql",
                        "20261020_02_knowledge_vector_publication.sql")) {
                    String script = Files.readString(Path.of("sql/migrations/" + name), StandardCharsets.UTF_8)
                            .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                            .replaceAll("(?i)CHARACTER SET ascii", "");
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
                }
            }
            db.jdbc.update("UPDATE sys_user SET password_hash=?", new BCryptPasswordEncoder(12).encode(PASSWORD));
            return db;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @BeforeEach void prepare() {
        DB.jdbc.update("DELETE FROM rag_vector_publication"); DB.jdbc.update("DELETE FROM rag_vector_manifest_entry");
        DB.jdbc.update("DELETE FROM rag_vector_job"); DB.jdbc.update("DELETE FROM rag_chunk");
        DB.jdbc.update("DELETE FROM rag_document_version"); DB.jdbc.update("DELETE FROM rag_knowledge_grant");
        DB.jdbc.update("UPDATE sys_user SET status=0,deleted=0,auth_version=1");
        DB.jdbc.update("UPDATE sys_enterprise SET status=0,permission_version=1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status=0,permission_version=1");
        DB.jdbc.update("UPDATE rag_knowledge_space SET status=0,permission_version=1");
        DB.jdbc.update("UPDATE sys_department SET status=0");
        DB.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN'),(1,401,101,'READ')");
        DB.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=701,deleted=0 WHERE id=501");
        DB.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
                  original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,publication_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'虚构制度.md',?,?,?,100,'READY','PUBLISHED',1,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "b".repeat(64), "c".repeat(64));
        DB.jdbc.update("INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line) VALUES(1001,501,701,0,'虚构餐补制度',0,6,1,1)");
        points.clear();
        when(embedding.snapshot(any())).thenAnswer(call -> { outside(); return MODEL; });
    }

    @AfterAll static void close() throws Exception { DB.close(); }

    @Test void admissionRefreshCancelAndSameIdRetryKeepOriginalIdentityAndDeadline() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        var response = submit(admin, BASE, id, "7", csrf); assertCode(response, 0); safe(response);
        JsonNode first = tree(response).path("data");
        assertThat(first.path("status").asText()).isEqualTo("QUEUED");
        assertThat(first.path("expectedRevision").asText()).isEqualTo("7");
        assertThat(first.path("expectedRevision").isTextual()).isTrue();
        assertThat(first.path("documentId").asText()).isEqualTo("501");
        assertThat(first.path("versionId").asText()).isEqualTo("701");
        assertThat(first.path("deadlineMs").asLong() - first.path("createdMs").asLong()).isEqualTo(120_000);
        assertThat(tree(get(admin, BASE + "/" + id)).path("data")).isEqualTo(first);
        assertThat(tree(submit(admin, BASE, id, "7", csrf)).path("data")).isEqualTo(first);
        var cancelled = post(admin, BASE + "/" + id + "/cancel", "{}", csrf); assertCode(cancelled, 0); safe(cancelled);
        JsonNode terminal = tree(cancelled).path("data");
        assertThat(terminal.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(terminal.path("deadlineMs")).isEqualTo(first.path("deadlineMs"));
        assertThat(tree(post(admin, BASE + "/" + id + "/cancel", "{}", csrf)).path("data")).isEqualTo(terminal);
        assertThat(tree(submit(admin, BASE, id, "7", csrf)).path("data")).isEqualTo(terminal);
        assertThat(count()).isEqualTo(1); verify(embedding, times(1)).snapshot(any()); verifyNoInteractions(vectors);
    }

    @Test void httpAdmissionActualWorkerVerificationAndExplicitPublicationFormOfflineLifecycle() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0);
        when(embedding.embed(anyList(), any())).thenAnswer(call -> {
            outside(); List<String> texts = call.getArgument(0); assertThat(texts).containsExactly("虚构餐补制度");
            return List.of(new Vector(MODEL.model(), List.of(3.0, 4.0)));
        });
        when(vectors.inspect(any(), any())).thenAnswer(call -> { outside(); return true; });
        doAnswer(call -> {
            outside(); List<QdrantVectorClient.Point> batch = call.getArgument(2);
            for (var point : batch) points.put(point.chunkId(), new QdrantVectorClient.Stored(point.chunkId(), point.contentSha256(), QdrantVectorClient.vectorSha256(point.vector())));
            return null;
        }).when(vectors).upsert(any(), any(), anyList(), any());
        when(vectors.read(any(), any(), anyList(), any())).thenAnswer(call -> {
            outside(); List<Long> ids = call.getArgument(2); return ids.stream().map(points::get).toList();
        });
        var worker = new KnowledgeVectorWorker(jobs, manifests, management, documents, members, embedding, vectors, manager, true);
        try { assertThat(worker.runOnce()).isTrue(); assertThat(worker.runOnce()).isFalse(); }
        finally { worker.close(); }
        JsonNode verified = tree(get(admin, BASE + "/" + id)).path("data");
        assertThat(verified.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.path("verifiedChunks").asInt()).isEqualTo(1);
        assertThat(verified.path("message").asText()).contains("尚未发布");
        assertThat(tree(get(admin, PUBLICATION)).path("data").path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(tree(post(admin, BASE + "/" + id + "/cancel", "{}", csrf)).path("data").path("status").asText()).isEqualTo("VERIFIED");
        var published = post(admin, PUBLICATION + "/publish", "{\"jobId\":\"" + id + "\",\"expectedRevision\":\"0\"}", csrf);
        assertCode(published, 0); safe(published);
        assertThat(tree(published).path("data").path("status").asText()).isEqualTo("READY");
        assertCode(post(admin, PUBLICATION + "/withdraw", "{\"expectedJobId\":\"" + id + "\",\"expectedRevision\":\"1\"}", csrf), 0);
        verify(embedding, times(1)).embed(anyList(), any()); verify(vectors, times(1)).upsert(any(), any(), anyList(), any());
        verify(vectors, never()).create(any(), any());
    }

    @Test void anonymousForgedHeadersAndMissingCsrfNeverReachAdmission() throws Exception {
        String id = UUID.randomUUID().toString(); var anonymous = client(); var csrf = csrf(anonymous);
        assertCode(get(anonymous, BASE + "/" + id), 401);
        assertThat(submit(anonymous, BASE, id, "7", csrf).statusCode()).isEqualTo(401);
        assertThat(post(anonymous, BASE + "/" + id + "/cancel", "{}", csrf).statusCode()).isEqualTo(401);
        var forged = anonymous.send(HttpRequest.newBuilder(uri(BASE + "/" + id)).header("X-User-Id", "3").header("X-Member-Id", "103").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertCode(forged, 401);
        var admin = loggedIn("admin");
        assertThat(submit(admin, BASE, id, "7", null).statusCode()).isEqualTo(403);
        assertThat(post(admin, BASE + "/" + id + "/cancel", "{}", null).statusCode()).isEqualTo(403);
        verifyNoInteractions(embedding, vectors); assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "peer", "adminWithoutGrant"})
    void onlyExplicitMaintainersMaySubmitReadAndCancel(String username) throws Exception {
        var client = loggedIn(username.equals("adminWithoutGrant") ? "admin" : username); var csrf = csrf(client);
        if (username.equals("adminWithoutGrant")) DB.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        String id = UUID.randomUUID().toString();
        assertCode(submit(client, BASE, id, "7", csrf), 404);
        assertCode(get(client, BASE + "/" + id), 404);
        assertCode(post(client, BASE + "/" + id + "/cancel", "{}", csrf), 404);
        verifyNoInteractions(embedding, vectors); assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"enterprise", "space", "document", "version", "otherOwner"})
    void existingTaskCannotBeReadCancelledOrResubmittedAcrossOwnershipBoundaries(String changed) throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0); clearInvocations(embedding);
        String path = switch (changed) {
            case "enterprise" -> BASE.replace("enterprises/1", "enterprises/2");
            case "space" -> BASE.replace("spaces/401", "spaces/402");
            case "document" -> BASE.replace("documents/501", "documents/502");
            case "version" -> BASE.replace("versions/701", "versions/702");
            default -> BASE;
        };
        if (changed.equals("otherOwner")) {
            DB.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE member_id=101");
            admin = loggedIn("owner"); csrf = csrf(admin);
        }
        assertCode(get(admin, path + "/" + id), 404);
        assertCode(post(admin, path + "/" + id + "/cancel", "{}", csrf), 404);
        assertCode(submit(admin, path, id, "7", csrf), 404);
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); assertThat(count()).isEqualTo(1); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {"account", "authVersion", "enterprise", "member", "grant", "department"})
    void revocationAfterLoginStopsAllTaskOperations(String changed) throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0); clearInvocations(embedding);
        if (changed.equals("department")) DB.jdbc.update("UPDATE rag_knowledge_grant SET member_id=NULL,department_id=11 WHERE member_id=103");
        DB.jdbc.update(switch (changed) {
            case "account" -> "UPDATE sys_user SET status=1 WHERE id=3";
            case "authVersion" -> "UPDATE sys_user SET auth_version=2 WHERE id=3";
            case "enterprise" -> "UPDATE sys_enterprise SET status=1 WHERE id=1";
            case "member" -> "UPDATE sys_enterprise_member SET status=1 WHERE id=103";
            case "grant" -> "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103";
            default -> "UPDATE sys_department SET status=1 WHERE id=11";
        });
        int code = changed.equals("account") || changed.equals("authVersion") ? 401 : 404;
        assertCode(get(admin, BASE + "/" + id), code);
        if (code == 401) {
            // 首次身份拒绝已经销毁Session；旧CSRF先被过滤器拒绝，刷新后仍必须拒绝匿名访问。
            assertCode(post(admin, BASE + "/" + id + "/cancel", "{}", csrf), 403);
            assertCode(submit(admin, BASE, id, "7", csrf), 403);
            csrf = csrf(admin);
        }
        assertCode(post(admin, BASE + "/" + id + "/cancel", "{}", csrf), code);
        assertCode(submit(admin, BASE, id, "7", csrf), code);
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "0", "true", "7.5", "{}", "[]", "\"0\"", "\"-1\"", "\"07\"", "\"+7\"", "\" 7\"", "\"7e0\"", "\"9223372036854775808\""})
    void invalidRevisionTypeOrValueCannotReachModel(String value) throws Exception {
        var admin = loggedIn("admin");
        assertCode(post(admin, BASE, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":" + value + "}", csrf(admin)), 400);
        assertThat(count()).isZero(); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{", "{\"requestId\":null,\"expectedRevision\":\"7\"}",
            "{\"requestId\":true,\"expectedRevision\":\"7\"}", "{\"requestId\":\"bad\",\"expectedRevision\":\"7\"}",
            "{\"requestId\":\"' OR 1=1 --\",\"expectedRevision\":\"7\"}",
            "{\"requestId\":\"bad\",\"expectedRevision\":\"7\",\"expectedRevision\":\"8\"}",
            "{\"requestId\":\"bad\",\"expectedRevision\":\"7\"} {}"})
    void malformedOrAmbiguousAdmissionBodyIsRejected(String body) throws Exception {
        var admin = loggedIn("admin"); assertCode(post(admin, BASE, body, csrf(admin)), 400);
        assertThat(count()).isZero(); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{", "{\"status\":\"VERIFIED\"}", "{} {}", "{\"memberId\":103}"})
    void cancellationAcceptsOnlyEmptyObject(String body) throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0); clearInvocations(embedding);
        assertCode(post(admin, BASE + "/" + id + "/cancel", body, csrf), 400);
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); verifyNoInteractions(embedding, vectors);
    }

    @Test void forgedIdentityModelAndManifestFieldsAreRejected() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        for (String field : List.of("memberId", "userId", "enterpriseId", "model", "generation", "manifest", "allowExternal")) {
            assertCode(post(admin, BASE, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":\"7\",\"" + field + "\":true}", csrf), 400);
        }
        assertThat(count()).isZero(); verifyNoInteractions(embedding, vectors);
    }

    @Test void changedRevisionAndDifferentRequestForActiveVersionDoNotCreateExtraTasks() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0); clearInvocations(embedding);
        assertCode(submit(admin, BASE, id, "8", csrf), 409);
        assertCode(submit(admin, BASE, UUID.randomUUID().toString(), "7", csrf), 409);
        assertThat(count()).isEqualTo(1); verifyNoInteractions(embedding, vectors);
    }

    @Test void metadataOutageIsVisibleWithoutTaskCreationOrAutomaticRetry() throws Exception {
        var admin = loggedIn("admin");
        doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "本机模型服务暂不可用，请核对原请求状态")).when(embedding).snapshot(any());
        var failed = submit(admin, BASE, UUID.randomUUID().toString(), "7", csrf(admin)); assertCode(failed, 503); safe(failed);
        assertThat(tree(failed).path("message").asText()).contains("暂不可用").doesNotContain("没有相关资料");
        assertThat(count()).isZero(); verify(embedding, times(1)).snapshot(any()); verifyNoInteractions(vectors);
    }

    @ParameterizedTest @ValueSource(strings = {"revision", "grant"})
    void metadataRecheckRejectsChangedSourceOrRevokedGrantBeforeInsert(String change) throws Exception {
        var admin = loggedIn("admin");
        doAnswer(call -> {
            outside(); DB.jdbc.update(change.equals("revision") ? "UPDATE rag_document SET revision=8 WHERE id=501"
                    : "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103"); return MODEL;
        }).when(embedding).snapshot(any());
        assertCode(submit(admin, BASE, UUID.randomUUID().toString(), "7", csrf(admin)), change.equals("revision") ? 409 : 404);
        assertThat(count()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void queuedExpiryAndLostLeaseAreFinalWithoutRenewingOriginalRequest() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0);
        long past = System.currentTimeMillis() - 5000;
        DB.jdbc.update("UPDATE rag_vector_job SET created_ms=?,deadline_ms=? WHERE id=?", past - 120_000, past, id);
        var expired = tree(get(admin, BASE + "/" + id)).path("data");
        assertThat(expired.path("status").asText()).isEqualTo("TIMED_OUT");
        clearInvocations(embedding);
        assertThat(tree(submit(admin, BASE, id, "7", csrf)).path("data").path("deadlineMs").asLong()).isEqualTo(past);
        String next = UUID.randomUUID().toString(); assertCode(submit(admin, BASE, next, "7", csrf), 0);
        long now = System.currentTimeMillis();
        DB.jdbc.update("UPDATE rag_vector_job SET created_ms=? WHERE id=?", now - 10_000, next);
        assertThat(jobs.claim(next, UUID.randomUUID().toString(), now - 2000, now - 1000)).isEqualTo(1);
        var abandoned = tree(get(admin, BASE + "/" + next)).path("data");
        assertThat(abandoned.path("status").asText()).isEqualTo("FAILED");
        assertThat(abandoned.path("failureCode").asText()).isEqualTo("LEASE_LOST");
        assertThat(tree(submit(admin, BASE, next, "7", csrf)).path("data")).isEqualTo(abandoned);
        verify(embedding, times(1)).snapshot(any()); verifyNoInteractions(vectors);
    }

    @Test void deletedDocumentRetainsPrivateTaskReadAndCancellationButRejectsNewWork() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        assertCode(submit(admin, BASE, id, "7", csrf), 0); clearInvocations(embedding);
        DB.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=501");
        assertCode(get(admin, BASE + "/" + id), 0);
        assertThat(tree(post(admin, BASE + "/" + id + "/cancel", "{}", csrf)).path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertCode(submit(admin, BASE, UUID.randomUUID().toString(), "7", csrf), 404); verifyNoInteractions(embedding, vectors);
    }

    @Test void largeDocumentVersionAndRevisionKeepExactTextAcrossHttp() throws Exception {
        long document = 9_007_199_254_740_993L, version = document + 1;
        DB.jdbc.update("UPDATE rag_document SET id=?,revision=? WHERE id=501", document, document);
        DB.jdbc.update("UPDATE rag_document_version SET id=?,document_id=? WHERE id=701", version, document);
        DB.jdbc.update("UPDATE rag_document SET published_version_id=? WHERE id=?", version, document);
        DB.jdbc.update("UPDATE rag_chunk SET document_id=?,version_id=? WHERE id=1001", document, version);
        try {
            String path = BASE.replace("documents/501", "documents/" + document).replace("versions/701", "versions/" + version);
            var admin = loggedIn("admin"); var csrf = csrf(admin);
            var response = submit(admin, path, UUID.randomUUID().toString(), Long.toString(document), csrf); assertCode(response, 0);
            var data = tree(response).path("data");
            assertThat(data.path("documentId").asText()).isEqualTo("9007199254740993");
            assertThat(data.path("versionId").asText()).isEqualTo("9007199254740994");
            assertThat(data.path("expectedRevision").asText()).isEqualTo("9007199254740993");
        } finally { DB.jdbc.update("UPDATE rag_document SET id=501 WHERE id=?", document); }
    }

    @Test void concurrentHttpSameIdReturnsOneTaskAndSameDeadline() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); String id = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit(admin, BASE, id, "7", csrf));
            var second = pool.submit(() -> submit(admin, BASE, id, "7", csrf));
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertCode(a, 0); assertCode(b, 0); assertThat(tree(a).path("data")).isEqualTo(tree(b).path("data"));
            assertThat(count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        verifyNoInteractions(vectors);
    }

    @Test void invalidHttpMethodsMediaEmptyAndOversizedBodiesNeverCreateTask() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, BASE, "", csrf), 400);
        var oversized = post(admin, BASE, "x".repeat(16 * 1024 + 1), csrf);
        assertThat(oversized.statusCode()).isEqualTo(413); safe(oversized);
        var wrongMedia = admin.send(HttpRequest.newBuilder(uri(BASE)).header("Content-Type", "text/plain")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(wrongMedia.statusCode()).isEqualTo(415);
        assertThat(get(admin, BASE + "/" + UUID.randomUUID() + "/cancel").statusCode()).isEqualTo(405);
        assertThat(count()).isZero(); verifyNoInteractions(embedding, vectors);
    }

    @Test void allEntriesSuppressPayloadLogging() {
        var methods = java.util.Arrays.stream(KnowledgeVectorJobController.class.getDeclaredMethods())
                .filter(method -> List.of("submit", "read", "cancel").contains(method.getName())).toList();
        assertThat(methods).hasSize(3).allSatisfy(method -> assertThat(method.isAnnotationPresent(NoPayloadLog.class)).isTrue());
    }

    private static void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private int count() { return DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job", Integer.class); }
    private HttpResponse<String> submit(HttpClient client, String path, String id, String revision, JsonNode csrf) throws Exception {
        return post(client, path, "{\"requestId\":\"" + id + "\",\"expectedRevision\":\"" + revision + "\"}", csrf);
    }
    private void assertCode(HttpResponse<String> response, int code) throws Exception {
        assertThat(tree(response).path("code").asInt()).isEqualTo(code);
        if (code == 401 || code == 403) assertThat(response.statusCode()).isEqualTo(code);
        else if (code == 400) assertThat(response.statusCode()).isIn(200, 400);
        else assertThat(response.statusCode()).isEqualTo(200);
    }
    private void safe(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain(PASSWORD, "generation", "leaseToken", "modelName", "sourceSha256", "manifestSha256", "authVersion", "storage_key", "synthetic:1");
        assertThat(tree(response).has("timestamp")).isTrue();
    }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build(); }
    private HttpClient loggedIn(String username) throws Exception {
        var client = client(); var csrf = csrf(client);
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200); return client;
    }
    private JsonNode csrf(HttpClient client) throws Exception { return tree(get(client, "/api/auth/csrf")).path("data"); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> post(HttpClient client, String path, String body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
