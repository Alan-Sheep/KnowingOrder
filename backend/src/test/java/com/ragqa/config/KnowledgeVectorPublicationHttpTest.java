package com.ragqa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.controller.KnowledgeVectorPublicationController;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.mapper.KnowledgeVectorManifestMapper;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.rag.retrieval.VectorIndexManifest;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 随机回环端口的真实登录/CSRF/控制器/服务/SQL/事务；仅H2及虚构清单，外部依赖为方法替身。 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "knowingorder.qdrant.enabled=true", "knowingorder.embedding.enabled=false",
        "knowingorder.security.login-per-address-per-minute=1000", "knowingorder.security.login-global-per-minute=2000",
        "knowingorder.knowledge.synthetic-model-spaces=", "spring.sql.init.mode=never",
        "logging.level.com.ragqa=ERROR", "server.address=127.0.0.1"
})
class KnowledgeVectorPublicationHttpTest {
    // 仅隔离测试的虚构账号口令，不用于开发库或真实员工。
    private static final String PASSWORD = "Fictional-http-test#2026";
    private static final String SOURCE = "b".repeat(64);
    private static final String BASE = "/api/enterprises/1/knowledge-spaces/401/documents/501/vector-publication";
    private static final LocalEmbeddingClient.ModelSnapshot MODEL = new LocalEmbeddingClient.ModelSnapshot("synthetic:1", "a".repeat(64), 2);
    private static final EnterpriseDatabase DB = database();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private KnowledgeVectorJobMapper jobs;
    @Autowired private KnowledgeVectorManifestMapper manifests;
    @Autowired private PlatformTransactionManager manager;
    @MockBean private LocalEmbeddingClient embedding;
    @MockBean private QdrantVectorClient vectors;
    // 本模块只验发布入口，禁止调度器抢走用例刻意设置的非终态任务。
    @MockBean private KnowledgeVectorWorker worker;
    private String jobId;
    private VectorIndexManifest manifest;

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

