package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.config.JacksonConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2实际权限/来源SQL和事务，模型/Qdrant为方法替身；仅虚构制度，不是实库或生产验收。 */
class KnowledgeVectorPublicationServiceTest {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private static final String SOURCE = "b".repeat(64);
    private static final LocalEmbeddingClient.ModelSnapshot MODEL = new LocalEmbeddingClient.ModelSnapshot("synthetic:1", "a".repeat(64), 2);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private EnterpriseDatabase db;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private KnowledgeVectorPublicationMapper publications;
    private KnowledgeManagementMapper management;
    private EnterpriseMemberService members;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private LocalEmbeddingClient embedding;
    private QdrantVectorClient vectors;
    private KnowledgeVectorPublicationService service;
    private Stored job;
    private VectorIndexManifest manifest;
    private Runnable onSnapshot = () -> { };
    private Runnable onRead = () -> { };
    private final Map<Long, QdrantVectorClient.Stored> actual = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger snapshots = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        try (var connection = db.dataSource.getConnection()) {
            for (String name : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql",
                    "20261020_02_knowledge_vector_publication.sql")) {
                String script = Files.readString(Path.of("sql/migrations/" + name), StandardCharsets.UTF_8)
                        .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                        .replaceAll("(?i)CHARACTER SET ascii", "");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (var type : List.of(KnowledgeVectorJobMapper.class, KnowledgeVectorManifestMapper.class,
                KnowledgeVectorPublicationMapper.class, KnowledgeManagementMapper.class)) db.factory.getConfiguration().addMapper(type);
        var sql = new SqlSessionTemplate(db.factory);
        jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
        manifests = sql.getMapper(KnowledgeVectorManifestMapper.class);
        publications = sql.getMapper(KnowledgeVectorPublicationMapper.class);
        management = sql.getMapper(KnowledgeManagementMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        manager = new DataSourceTransactionManager(db.dataSource);
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN')");
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=701 WHERE id=501");
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
                  original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,publication_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'虚构制度.md',?,?,?,100,'READY','PUBLISHED',17,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SOURCE, "c".repeat(64));
        seed(17);
        embedding = mock(LocalEmbeddingClient.class); vectors = mock(QdrantVectorClient.class);
        when(embedding.snapshot(any())).thenAnswer(call -> {
            outside(); snapshots.incrementAndGet(); onSnapshot.run(); return MODEL;
        });
        when(vectors.read(any(), any(), anyList(), any())).thenAnswer(call -> {
            outside(); reads.incrementAndGet(); onRead.run();
            List<Long> ids = call.getArgument(2);
            assertThat(ids).hasSizeBetween(1, 16);
            assertThat(call.getArgument(0, QdrantVectorClient.Index.class)).isEqualTo(manifest.index());
            assertThat(call.getArgument(1, QdrantVectorClient.Scope.class)).isEqualTo(manifest.scope());
            return ids.stream().map(actual::get).filter(Objects::nonNull).toList();
        });
        service = fresh(manager, true);
    }

    private void seed(int count) throws Exception {
        db.jdbc.update("DELETE FROM rag_chunk WHERE document_id=501");
        db.jdbc.update("UPDATE rag_document_version SET chunk_count=? WHERE id=701", count);
        String id = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jobs.insert(new Stored(id, generation, 1, 401, 501, 701, 103, 3, 1, 7, "c".repeat(64), SOURCE,
                "ko_publish_service", MODEL.model(), MODEL.revision(), MODEL.dimensions(), count, "QUEUED", null, null, null, 0,
                now.get() - 50_000, now.get() + 70_000, null, null));
        assertThat(jobs.claim(id, token, now.get() - 40_000, now.get() + 60_000)).isEqualTo(1);
        var points = new ArrayList<QdrantVectorClient.Point>();
        for (int n = 0; n < count; n++) {
            String content = "虚构制度条目" + n;
            db.jdbc.update("INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line) "
                    + "VALUES(?,501,701,?,?,0,8,1,1)", 1001 + n, n, content);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
            points.add(new QdrantVectorClient.Point(1001 + n, hash, new Vector(MODEL.model(), List.of(3.0, 4.0))));
        }
        manifest = VectorIndexManifest.capture(new QdrantVectorClient.Index("ko_publish_service", MODEL.model(), MODEL.revision(), 2),
                new QdrantVectorClient.Scope(1, 401, 501, 701, generation), 7, SOURCE, count, points);
        tx.execute(status -> manifests.persist(id, token, manifest, fixed(now.get() - 20_000)));
        assertThat(jobs.verified(id, token, manifest.sha256(), count, now.get() - 1000)).isEqualTo(1);
        job = jobs.find(id); actual.clear();
        for (var row : manifest.expected()) actual.put(row.chunkId(), row);
    }

