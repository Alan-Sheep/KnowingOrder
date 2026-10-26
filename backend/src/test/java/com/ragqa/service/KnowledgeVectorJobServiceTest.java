package com.ragqa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.JacksonConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 隔离H2执行真实权限SQL、Mapper及事务；模型元数据由回环HTTP替身提供，无推理或向量写入。 */
class KnowledgeVectorJobServiceTest {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private static final AuthenticatedUserPrincipal READER = new AuthenticatedUserPrincipal(1, 1);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private volatile Runnable beforeShow = () -> { };
    private volatile int modelStatus = 200;
    private volatile String modelRevision = "a".repeat(64);
    private EnterpriseDatabase db;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeDocumentMapper documents;
    private KnowledgeVectorJobService service;
    private LocalEmbeddingClient embedding;
    private DataSourceTransactionManager manager;
    private EnterpriseMemberService members;
    private KnowledgeManagementMapper management;
    private HttpServer server;
    private ExecutorService httpWorkers;

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        String migration = Files.readString(Path.of("sql/migrations/20261019_04_knowledge_vector_jobs.sql"), StandardCharsets.UTF_8)
                .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                .replaceAll("(?i)CHARACTER SET ascii", "");
        try (var connection = db.dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
        }
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN')");
        for (var type : List.of(KnowledgeDocumentMapper.class, KnowledgeManagementMapper.class, KnowledgeVectorJobMapper.class))
            db.factory.getConfiguration().addMapper(type);
        var sql = new SqlSessionTemplate(db.factory);
        jobs = spy(sql.getMapper(KnowledgeVectorJobMapper.class));
        documents = spy(sql.getMapper(KnowledgeDocumentMapper.class));
        management = sql.getMapper(KnowledgeManagementMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        manager = new DataSourceTransactionManager(db.dataSource);
        httpWorkers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            try {
                metadataCalls.incrementAndGet();
                String path = exchange.getRequestURI().toString();
                JsonNode body;
                if ("/api/tags".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                    body = json.readTree("{\"models\":[{\"name\":\"synthetic:1\",\"model\":\"synthetic:1\",\"digest\":\"" + modelRevision + "\"}]}");
                } else if ("/api/show".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                    assertThat(json.readTree(exchange.getRequestBody()).path("model").textValue()).isEqualTo("synthetic:1");
                    beforeShow.run();
                    body = json.readTree("{\"capabilities\":[\"embedding\"],\"model_info\":{\"general.architecture\":\"bert\",\"bert.embedding_length\":2}}");
                } else { throw new AssertionError("禁止推理/下载或其他模型调用：" + path); }
                byte[] bytes = (modelStatus == 200 ? json.writeValueAsString(body) : "SECRET_UPSTREAM").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(modelStatus, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable ex) { serverFailure.compareAndSet(null, ex); }
            finally { exchange.close(); }
        });
        server.start();
        embedding = new LocalEmbeddingClient(json, true, "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic:1");
        service = fresh(true);
        seed(501, 701);
    }

    @AfterEach void close() throws Exception {
        if (server != null) server.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow();
        if (db != null) db.close();
        assertThat(serverFailure.get()).isNull();
    }

    private KnowledgeVectorJobService fresh(boolean enabled) {
        return new KnowledgeVectorJobService(jobs, management, documents, members, embedding, manager, enabled, clock);
    }
    private void seed(long document, long version) {
        if (document != 501) db.jdbc.update("INSERT INTO rag_document(id,enterprise_id,knowledge_space_id,title,source_type) VALUES(?,1,401,'虚构制度','UPLOAD')", document);
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7 WHERE id=?", document);
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
                  original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,chunk_count,created_by_member)
                VALUES(?,1,401,?,1,?,'虚构.md',?,?,?,100,'READY',2,103)
                """, version, document, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "b".repeat(64), "c".repeat(64));
        db.jdbc.update("INSERT INTO rag_chunk(document_id,version_id,chunk_index,content) VALUES(?,?,0,'虚构餐补'),(?,?,1,'虚构审批')",
                document, version, document, version);
    }
    private KnowledgeVectorJobService.Job submit() { return submit(501, 701, UUID.randomUUID().toString(), 7); }
    private KnowledgeVectorJobService.Job submit(long doc, long version, String id, long revision) {
        return service.submit(ADMIN, 1, 401, doc, version, new KnowledgeVectorJobService.Submit(id, revision));
    }
    private KnowledgeVectorJobService.Job read(String id) { return service.read(ADMIN, 1, 401, 501, 701, id); }
    private KnowledgeVectorJobService.Job cancel(String id) { return service.cancel(ADMIN, 1, 401, 501, 701, id); }
    private int count() { return db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job", Integer.class); }
    private void code(Runnable work, int expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(expected)).hasMessageNotContaining("SECRET");
    }
    private void grantReaderMaintain() {
        db.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE member_id=101 AND knowledge_space_id=401");
    }

    @Test void bindsTrustedDocumentModelAndNewGenerationWithoutPublishing() {
        var result = submit(); var stored = jobs.find(result.id());
        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(stored.enterpriseId()).isEqualTo(1); assertThat(stored.memberId()).isEqualTo(103);
        assertThat(stored.userId()).isEqualTo(3); assertThat(stored.authVersion()).isEqualTo(1);
        assertThat(stored.expectedRevision()).isEqualTo(7); assertThat(stored.expectedChunks()).isEqualTo(2);
        assertThat(stored.sourceSha256()).isEqualTo("b".repeat(64));
        assertThat(stored.modelName()).isEqualTo("synthetic:1"); assertThat(stored.modelRevision()).isEqualTo("a".repeat(64));
        assertThat(stored.dimensions()).isEqualTo(2); assertThat(stored.collectionName()).matches("ko_m_[a-f0-9]{64}");
        assertThat(stored.generation()).isNotEqualTo(result.id()); UUID.fromString(stored.generation());
        assertThat(stored.createdMs()).isEqualTo(now.get()); assertThat(stored.deadlineMs()).isEqualTo(now.get() + 120_000);
        assertThat(stored.requestSha256()).matches("[a-f0-9]{64}"); assertThat(stored.manifestSha256()).isNull();
        assertThat(db.jdbc.queryForObject("SELECT published_version_id FROM rag_document WHERE id=501", Long.class)).isNull();
        assertThat(metadataCalls.get()).isEqualTo(3); assertThat(count()).isEqualTo(1);
    }

    @Test void retryAfterRestartOrModelOutageReturnsOriginalTaskAndDeadline() {
        var first = submit(); now.addAndGet(1000); service = fresh(false); modelStatus = 503;
        var same = submit(501, 701, first.id(), 7);
        assertThat(same).isEqualTo(first); assertThat(count()).isEqualTo(1); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void largeResourceIdsKeepExactTextWhileEpochAndRevisionRemainNumbers() throws Exception {
        long document = 9_007_199_254_740_993L;
        long version = document + 1;
        seed(document, version);
        var result = submit(document, version, UUID.randomUUID().toString(), 7);
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        JsonNode output = builder.build().valueToTree(result);
        assertThat(output.path("documentId").isTextual()).isTrue();
        assertThat(output.path("documentId").textValue()).isEqualTo("9007199254740993");
        assertThat(output.path("versionId").isTextual()).isTrue();
        assertThat(output.path("versionId").textValue()).isEqualTo("9007199254740994");
        assertThat(output.path("createdMs").isIntegralNumber()).isTrue();
        assertThat(output.path("createdMs").longValue()).isEqualTo(now.get());
        assertThat(output.path("deadlineMs").isIntegralNumber()).isTrue();
        assertThat(output.path("expectedRevision").isIntegralNumber()).isTrue();
    }

    @Test void sameUuidChangedRevisionOrFingerprintConflictsWithoutModelCalls() {
        var first = submit(); code(() -> submit(501, 701, first.id(), 8), 409);
        db.jdbc.update("UPDATE rag_vector_job SET request_sha256=? WHERE id=?", "d".repeat(64), first.id());
        code(() -> submit(501, 701, first.id(), 7), 409);
        assertThat(metadataCalls.get()).isEqualTo(3); assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"", "bad", "' OR 1=1 --", "00000000-0000-4000-8000-00000000000A"})
    void invalidRequestIdIsRejectedBeforeDatabaseAndHttp(String id) {
        code(() -> submit(501, 701, id, 7), 400); assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
        verify(jobs, never()).lockGate();
    }

    @Test void nullAndInvalidArgumentsNeverReachModel() {
        code(() -> service.submit(ADMIN, 1, 401, 501, 701, null), 400);
        code(() -> service.submit(ADMIN, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(null, 7L)), 400);
        code(() -> service.submit(ADMIN, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(UUID.randomUUID().toString(), null)), 400);
        code(() -> submit(501, 701, UUID.randomUUID().toString(), 0), 400);
        code(() -> submit(-1, 701, UUID.randomUUID().toString(), 7), 400);
        code(() -> service.read(ADMIN, 0, 401, 501, 701, UUID.randomUUID().toString()), 400);
        code(() -> service.cancel(ADMIN, 1, -1, 501, 701, UUID.randomUUID().toString()), 400);
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @Test void anonymousOldAuthenticationAndReadOnlyMemberCannotTriggerModel() {
        var request = new KnowledgeVectorJobService.Submit(UUID.randomUUID().toString(), 7L);
        code(() -> service.submit(null, 1, 401, 501, 701, request), 401);
        code(() -> service.submit(new AuthenticatedUserPrincipal(3, 0), 1, 401, 501, 701, request), 401);
        code(() -> service.submit(READER, 1, 401, 501, 701, request), 404);
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @Test void adminWithoutExplicitMaintainGrantHasNoImplicitKnowledgeAccess() {
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        code(this::submit, 404); assertThat(metadataCalls.get()).isZero();
    }

    @Test void departmentGrantMustRemainValid() {
        db.jdbc.update("UPDATE rag_knowledge_grant SET member_id=NULL,department_id=11 WHERE member_id=103");
        var first = submit(); db.jdbc.update("UPDATE sys_department SET status=1 WHERE id=11");
        code(() -> read(first.id()), 404); code(() -> cancel(first.id()), 404);
        code(() -> submit(501, 701, first.id(), 7), 404);
        assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void crossEnterpriseSpaceDocumentVersionAndOtherOwnerAreHidden() {
        var first = submit(); grantReaderMaintain();
        code(() -> service.read(READER, 1, 401, 501, 701, first.id()), 404);
        code(() -> service.cancel(READER, 1, 401, 501, 701, first.id()), 404);
        code(() -> service.submit(READER, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(first.id(), 7L)), 404);
        code(() -> service.read(ADMIN, 2, 401, 501, 701, first.id()), 404);
        code(() -> service.read(ADMIN, 1, 402, 501, 701, first.id()), 404);
        code(() -> service.read(ADMIN, 1, 401, 502, 701, first.id()), 404);
        code(() -> service.read(ADMIN, 1, 401, 501, 702, first.id()), 404);
        assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"UPDATE sys_user SET auth_version=2 WHERE id=3",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=103", "UPDATE sys_enterprise SET status=1 WHERE id=1",
            "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103", "UPDATE rag_knowledge_space SET status=1 WHERE id=401"})
    void revocationCannotBeBypassedByOriginalUuid(String sql) {
        var first = submit(); db.jdbc.update(sql);
        assertThatThrownBy(() -> submit(501, 701, first.id(), 7)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> read(first.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cancel(first.id())).isInstanceOf(BusinessException.class);
        assertThat(jobs.find(first.id()).status()).isEqualTo("QUEUED"); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"UPDATE rag_document SET revision=8 WHERE id=501",
            "UPDATE rag_document_version SET parse_status='PENDING' WHERE id=701",
            "UPDATE rag_document_version SET chunk_count=3 WHERE id=701",
            "UPDATE rag_document_version SET publication_status='DELETED' WHERE id=701",
            "UPDATE rag_document SET deleted=1 WHERE id=501"})
    void badDocumentStateIsRejectedBeforeModel(String sql) {
        db.jdbc.update(sql); assertThatThrownBy(this::submit).isInstanceOf(BusinessException.class);
        assertThat(metadataCalls.get()).isZero(); assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1001})
    void versionChunkCountBoundsAreChecked(int count) {
        db.jdbc.update("UPDATE rag_document_version SET chunk_count=? WHERE id=701", count);
        code(this::submit, 409); assertThat(metadataCalls.get()).isZero();
    }

    @Test void missingOwnerBadDigestOrMismatchedActualChunksRejectAdmission() {
        db.jdbc.update("UPDATE rag_document SET owner_member_id=NULL WHERE id=501"); code(this::submit, 409);
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103 WHERE id=501");
        db.jdbc.update("UPDATE rag_document_version SET content_sha256='invalid' WHERE id=701"); code(this::submit, 409);
        db.jdbc.update("UPDATE rag_document_version SET content_sha256=? WHERE id=701", "b".repeat(64));
        db.jdbc.update("DELETE FROM rag_chunk WHERE version_id=701 AND chunk_index=1"); code(this::submit, 409);
        assertThat(metadataCalls.get()).isZero(); assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"PUBLISHED", "WITHDRAWN"})
    void indexingPublishedOrWithdrawnVersionDoesNotChangeItsPublication(String state) {
        db.jdbc.update("UPDATE rag_document_version SET publication_status=? WHERE id=701", state);
        var result = submit(); assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(db.jdbc.queryForObject("SELECT publication_status FROM rag_document_version WHERE id=701", String.class)).isEqualTo(state);
    }

    @Test void disabledVectorSupportDoesNotProbeModelOrCreateTask() {
        service = fresh(false); code(this::submit, 503);
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @Test void missingGateFailsClosedBeforeModel() {
        db.jdbc.update("DELETE FROM rag_vector_gate WHERE id=1"); code(this::submit, 503);
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429, 503})
    void modelFailuresDoNotCreateOrRetryTasks(int status) {
        modelStatus = status; code(this::submit, 503);
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isEqualTo(1);
    }

    @Test void revisionChangeDuringModelMetadataStopsCommitWithoutHoldingDocumentLock() {
        beforeShow = () -> db.jdbc.update("UPDATE rag_document SET revision=8 WHERE id=501");
        code(this::submit, 409); assertThat(count()).isZero(); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void permissionRevocationDuringMetadataStopsCommit() {
        beforeShow = () -> db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        code(this::submit, 404); assertThat(count()).isZero(); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void authenticationRevocationDuringMetadataStopsCommit() {
        beforeShow = () -> db.jdbc.update("UPDATE sys_user SET auth_version=2 WHERE id=3");
        code(this::submit, 401); assertThat(count()).isZero();
    }

    @Test void modelRevisionChangeDuringProbeCannotBindMadeUpRevision() {
        beforeShow = () -> modelRevision = "d".repeat(64);
        code(this::submit, 409); assertThat(count()).isZero();
    }

    @Test void twoTasksPerMemberAndSameVersionMutualExclusionAreEnforced() {
        seed(510, 710); seed(511, 711);
        var first = submit(); code(this::submit, 409);
        submit(510, 710, UUID.randomUUID().toString(), 7);
        code(() -> submit(511, 711, UUID.randomUUID().toString(), 7), 503);
        assertThat(metadataCalls.get()).isEqualTo(6);
        cancel(first.id()); assertThat(submit(511, 711, UUID.randomUUID().toString(), 7).status()).isEqualTo("QUEUED");
    }

    @Test void globalCapacityIsCheckedUnderGateBeforeHttp() {
        var first = submit();
        for (int i = 0; i < 63; i++) db.jdbc.update("""
                INSERT INTO rag_vector_job(id,generation,enterprise_id,space_id,document_id,version_id,member_id,user_id,
                  auth_version,expected_revision,request_sha256,source_sha256,collection_name,model_name,model_revision,
                  dimensions,expected_chunks,created_ms,deadline_ms)
                SELECT ?,?,enterprise_id,space_id,document_id,version_id,?,user_id,auth_version,expected_revision,
                  request_sha256,source_sha256,collection_name,model_name,model_revision,dimensions,expected_chunks,created_ms,deadline_ms
                FROM rag_vector_job WHERE id=?
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 900 + i, first.id());
        seed(510, 710); code(() -> submit(510, 710, UUID.randomUUID().toString(), 7), 503);
        assertThat(jobs.activeTotal()).isEqualTo(64); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void secondPhaseCapacityRecheckPreventsConcurrentMemberOversubscription() throws Exception {
        seed(510, 710); seed(511, 711);
        submit();
        var both = new CountDownLatch(2);
        beforeShow = () -> { both.countDown(); await(both); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> outcome(() -> submit(510, 710, UUID.randomUUID().toString(), 7)));
            var b = pool.submit(() -> outcome(() -> submit(511, 711, UUID.randomUUID().toString(), 7)));
            assertThat(List.of(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 503);
            assertThat(jobs.activeOwner(1, 103)).isEqualTo(2); assertThat(count()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentSameUuidIsOneTaskAcrossSeparateServiceInstances() throws Exception {
        var other = fresh(true); String id = UUID.randomUUID().toString();
        var both = new CountDownLatch(2); beforeShow = () -> { both.countDown(); await(both); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> submit(501, 701, id, 7));
            var b = pool.submit(() -> other.submit(ADMIN, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(id, 7L)));
            assertThat(a.get(8, TimeUnit.SECONDS)).isEqualTo(b.get(8, TimeUnit.SECONDS));
            assertThat(count()).isEqualTo(1); assertThat(metadataCalls.get()).isEqualTo(6);
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDifferentIdsForOneVersionAllowOnlyOneTask() throws Exception {
        var both = new CountDownLatch(2); beforeShow = () -> { both.countDown(); await(both); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> outcome(this::submit)); var b = pool.submit(() -> outcome(this::submit));
            assertThat(List.of(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 409);
            assertThat(count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void expiryDoesNotRenewDeadlineAndManualRetryGetsNewGeneration() {
        var first = submit(); var old = jobs.find(first.id()); now.addAndGet(120_000);
        var expired = read(first.id()); assertThat(expired.status()).isEqualTo("TIMED_OUT");
        assertThat(expired.deadlineMs()).isEqualTo(first.deadlineMs());
        assertThat(submit(501, 701, first.id(), 7).status()).isEqualTo("TIMED_OUT");
        assertThat(metadataCalls.get()).isEqualTo(3);
        var retry = submit(); assertThat(jobs.find(retry.id()).generation()).isNotEqualTo(old.generation());
        assertThat(retry.deadlineMs()).isEqualTo(now.get() + 120_000);
    }

    @Test void expiredRunningLeaseFailsWithoutReclaimOrAutomaticWriteReplay() {
        var first = submit();
        jobs.claim(first.id(), UUID.randomUUID().toString(), now.get(), now.get() + 1000);
        now.addAndGet(1000);
        assertThat(read(first.id()).failureCode()).isEqualTo("LEASE_LOST");
        assertThat(read(first.id()).status()).isEqualTo("FAILED");
        assertThat(submit(501, 701, first.id(), 7).status()).isEqualTo("FAILED");
        assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void cancelledTaskIsStableAndPublicViewHidesAllInternalSnapshots() throws Exception {
        var first = submit(); var result = cancel(first.id());
        assertThat(result.status()).isEqualTo("CANCELLED"); assertThat(cancel(first.id())).isEqualTo(result);
        assertThat(submit(501, 701, first.id(), 7)).isEqualTo(result);
        String output = json.writeValueAsString(read(first.id()));
        assertThat(output).doesNotContain("generation", "lease", "collection", "modelName", "sha256", "authVersion", "userId", "storageKey");
        assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void verifiedStatusExplicitlyDoesNotClaimPublishedReadyIndex() {
        var first = submit(); String token = UUID.randomUUID().toString();
        jobs.claim(first.id(), token, now.get(), now.get() + 10000);
        jobs.bindManifest(first.id(), token, "d".repeat(64), 2, now.get());
        jobs.verified(first.id(), token, "d".repeat(64), 2, now.get());
        var result = cancel(first.id()); assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.message()).contains("尚未发布"); assertThat(result.verifiedChunks()).isEqualTo(2);
    }

    @Test void deletedDocumentStillAllowsPrivateTerminalStatusButNotOtherOwners() {
        var first = submit(); db.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=501");
        assertThat(cancel(first.id()).status()).isEqualTo("CANCELLED");
        assertThat(submit(501, 701, first.id(), 7).status()).isEqualTo("CANCELLED");
        code(this::submit, 404); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void admissionTimeoutDuringMetadataRollsBackWithoutRenewingBudget() {
        beforeShow = () -> now.addAndGet(15_000);
        code(this::submit, 503); assertThat(count()).isZero();
    }

    @Test void clockRollbackAndInvalidClockFailClosed() {
        beforeShow = () -> now.decrementAndGet(); code(this::submit, 503); assertThat(count()).isZero();
        now.set(-1); code(this::submit, 503);
    }

    @Test void interruptedAdmissionPreservesFlagAndNeverTouchesQueue() {
        Thread.currentThread().interrupt();
        try { code(this::submit, 503); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @Test void ambientTransactionIsRejectedBeforeHttpRatherThanHoldingLocksAcrossProbe() {
        var outer = new TransactionTemplate(manager);
        outer.execute(status -> { code(this::submit, 503); return null; });
        assertThat(count()).isZero(); assertThat(metadataCalls.get()).isZero();
    }

    @Test void failureAfterInsertRollsBackTaskInsteadOfReportingAccepted() {
        doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("SECRET_DATABASE"); }).when(jobs).insert(any());
        code(this::submit, 503); assertThat(count()).isZero();
    }

    @Test void lateAdmissionAfterInsertRollsBackTask() {
        doAnswer(call -> { int result = (int) call.callRealMethod(); now.addAndGet(15_000); return result; }).when(jobs).insert(any());
        code(this::submit, 503); assertThat(count()).isZero();
    }

    @Test void committedTaskCanBeRecoveredAfterCallerLosesResponse() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> { submit(501, 701, id, 7); throw new IllegalStateException("合成客户端丢失回执"); })
                .isInstanceOf(IllegalStateException.class);
        service = fresh(true);
        assertThat(submit(501, 701, id, 7)).isEqualTo(read(id));
        assertThat(count()).isEqualTo(1); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void actualCommitThenLostAcknowledgementReturnsSafeErrorAndOriginalTaskIsRecoverable() {
        AtomicBoolean loseOnce = new AtomicBoolean(true);
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if (count() == 1 && loseOnce.compareAndSet(true, false)) throw new TransactionSystemException("SECRET_COMMIT_ACK");
            }
        };
        service = new KnowledgeVectorJobService(jobs, management, documents, members, embedding, uncertain, true, clock);
        String id = UUID.randomUUID().toString();
        code(() -> submit(501, 701, id, 7), 503);
        assertThat(count()).isEqualTo(1);
        long originalDeadline = jobs.find(id).deadlineMs();
        service = fresh(true); now.addAndGet(1000);
        assertThat(submit(501, 701, id, 7).deadlineMs()).isEqualTo(originalDeadline);
        assertThat(count()).isEqualTo(1); assertThat(metadataCalls.get()).isEqualTo(3);
    }

    @Test void freshRequestsUseModelBoundCollectionButNeverChangeExistingSnapshot() {
        var first = submit(); var old = jobs.find(first.id()); cancel(first.id());
        modelRevision = "d".repeat(64);
        assertThat(submit(501, 701, first.id(), 7).status()).isEqualTo("CANCELLED");
        assertThat(jobs.find(first.id()).modelRevision()).isEqualTo(old.modelRevision());
        var second = submit(); var changed = jobs.find(second.id());
        assertThat(changed.modelRevision()).isEqualTo(modelRevision);
        assertThat(changed.collectionName()).isNotEqualTo(old.collectionName());
        assertThat(changed.generation()).isNotEqualTo(old.generation());
        assertThat(metadataCalls.get()).isEqualTo(6);
    }

    @Test void failedFinalInsertionPreservesCommittedPreflightCleanup() {
        var first = submit(); now.addAndGet(120_000);
        // 预检已经提交过期清理；最终入队失败仅回滚本次受理，旧终态不可被还原为在途。
        doThrow(new DataAccessResourceFailureException("SECRET_INSERT")).when(jobs).insert(any());
        code(this::submit, 503);
        assertThat(count()).isEqualTo(1); assertThat(jobs.find(first.id()).status()).isEqualTo("TIMED_OUT");
    }

    @Test void gateContentionIsBoundedAndNeverTriggersMetadataOrAutomaticRetry() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jobs.lockGate(); locked.countDown(); await(release); return null;
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); code(this::submit, 503);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(metadataCalls.get()).isZero(); assertThat(count()).isZero();
            release.countDown(); blocker.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void unknownStoredFailureDoesNotLeakThroughPublicStatus() {
        var first = submit(); String token = UUID.randomUUID().toString();
        jobs.claim(first.id(), token, now.get(), now.get() + 10000);
        jobs.fail(first.id(), token, "INTERNAL_ERROR", now.get());
        db.jdbc.update("UPDATE rag_vector_job SET failure_code='SECRET_DATABASE' WHERE id=?", first.id());
        assertThat(read(first.id()).failureCode()).isEqualTo("INTERNAL_ERROR");
    }

    private static int outcome(Runnable work) {
        try { work.run(); return 0; } catch (BusinessException ex) { return ex.getCode(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