    @BeforeEach void prepare() throws Exception {
        DB.jdbc.update("DELETE FROM rag_vector_publication");
        DB.jdbc.update("DELETE FROM rag_vector_manifest_entry");
        DB.jdbc.update("DELETE FROM rag_vector_job");
        DB.jdbc.update("DELETE FROM rag_chunk");
        DB.jdbc.update("DELETE FROM rag_document_version");
        DB.jdbc.update("DELETE FROM rag_knowledge_grant");
        DB.jdbc.update("UPDATE sys_user SET status=0,deleted=0,auth_version=1");
        DB.jdbc.update("UPDATE sys_enterprise SET status=0,permission_version=1");
        DB.jdbc.update("UPDATE sys_enterprise_member SET status=0,permission_version=1");
        DB.jdbc.update("UPDATE rag_knowledge_space SET status=0,permission_version=1");
        DB.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN'),(1,401,101,'READ')");
        DB.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=701,deleted=0 WHERE id=501");
        DB.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
                  original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,publication_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'虚构制度.md',?,?,?,100,'READY','PUBLISHED',1,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SOURCE, "c".repeat(64));
        String content = "虚构制度条目";
        DB.jdbc.update("INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content) VALUES(1001,501,701,0,?)", content);
        long now = System.currentTimeMillis();
        jobId = UUID.randomUUID().toString(); String generation = UUID.randomUUID().toString(), lease = UUID.randomUUID().toString();
        jobs.insert(new Stored(jobId, generation, 1, 401, 501, 701, 103, 3, 1, 7, "c".repeat(64), SOURCE,
                "ko_publish_http", MODEL.model(), MODEL.revision(), 2, 1, "QUEUED", null, null, null, 0,
                now - 30_000, now + 90_000, null, null));
        assertThat(jobs.claim(jobId, lease, now - 20_000, now + 80_000)).isEqualTo(1);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        manifest = VectorIndexManifest.capture(new QdrantVectorClient.Index("ko_publish_http", MODEL.model(), MODEL.revision(), 2),
                new QdrantVectorClient.Scope(1, 401, 501, 701, generation), 7, SOURCE, 1,
                List.of(new QdrantVectorClient.Point(1001, hash, new Vector(MODEL.model(), List.of(3.0, 4.0)))));
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.execute(status -> manifests.persist(jobId, lease, manifest, Clock.fixed(Instant.ofEpochMilli(now - 10_000), ZoneOffset.UTC)));
        assertThat(jobs.verified(jobId, lease, manifest.sha256(), 1, now - 1000)).isEqualTo(1);
        when(embedding.snapshot(any())).thenAnswer(call -> { outside(); return MODEL; });
        when(vectors.read(any(), any(), anyList(), any())).thenAnswer(call -> {
            outside(); assertThat(call.getArgument(2, List.class)).containsExactly(1001L); return manifest.expected();
        });
    }

    @AfterAll static void close() throws Exception { DB.close(); }

    @Test void anonymousForgedHeadersAndMissingCsrfCannotReachPublication() throws Exception {
        var anonymous = client();
        var response = get(anonymous, BASE);
        assertThat(response.statusCode()).isEqualTo(401); safe(response);
        var forged = anonymous.send(HttpRequest.newBuilder(uri(BASE)).header("X-User-Id", "3").header("X-Member-Id", "103")
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(forged.statusCode()).isEqualTo(401);
        JsonNode csrf = csrf(anonymous);
        for (String action : List.of("publish", "withdraw")) {
            assertThat(post(anonymous, BASE + "/" + action, "{}", csrf).statusCode()).isEqualTo(401);
            assertThat(post(anonymous, BASE + "/" + action, "{}", null).statusCode()).isEqualTo(403);
        }
        var admin = loggedIn("admin");
        assertThat(post(admin, BASE + "/publish", publishBody("0"), null).statusCode()).isEqualTo(403);
        assertThat(post(admin, BASE + "/withdraw", withdrawBody("0", null), null).statusCode()).isEqualTo(403);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void publishRefreshIdempotentRetryWithdrawAndTombstoneUseTheSameHttpContract() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        var initial = tree(get(admin, BASE)).path("data");
        assertThat(initial.path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(initial.path("revision").isTextual()).isTrue();
        var response = post(admin, BASE + "/publish", publishBody("0"), csrf); safe(response);
        var state = tree(response).path("data");
        assertThat(state.path("documentId").asText()).isEqualTo("501");
        assertThat(state.path("revision").asText()).isEqualTo("1");
        assertThat(state.path("status").asText()).isEqualTo("READY");
        assertThat(tree(get(admin, BASE)).path("data")).isEqualTo(state);
        assertThat(tree(post(admin, BASE + "/publish", publishBody("0"), csrf)).path("data")).isEqualTo(state);
        assertThat(tree(post(admin, BASE + "/withdraw", withdrawBody("1", jobId), csrf)).path("data").path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(tree(post(admin, BASE + "/withdraw", withdrawBody("1", jobId), csrf)).path("data").path("revision").asText()).isEqualTo("2");
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), 409);
        verify(embedding, times(4)).snapshot(any());
        verify(vectors, times(2)).read(any(), any(), anyList(), any());
        verify(embedding, never()).embed(anyList(), any());
        verify(vectors, never()).upsert(any(), any(), anyList(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "peer"})
    void readOnlyAndUnGrantedMembersCannotReadPublishOrWithdraw(String username) throws Exception {
        var employee = loggedIn(username); var csrf = csrf(employee);
        assertCode(get(employee, BASE), 404);
        assertCode(post(employee, BASE + "/publish", publishBody("0"), csrf), 404);
        assertCode(post(employee, BASE + "/withdraw", withdrawBody("0", null), csrf), 404);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @ParameterizedTest @ValueSource(strings = {"enterprise", "space", "document", "taskOwner", "taskSpace", "taskEnterprise"})
    void identifiersCannotCrossResourceOrTaskOwnerBoundaries(String changed) throws Exception {
        String path = switch (changed) {
            case "enterprise" -> BASE.replace("enterprises/1", "enterprises/2");
            case "space" -> BASE.replace("spaces/401", "spaces/402");
            case "document" -> BASE.replace("documents/501", "documents/502");
            default -> BASE;
        };
        switch (changed) {
            case "taskOwner" -> DB.jdbc.update("UPDATE rag_vector_job SET member_id=101,user_id=1");
            case "taskSpace" -> DB.jdbc.update("UPDATE rag_vector_job SET space_id=402");
            case "taskEnterprise" -> DB.jdbc.update("UPDATE rag_vector_job SET enterprise_id=2");
            default -> { }
        }
        var admin = loggedIn("admin");
        assertCode(post(admin, path + "/publish", publishBody("0"), csrf(admin)), 404);
        if (!changed.startsWith("task")) {
            assertCode(get(admin, path), 404);
            // 复用仓储契约：维护者已核权，但文档不属于该空间时，撤回按来源冲突拒绝且不写墓碑。
            assertCode(post(admin, path + "/withdraw", withdrawBody("0", null), csrf(admin)), changed.equals("document") ? 409 : 404);
        }
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void administratorDoesNotBypassExplicitGrantAndGrantRevocationAppliesImmediately() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        DB.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        assertCode(get(admin, BASE), 404);
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), 404);
        assertCode(post(admin, BASE + "/withdraw", withdrawBody("0", null), csrf), 404);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @ParameterizedTest @ValueSource(strings = {"account", "authVersion", "member", "enterprise", "space"})
    void existingSessionCannotBypassDisabledOrRevokedIdentity(String changed) throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        DB.jdbc.update(switch (changed) {
            case "account" -> "UPDATE sys_user SET status=1 WHERE id=3";
            case "authVersion" -> "UPDATE sys_user SET auth_version=2 WHERE id=3";
            case "member" -> "UPDATE sys_enterprise_member SET status=1 WHERE id=103";
            case "enterprise" -> "UPDATE sys_enterprise SET status=1 WHERE id=1";
            default -> "UPDATE rag_knowledge_space SET status=1 WHERE id=401";
        });
        int expected = changed.equals("account") || changed.equals("authVersion") ? 401 : 404;
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), expected);
        assertCode(get(admin, BASE), expected);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "0", "1.5", "true", "{}", "[]", "\"\"", "\"-1\"",
            "\"01\"", "\"+1\"", "\" 1\"", "\"1e2\"", "\"9223372036854775807\"", "\"9223372036854775808\""})
    void invalidRevisionTypesAndOverflowCannotWrite(String revision) throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, BASE + "/publish", "{\"jobId\":\"" + jobId + "\",\"expectedRevision\":" + revision + "}", csrf), 400);
        assertCode(post(admin, BASE + "/withdraw", "{\"expectedJobId\":null,\"expectedRevision\":" + revision + "}", csrf), 400);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "{", "{\"jobId\":null,\"expectedRevision\":\"0\"}",
            "{\"jobId\":2,\"expectedRevision\":\"0\"}", "{\"jobId\":\"../bad\",\"expectedRevision\":\"0\"}",
            "{\"jobId\":\"bad\",\"expectedRevision\":\"0\",\"expectedRevision\":\"1\"}",
            "{\"jobId\":\"bad\",\"expectedRevision\":\"0\"} {}"})
    void malformedAndAmbiguousJsonIsRejectedWithoutExternalCalls(String body) throws Exception {
        var admin = loggedIn("admin");
        assertCode(post(admin, BASE + "/publish", body, csrf(admin)), 400);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void forgedIdentityAndManifestFieldsAreNotAcceptedAsAuthority() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        for (String extra : List.of("memberId", "userId", "enterpriseId", "manifest", "generation", "allowExternal")) {
            String body = publishBody("0"); body = body.substring(0, body.length() - 1) + ",\"" + extra + "\":true}";
            assertCode(post(admin, BASE + "/publish", body, csrf), 400);
        }
        assertCode(post(admin, BASE + "/withdraw", "{\"expectedRevision\":\"0\"}", csrf), 400);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void emptyOversizedWrongMediaAndWrongMethodsCannotChangePointer() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, BASE + "/publish", "", csrf), 400);
        var oversized = post(admin, BASE + "/publish", "x".repeat(16 * 1024 + 1), csrf);
        assertThat(oversized.statusCode()).isEqualTo(413); safe(oversized);
        var wrongMedia = admin.send(HttpRequest.newBuilder(uri(BASE + "/publish")).header("Content-Type", "text/plain")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(publishBody("0"))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(wrongMedia.statusCode()).isEqualTo(415);
        assertThat(get(admin, BASE + "/publish").statusCode()).isEqualTo(405);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void staleStateWithdrawalAndLargeRevisionRemainExactWithoutVectorService() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), 0);
        DB.jdbc.update("UPDATE rag_document SET revision=8 WHERE id=501");
        DB.jdbc.update("UPDATE rag_vector_publication SET revision=9007199254740993 WHERE document_id=501");
        clearInvocations(embedding, vectors);
        var state = tree(get(admin, BASE)).path("data");
        assertThat(state.path("status").asText()).isEqualTo("STALE");
        assertThat(state.path("revision").asText()).isEqualTo("9007199254740993");
        var withdrawn = tree(post(admin, BASE + "/withdraw", withdrawBody("9007199254740993", jobId), csrf)).path("data");
        assertThat(withdrawn.path("revision").asText()).isEqualTo("9007199254740994");
        assertThat(withdrawn.path("status").asText()).isEqualTo("WITHDRAWN");
        verifyNoInteractions(embedding, vectors);
    }

    @Test void upstreamFailureIsVisibleAndDoesNotPretendToBeNoEvidenceOrRetry() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "向量服务暂不可用，请先查询原指针"))
                .when(vectors).read(any(), any(), anyList(), any());
        var failed = post(admin, BASE + "/publish", publishBody("0"), csrf);
        assertCode(failed, 503); safe(failed);
        assertThat(tree(failed).path("message").asText()).contains("查询原指针").doesNotContain("没有相关资料");
        assertThat(tree(get(admin, BASE)).path("data").path("status").asText()).isEqualTo("UNPUBLISHED");
        verify(vectors, times(1)).read(any(), any(), anyList(), any()); assertNoPublication();
    }

    @Test void grantRevokedInsideExternalCheckPreventsPublicationThroughHttp() throws Exception {
        var admin = loggedIn("admin");
        doAnswer(call -> {
            outside(); DB.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103"); return manifest.expected();
        }).when(vectors).read(any(), any(), anyList(), any());
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf(admin)), 404); assertNoPublication();
    }

    @Test void withdrawalBeforeFirstPublishCreatesAnIdempotentTombstoneAndBlocksLatePublish() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        var first = post(admin, BASE + "/withdraw", withdrawBody("0", null), csrf); assertCode(first, 0);
        assertThat(tree(first).path("data").path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(tree(first).path("data").path("revision").asText()).isEqualTo("1");
        assertThat(tree(post(admin, BASE + "/withdraw", withdrawBody("0", null), csrf)).path("data")).isEqualTo(tree(first).path("data"));
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), 409);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication WHERE job_id IS NOT NULL", Integer.class)).isZero();
        verifyNoInteractions(embedding, vectors);
    }

    @Test void anotherCurrentMaintainerCanWithdrawButCannotPublishSomebodyElsesJob() throws Exception {
        var admin = loggedIn("admin");
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf(admin)), 0);
        DB.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE member_id=101");
        clearInvocations(embedding, vectors);
        var owner = loggedIn("owner"); var csrf = csrf(owner);
        assertCode(post(owner, BASE + "/publish", publishBody("1"), csrf), 404);
        assertCode(post(owner, BASE + "/withdraw", withdrawBody("1", jobId), csrf), 0);
        assertThat(tree(get(owner, BASE)).path("data").path("status").asText()).isEqualTo("WITHDRAWN");
        verifyNoInteractions(embedding, vectors);
    }

    @Test void deletedDocumentStillAllowsAuthorizedWithdrawalOfResidualPointer() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf), 0);
        DB.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=501");
        clearInvocations(embedding, vectors);
        assertCode(post(admin, BASE + "/withdraw", withdrawBody("1", jobId), csrf), 0);
        assertThat(tree(get(admin, BASE)).path("data").path("status").asText()).isEqualTo("WITHDRAWN");
        verifyNoInteractions(embedding, vectors);
    }

    @Test void logoutInvalidatesPublicationAccessWithoutReusingOldSession() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin);
        assertCode(post(admin, "/api/auth/logout", "{}", csrf), 0);
        assertCode(get(admin, BASE), 401);
        assertCode(post(admin, BASE + "/publish", publishBody("0"), csrf(admin)), 401);
        verifyNoInteractions(embedding, vectors); assertNoPublication();
    }

    @Test void simultaneousSameRevisionHttpRequestsAreIdempotentRatherThanDoublePublished() throws Exception {
        var admin = loggedIn("admin"); var csrf = csrf(admin); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> post(admin, BASE + "/publish", publishBody("0"), csrf));
            var second = pool.submit(() -> post(admin, BASE + "/publish", publishBody("0"), csrf));
            assertThat(tree(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).path("data").path("revision").asText()).isEqualTo("1");
            assertThat(tree(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).path("data").path("revision").asText()).isEqualTo("1");
            assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void everyEntrySuppressesPayloadLogging() {
        var entries = java.util.Arrays.stream(KnowledgeVectorPublicationController.class.getDeclaredMethods())
                .filter(method -> List.of("read", "publish", "withdraw").contains(method.getName())).toList();
        assertThat(entries).hasSize(3).allSatisfy(method -> assertThat(method.isAnnotationPresent(NoPayloadLog.class)).isTrue());
    }

    private static void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private void assertNoPublication() { assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication", Integer.class)).isZero(); }
    private String publishBody(String revision) { return "{\"jobId\":\"" + jobId + "\",\"expectedRevision\":\"" + revision + "\"}"; }
    private String withdrawBody(String revision, String job) { return "{\"expectedJobId\":" + (job == null ? "null" : "\"" + job + "\"") + ",\"expectedRevision\":\"" + revision + "\"}"; }
    private void assertCode(HttpResponse<String> response, int code) throws Exception {
        assertThat(tree(response).path("code").asInt()).isEqualTo(code);
        if (code == 401) assertThat(response.statusCode()).isEqualTo(401);
        else if (code == 400) assertThat(response.statusCode()).isIn(200, 400);
        else assertThat(response.statusCode()).isEqualTo(200);
    }
    private void safe(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain(PASSWORD, "sourceSha256", "manifestSha256", "generation", "leaseToken", "storage_key", "authVersion", "synthetic:1");
        assertThat(tree(response).has("timestamp")).isTrue();
    }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build(); }
    private HttpClient loggedIn(String name) throws Exception {
        var client = client(); var csrf = csrf(client);
        String body = "username=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