    @AfterEach void close() throws Exception { Thread.interrupted(); if (db != null) db.close(); }
    private KnowledgeVectorPublicationService fresh(PlatformTransactionManager transactionManager, boolean enabled) {
        return new KnowledgeVectorPublicationService(jobs, publications, management, members, embedding, vectors,
                transactionManager, enabled, clock);
    }
    private KnowledgeVectorPublicationService.State publish(long revision) {
        return service.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish(job.id(), revision));
    }
    private KnowledgeVectorPublicationService.State read() { return service.read(ADMIN, 1, 401, 501); }
    private KnowledgeVectorPublicationService.State withdraw(long revision, String expectedJob) {
        return service.withdraw(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Withdraw(expectedJob, revision));
    }
    private static void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static Clock fixed(long time) { return Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC); }
    private void code(Runnable work, int code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
    private void notPublished() {
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication WHERE job_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jobs.find(job.id()).status()).isEqualTo("VERIFIED");
    }

    @ParameterizedTest @ValueSource(ints = {1, 16, 17, 1000})
    void authorizedPublishRechecksAllPointsOutsideTransactionsAndExposesSafeState(int count) throws Exception {
        if (count != 17) seed(count);
        assertThat(read().status()).isEqualTo("UNPUBLISHED");
        var state = publish(0);
        assertThat(state.status()).isEqualTo("READY"); assertThat(state.revision()).isEqualTo(1);
        assertThat(state.jobId()).isEqualTo(job.id()); assertThat(read()).isEqualTo(state);
        assertThat(reads.get()).isEqualTo((count + 15) / 16); assertThat(snapshots.get()).isEqualTo(2);
        assertThat(jobs.find(job.id())).isEqualTo(job);
        verify(embedding, never()).embed(anyList(), any());
        verify(vectors, never()).upsert(any(), any(), anyList(), any());
        verify(vectors, never()).create(any(), any());
        var builder = new Jackson2ObjectMapperBuilder(); new JacksonConfig().jacksonCustomizer().customize(builder);
        String json = builder.build().writeValueAsString(state);
        assertThat(json).doesNotContain("generation", "sha256", "model", "lease", "synthetic", "storage", "authVersion");
    }

    @Test void defaultDisabledDoesNotReadDatabaseOrCallExternalServices() {
        var disabledJobs = mock(KnowledgeVectorJobMapper.class);
        var disabledPublications = mock(KnowledgeVectorPublicationMapper.class);
        var disabledManagement = mock(KnowledgeManagementMapper.class);
        var disabledMembers = mock(EnterpriseMemberService.class);
        var disabled = new KnowledgeVectorPublicationService(disabledJobs, disabledPublications, disabledManagement,
                disabledMembers, embedding, vectors, manager, false, clock);
        code(() -> disabled.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 503);
        verifyNoInteractions(disabledJobs, disabledPublications, disabledManagement, disabledMembers, embedding, vectors);
    }

    @Test void idempotentPublishStillRevalidatesModelAndAllPoints() {
        var first = publish(0);
        assertThat(publish(0)).isEqualTo(first);
        assertThat(publish(1)).isEqualTo(first);
        assertThat(reads.get()).isEqualTo(6); assertThat(snapshots.get()).isEqualTo(6);
        code(() -> publish(2), 409);
        assertThat(read().revision()).isEqualTo(1);
    }

    @Test void withdrawalAndStatusRemainAvailableWhenServicesAreDisabledOrBroken() {
        publish(0); clearInvocations(embedding, vectors);
        service = fresh(manager, false);
        assertThat(read().status()).isEqualTo("READY");
        var removed = withdraw(1, job.id());
        assertThat(removed.status()).isEqualTo("WITHDRAWN"); assertThat(removed.revision()).isEqualTo(2);
        assertThat(withdraw(1, job.id())).isEqualTo(removed);
        assertThat(read()).isEqualTo(removed);
        verifyNoInteractions(embedding, vectors);
    }

    @Test void absentWithdrawalFencesDelayedFirstPublish() {
        assertThat(withdraw(0, null).revision()).isEqualTo(1);
        code(() -> publish(0), 409); verifyNoInteractions(embedding, vectors);
        assertThat(publish(1).revision()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE sys_user SET status=1 WHERE id=3",
            "UPDATE sys_user SET auth_version=2 WHERE id=3",
            "UPDATE sys_enterprise SET status=1 WHERE id=1",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=103",
            "UPDATE rag_knowledge_space SET status=1 WHERE id=401",
            "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103",
            "UPDATE rag_knowledge_grant SET permission='READ' WHERE member_id=103"})
    void invalidIdentityOrMissingMaintainBlocksAllOperationsWithoutExternalCalls(String mutation) {
        db.jdbc.update(mutation);
        code(() -> publish(0), mutation.contains("sys_user") ? 401 : 404);
        code(() -> read(), mutation.contains("sys_user") ? 401 : 404);
        code(() -> withdraw(0, null), mutation.contains("sys_user") ? 401 : 404);
        verifyNoInteractions(embedding, vectors); notPublished();
    }

    @Test void readPermissionAndAdminRoleDoNotImplicitlyGrantPublishing() {
        db.jdbc.update("DELETE FROM rag_knowledge_grant WHERE member_id=103");
        code(() -> publish(0), 404);
        code(() -> service.publish(new AuthenticatedUserPrincipal(1, 1), 1, 401, 501,
                new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 404);
        verifyNoInteractions(embedding, vectors);
    }

    @Test void crossEnterpriseSpaceDocumentAndOtherMembersTaskStayHidden() {
        code(() -> service.publish(ADMIN, 2, 401, 501, new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 404);
        code(() -> service.publish(ADMIN, 1, 402, 501, new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 404);
        code(() -> service.publish(ADMIN, 1, 401, 503, new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 404);
        db.jdbc.update("UPDATE rag_vector_job SET member_id=101,user_id=1 WHERE id=?", job.id());
        code(() -> publish(0), 404); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_document SET revision=8 WHERE id=501",
            "UPDATE rag_document SET deleted=1 WHERE id=501",
            "UPDATE rag_document SET published_version_id=NULL WHERE id=501",
            "UPDATE rag_document_version SET publication_status='DRAFT' WHERE id=701",
            "UPDATE rag_document_version SET valid_until_ms=1800000000000 WHERE id=701",
            "UPDATE rag_document_version SET content_sha256=REPEAT('d',64) WHERE id=701",
            "UPDATE rag_vector_job SET auth_version=2",
            "UPDATE rag_vector_job SET status='FAILED',verified_chunks=0",
            "DELETE FROM rag_vector_manifest_entry WHERE chunk_id=1001",
            "UPDATE rag_vector_manifest_entry SET vector_sha256=REPEAT('d',64) WHERE chunk_id=1001",
            "UPDATE rag_vector_job SET manifest_sha256=REPEAT('d',64)"})
    void preflightRefusesStaleTaskSourceOrDamagedSealedManifest(String mutation) {
        db.jdbc.update(mutation); code(() -> publish(0), 409); verifyNoInteractions(embedding, vectors);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103",
            "UPDATE sys_enterprise_member SET permission_version=2 WHERE id=103",
            "UPDATE sys_user SET auth_version=2 WHERE id=3"})
    void revocationDuringModelMetadataStopsBeforeFirstPointRead(String mutation) {
        onSnapshot = () -> db.jdbc.update(mutation);
        code(() -> publish(0), mutation.contains("sys_user") ? 401 : 404);
        assertThat(reads.get()).isZero(); notPublished();
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_document SET revision=8 WHERE id=501",
            "UPDATE rag_document_version SET publication_status='WITHDRAWN' WHERE id=701",
            "UPDATE rag_vector_job SET model_revision=REPEAT('d',64)",
            "UPDATE rag_vector_manifest_entry SET vector_sha256=REPEAT('d',64) WHERE chunk_id=1001",
            "UPDATE rag_chunk SET content='篡改后的正文' WHERE id=1001"})
    void changesDuringVectorReadCannotPassFinalPublication(String mutation) {
        onRead = () -> { if (reads.get() == 1) db.jdbc.update(mutation); };
        code(() -> publish(0), 409); notPublished();
    }

    @Test void revocationBetweenVectorBatchesStopsFurtherExternalCalls() {
        onRead = () -> db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        code(() -> publish(0), 404);
        assertThat(reads.get()).isEqualTo(1); notPublished();
    }

    @Test void missingPointOrEqualCountWithWrongDigestNeverPublishes() {
        actual.remove(1001L); code(() -> publish(0), 409); notPublished();
        actual.put(1001L, new QdrantVectorClient.Stored(1001, "d".repeat(64), manifest.expected().get(0).vectorSha256()));
        code(() -> publish(0), 409); notPublished();
    }

    @Test void changedModelBeforeOrAfterReadNeverPublishes() {
        var changed = new LocalEmbeddingClient.ModelSnapshot("synthetic:1", "d".repeat(64), 2);
        doReturn(changed).when(embedding).snapshot(any());
        code(() -> publish(0), 409); assertThat(reads.get()).isZero();
        doReturn(MODEL, changed).when(embedding).snapshot(any());
        code(() -> publish(0), 409); assertThat(reads.get()).isEqualTo(2); notPublished();
    }

    @Test void nullModelAndExternalUnexpectedExceptionAreSafeAndDoNotRetry() {
        doReturn(null).when(embedding).snapshot(any()); code(() -> publish(0), 409);
        doThrow(new IllegalStateException("第三方内部凭据与正文不得外露")).when(embedding).snapshot(any());
        assertThatThrownBy(() -> publish(0)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503);
            assertThat(ex.getMessage()).doesNotContain("凭据", "正文");
        });
        verify(embedding, times(2)).snapshot(any()); assertThat(reads.get()).isZero(); notPublished();
    }

    @Test void vectorOutageIsNotReportedAsNoEvidenceAndDoesNotRetry() {
        doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "向量服务暂不可用")).when(vectors).read(any(), any(), anyList(), any());
        code(() -> publish(0), 503); verify(vectors, times(1)).read(any(), any(), anyList(), any()); notPublished();
    }

    @ParameterizedTest @ValueSource(longs = {-1, 15_000})
    void backwardsClockOrExpiredTotalBudgetStopsBeforePointRead(long shift) {
        onSnapshot = () -> now.addAndGet(shift);
        code(() -> publish(0), 503); assertThat(reads.get()).isZero(); notPublished();
    }

    @Test void interruptPreservesFlagAndNeverPublishes() {
        onRead = () -> Thread.currentThread().interrupt();
        code(() -> publish(0), 503); assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); notPublished();
    }

    @Test void externalWaitCannotRunInsideCallersTransaction() {
        code(() -> tx.execute(status -> publish(0)), 503);
        code(() -> tx.execute(status -> read()), 503);
        code(() -> tx.execute(status -> withdraw(0, null)), 503);
        verifyNoInteractions(embedding, vectors);
    }

    @Test void statusMarksOldRevisionStaleWithoutAccessingExternalServices() {
        publish(0); clearInvocations(embedding, vectors);
        db.jdbc.update("UPDATE rag_document SET revision=8 WHERE id=501");
        assertThat(read().status()).isEqualTo("STALE"); verifyNoInteractions(embedding, vectors);
        assertThat(withdraw(1, job.id()).status()).isEqualTo("WITHDRAWN");
    }

    @Test void deletionOrMissingTaskDoesNotPreventAuthorizedWithdrawal() {
        publish(0); clearInvocations(embedding, vectors);
        db.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=501");
        db.jdbc.update("DELETE FROM rag_vector_manifest_entry"); db.jdbc.update("DELETE FROM rag_vector_job");
        assertThat(withdraw(1, job.id()).status()).isEqualTo("WITHDRAWN"); verifyNoInteractions(embedding, vectors);
    }

    @Test void invalidInputsAreRejectedBeforeDependencies() {
        code(() -> service.publish(ADMIN, 1, 401, 501, null), 400);
        code(() -> service.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish("../bad", 0L)), 400);
        code(() -> service.publish(ADMIN, 1, 401, 501, new KnowledgeVectorPublicationService.Publish(job.id(), null)), 400);
        code(() -> service.publish(ADMIN, 0, 401, 501, new KnowledgeVectorPublicationService.Publish(job.id(), 0L)), 400);
        code(() -> publish(-1), 400); code(() -> publish(Long.MAX_VALUE), 400);
        code(() -> service.withdraw(ADMIN, 1, 401, 501, null), 400);
        code(() -> withdraw(0, "' OR 1=1 --"), 400); verifyNoInteractions(embedding, vectors);
    }

    @Test void sealedRestoreRetainsCanonicalDigestAndRejectsTampering() {
        var reversed = new ArrayList<>(manifest.expected()); Collections.reverse(reversed);
        var restored = VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 17, reversed, manifest.sha256());
        assertThat(restored.expected()).isEqualTo(manifest.expected()); assertThat(restored.sha256()).isEqualTo(manifest.sha256());
        reversed.clear(); assertThat(restored.expected()).hasSize(17);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 8, SOURCE, 17, manifest.expected(), manifest.sha256()), 409);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 17, manifest.expected(), "d".repeat(64)), 409);
    }

    @Test void restoredManifestRejectsNullOversizedDuplicateOrBadRows() {
        code(() -> VectorIndexManifest.restore(null, manifest.scope(), 7, SOURCE, 17, manifest.expected(), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), null, 7, SOURCE, 17, manifest.expected(), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 0, SOURCE, 17, manifest.expected(), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 1001, manifest.expected(), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 17, null, manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 17,
                Collections.nCopies(17, manifest.expected().get(0)), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 1,
                Arrays.asList((QdrantVectorClient.Stored) null), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 1,
                List.of(new QdrantVectorClient.Stored(0, SOURCE, SOURCE)), manifest.sha256()), 400);
        code(() -> VectorIndexManifest.restore(manifest.index(), manifest.scope(), 7, SOURCE, 1,
                List.of(new QdrantVectorClient.Stored(1, "bad", SOURCE)), manifest.sha256()), 400);
    }

    @Test void finalTransactionRollbackKeepsPointerUnpublished() {
        var failing = spy(publications);
        doAnswer(call -> {
            var result = publications.publish(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("故意阻断最终提交"); }
            });
            return result;
        }).when(failing).publish(any(), anyList(), anyLong(), any());
        service = new KnowledgeVectorPublicationService(jobs, failing, management, members, embedding, vectors, manager, true, clock);
        code(() -> publish(0), 503); notPublished();
    }

    @Test void actualCommitWithLostReceiptIsSafeToReadAndOriginalCasRemainsIdempotent() {
        PlatformTransactionManager lost = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                boolean publishing = db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication", Integer.class) > 0;
                manager.commit(status);
                if (publishing) throw new TransactionSystemException("模拟提交完成后回执丢失，不得外露连接信息");
            }
        };
        service = fresh(lost, true); code(() -> publish(0), 503);
        service = fresh(manager, true);
        assertThat(read().revision()).isEqualTo(1); assertThat(publish(0).revision()).isEqualTo(1);
    }

    @Test void databaseOutageGivesSafeQueryOriginalPointerMessage() {
        var brokenJobs = mock(KnowledgeVectorJobMapper.class);
        when(brokenJobs.find(anyString())).thenThrow(new DataAccessResourceFailureException("内部连接信息不得外露"));
        service = new KnowledgeVectorPublicationService(brokenJobs, publications, management, members, embedding, vectors, manager, true, clock);
        assertThatThrownBy(() -> publish(0)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503); assertThat(ex.getMessage()).contains("查询原指针").doesNotContain("内部连接");
        });
        verifyNoInteractions(embedding, vectors); notPublished();
    }

    @ParameterizedTest @ValueSource(strings = {"publish", "withdraw", "read"})
    void budgetExpiredDuringCommitReportsUncertaintyWithoutClaimingRollback(String operation) {
        if (!"publish".equals(operation)) publish(0);
        PlatformTransactionManager slow = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                boolean finalCommit = !"publish".equals(operation)
                        || db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_publication", Integer.class) > 0;
                manager.commit(status);
                if (finalCommit) now.addAndGet(15_000);
            }
        };
        service = fresh(slow, true);
        assertThatThrownBy(() -> {
            switch (operation) {
                case "publish" -> publish(0);
                case "withdraw" -> withdraw(1, job.id());
                default -> read();
            }
        }).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503);
            assertThat(ex.getMessage()).contains("核对原指针").doesNotContain("回滚");
        });
        service = fresh(manager, true);
        var stored = read();
        assertThat(stored.status()).isEqualTo("withdraw".equals(operation) ? "WITHDRAWN" : "READY");
        assertThat(stored.revision()).isEqualTo("withdraw".equals(operation) ? 2 : 1);
    }

    @ParameterizedTest @ValueSource(strings = {"publish", "withdraw", "read"})
    void identityStorageOutageIsAlsoSanitized(String operation) {
        var brokenMembers = mock(EnterpriseMemberService.class);
        when(brokenMembers.requireMember(any(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("内部身份连接信息不得外露"));
        service = new KnowledgeVectorPublicationService(jobs, publications, management, brokenMembers,
                embedding, vectors, manager, true, clock);
        assertThatThrownBy(() -> {
            switch (operation) {
                case "publish" -> publish(0);
                case "withdraw" -> withdraw(0, null);
                default -> read();
            }
        }).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503);
            assertThat(ex.getMessage()).contains("查询原指针").doesNotContain("内部身份");
        });
        verifyNoInteractions(embedding, vectors); notPublished();
    }

    @Test void withdrawnDuringExternalVerificationCannotBeResurrectedByDelayedPublish() {
        onRead = () -> { if (reads.get() == 1) withdraw(0, null); };
        code(() -> publish(0), 409);
        assertThat(read().status()).isEqualTo("WITHDRAWN"); assertThat(read().revision()).isEqualTo(1);
        notPublished();
    }

    @Test void twoSlotsRejectThirdWithoutQueueAndAreReleasedAfterFailure() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        onSnapshot = () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> publish(0)); var second = pool.submit(() -> publish(0));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            code(() -> publish(0), 503); assertThat(snapshots.get()).isEqualTo(2);
            release.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS).revision()).isEqualTo(1);
            assertThat(second.get(8, TimeUnit.SECONDS).revision()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
        onSnapshot = () -> { throw new BusinessException(ResultCode.SERVICE_BUSY); };
        code(() -> publish(0), 503);
        onSnapshot = () -> { };
        assertThat(publish(0).revision()).isEqualTo(1);
    }
}
