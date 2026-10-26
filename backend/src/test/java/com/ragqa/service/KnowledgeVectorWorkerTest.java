package com.ragqa.service;

import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.common.ResultCode;
import com.ragqa.mapper.*;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.LocalEmbeddingClient.ModelSnapshot;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.QdrantVectorClient.Stored;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** H2真实权限SQL/事务/分页，模型及向量服务为替身；仅合成资料，不作实库或语义效果验收。 */
class KnowledgeVectorWorkerTest {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private EnterpriseDatabase db;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private KnowledgeDocumentMapper documents;
    private KnowledgeManagementMapper management;
    private EnterpriseMemberService members;
    private DataSourceTransactionManager manager;
    private LocalEmbeddingClient embedding;
    private QdrantVectorClient vectors;
    private KnowledgeVectorJobService service;
    private KnowledgeVectorWorker worker;
    private final Map<Long, Stored> written = new HashMap<>();
    private final List<List<String>> inputs = new ArrayList<>();
    private Runnable onEmbed = () -> { };
    private Runnable onWrite = () -> { };
    private Runnable onRead = () -> { };
    private Runnable onSnapshot = () -> { };
    private String id;

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        for (String path : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql")) {
            String migration = Files.readString(Path.of("sql/migrations/" + path), StandardCharsets.UTF_8)
                    .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                    .replaceAll("(?i)CHARACTER SET ascii", "");
            try (var connection = db.dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            }
        }
        db.factory.getConfiguration().addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        for (var type : List.of(KnowledgeDocumentMapper.class, KnowledgeManagementMapper.class,
                KnowledgeVectorJobMapper.class, KnowledgeVectorManifestMapper.class)) db.factory.getConfiguration().addMapper(type);
        var sql = new SqlSessionTemplate(db.factory);
        jobs = spy(sql.getMapper(KnowledgeVectorJobMapper.class));
        manifests = spy(sql.getMapper(KnowledgeVectorManifestMapper.class));
        documents = spy(sql.getMapper(KnowledgeDocumentMapper.class));
        management = sql.getMapper(KnowledgeManagementMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        manager = new DataSourceTransactionManager(db.dataSource);
        embedding = mock(LocalEmbeddingClient.class); vectors = mock(QdrantVectorClient.class);
        when(embedding.snapshot(any())).thenAnswer(call -> {
            outsideTransaction(); onSnapshot.run();
            return new ModelSnapshot("synthetic:1", "a".repeat(64), 2);
        });
        when(embedding.embed(anyList(), any())).thenAnswer(call -> {
            outsideTransaction();
            List<String> texts = call.getArgument(0); inputs.add(List.copyOf(texts)); onEmbed.run();
            assertThat(texts).hasSizeBetween(1, 16);
            return texts.stream().map(text -> new Vector("synthetic:1", List.of(1.0, 0.5))).toList();
        });
        when(vectors.inspect(any(), any())).thenAnswer(call -> { outsideTransaction(); return true; });
        doAnswer(call -> {
            outsideTransaction();
            assertThat(countManifest()).isEqualTo(jobs.find(id).expectedChunks());
            assertThat(jobs.find(id).manifestSha256()).hasSize(64);
            assertThat(jobs.find(id).status()).isEqualTo("RUNNING");
            List<QdrantVectorClient.Point> batch = call.getArgument(2);
            assertThat(batch).hasSizeBetween(1, 16);
            for (var point : batch) written.put(point.chunkId(), new Stored(point.chunkId(), point.contentSha256(),
                    QdrantVectorClient.vectorSha256(point.vector())));
            onWrite.run(); return null;
        }).when(vectors).upsert(any(), any(), anyList(), any());
        when(vectors.read(any(), any(), anyList(), any())).thenAnswer(call -> {
            outsideTransaction(); onRead.run();
            List<Long> ids = call.getArgument(2);
            return ids.stream().map(written::get).filter(Objects::nonNull).sorted(Comparator.comparingLong(Stored::chunkId)).toList();
        });
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN')");
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7 WHERE id=501");
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
                  original_name,storage_key,content_sha256,request_sha256,file_size,parse_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'虚构制度.md',?,?,?,100,'READY',2,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "b".repeat(64), "c".repeat(64));
        chunks(2);
        service = new KnowledgeVectorJobService(jobs, management, documents, members, embedding, manager, true, clock);
        worker = fresh(true);
    }

    @AfterEach void close() throws Exception {
        if (worker != null) worker.close();
        Thread.interrupted();
        if (db != null) db.close();
    }

    private KnowledgeVectorWorker fresh(boolean enabled) {
        return new KnowledgeVectorWorker(jobs, manifests, management, documents, members, embedding, vectors, manager, enabled, clock);
    }
    private void chunks(int count) {
        db.jdbc.update("DELETE FROM rag_chunk WHERE document_id=501");
        for (int index = 0; index < count; index++)
            db.jdbc.update("INSERT INTO rag_chunk(document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line) "
                    + "VALUES(501,701,?,?,0,6,1,1)", index, "虚构片段" + index);
        db.jdbc.update("UPDATE rag_document_version SET chunk_count=? WHERE id=701", count);
    }
    private void submit() {
        id = UUID.randomUUID().toString();
        service.submit(ADMIN, 1, 401, 501, 701, new KnowledgeVectorJobService.Submit(id, 7L));
        clearInvocations(embedding, jobs, documents, manifests);
    }
    private static void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private int countManifest() { return db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_manifest_entry WHERE job_id=?", Integer.class, id); }
    private void failed(String code) {
        var job = jobs.find(id);
        assertThat(job.status()).isEqualTo("FAILED"); assertThat(job.failureCode()).isEqualTo(code);
        assertThat(job.verifiedChunks()).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {1, 17, 501, 1000})
    void fullSnapshotBeforeWritingAndVerificationNeverPublishes(int count) {
        chunks(count); submit();
        assertThat(worker.runOnce()).isTrue();
        var job = jobs.find(id);
        assertThat(job.status()).isEqualTo("VERIFIED"); assertThat(job.verifiedChunks()).isEqualTo(count);
        assertThat(job.failureCode()).isNull(); assertThat(job.deadlineMs()).isEqualTo(job.createdMs() + 120_000);
        assertThat(job.leaseUntilMs()).isEqualTo(job.deadlineMs()); assertThat(countManifest()).isEqualTo(count);
        assertThat(written).hasSize(count);
        assertThat(inputs.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, count).mapToObj(i -> "虚构片段" + i).toList());
        assertThat(db.jdbc.queryForObject("SELECT published_version_id FROM rag_document WHERE id=501", Long.class)).isNull();
        verify(vectors, times((count + 15) / 16)).upsert(any(), any(), anyList(), any());
        verify(vectors, times((count + 15) / 16)).read(any(), any(), anyList(), any());
        clearInvocations(embedding, vectors);
        assertThat(worker.runOnce()).isFalse(); verifyNoInteractions(embedding, vectors);
    }

    @Test void defaultDisabledDoesNotReadMissingTablesOrStartThreads() {
        worker.close(); worker = fresh(false); worker.start();
        clearInvocations(jobs, documents, manifests, embedding, vectors);
        assertThat(worker.runOnce()).isFalse();
        verifyNoInteractions(jobs, documents, manifests, embedding, vectors);
    }

    @Test void closedWorkerDoesNotClaim() {
        submit(); worker.close(); assertThat(worker.runOnce()).isFalse();
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); verifyNoInteractions(embedding, vectors);
    }

    @Test void outerTransactionRejectedBeforeClaim() {
        submit();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> worker.runOnce()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("外层事务");
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); verifyNoInteractions(embedding, vectors);
    }

    @Test void interruptedBeforeDispatchDoesNotClaimAndPreservesFlag() {
        submit(); Thread.currentThread().interrupt(); assertThat(worker.runOnce()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue(); Thread.interrupted();
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED"); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE sys_user SET status=1 WHERE id=3", "UPDATE sys_user SET auth_version=2 WHERE id=3",
            "UPDATE sys_user SET deleted=1 WHERE id=3", "UPDATE sys_enterprise SET status=1 WHERE id=1",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=103",
            "UPDATE sys_enterprise_member SET user_id=999 WHERE id=103",
            "UPDATE rag_knowledge_space SET status=1 WHERE id=401",
            "UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103",
            "UPDATE rag_knowledge_grant SET permission='READ' WHERE member_id=103"})
    void currentAccessRequiredEvenForAdministrators(String sql) {
        submit(); db.jdbc.update(sql); assertThat(worker.runOnce()).isTrue(); failed("ACCESS_REVOKED");
        verifyNoInteractions(embedding, vectors); assertThat(countManifest()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_document SET revision=8 WHERE id=501", "UPDATE rag_document SET deleted=1 WHERE id=501",
            "UPDATE rag_document SET owner_member_id=NULL WHERE id=501",
            "UPDATE rag_document_version SET parse_status='FAILED' WHERE id=701",
            "UPDATE rag_document_version SET publication_status='DELETED' WHERE id=701",
            "UPDATE rag_document_version SET content_sha256=REPEAT('d',64) WHERE id=701",
            "UPDATE rag_document_version SET chunk_count=1 WHERE id=701",
            "DELETE FROM rag_chunk WHERE chunk_index=1 AND document_id=501",
            "UPDATE rag_chunk SET chunk_index=3 WHERE chunk_index=1 AND document_id=501"})
    void changedSourceRejectedBeforeModel(String sql) {
        submit(); db.jdbc.update(sql); assertThat(worker.runOnce()).isTrue(); failed("REVISION_CONFLICT");
        verifyNoInteractions(embedding, vectors); assertThat(countManifest()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\u0000", "\u200b", "\ud800"})
    void invalidTextRejectedBeforeModel(String content) {
        submit(); db.jdbc.update("UPDATE rag_chunk SET content=? WHERE document_id=501 AND chunk_index=0", content);
        worker.runOnce(); failed("REVISION_CONFLICT"); verifyNoInteractions(embedding, vectors);
    }

    @Test void oversizedChunkFailsWithoutSilentTruncation() {
        submit(); db.jdbc.update("UPDATE rag_chunk SET content=? WHERE document_id=501 AND chunk_index=0", "字".repeat(1201));
        worker.runOnce(); failed("REVISION_CONFLICT"); verifyNoInteractions(embedding, vectors);
    }

    @Test void sameCountContentMutationDuringEmbeddingPreventsAllExternalWrites() {
        submit(); onEmbed = () -> db.jdbc.update("UPDATE rag_chunk SET content='已变更' WHERE document_id=501 AND chunk_index=0");
        worker.runOnce(); failed("REVISION_CONFLICT"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void sameCountMutationDuringWriteCannotBecomeVerified() {
        submit(); onWrite = () -> db.jdbc.update("UPDATE rag_chunk SET content='已变更' WHERE document_id=501 AND chunk_index=0");
        worker.runOnce(); failed("REVISION_CONFLICT"); assertThat(countManifest()).isEqualTo(2); assertThat(written).hasSize(2);
    }

    @Test void revokeDuringEmbeddingPreventsAllExternalWrites() {
        submit(); onEmbed = () -> db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103");
        worker.runOnce(); failed("ACCESS_REVOKED"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void cancelDuringEmbeddingRemainsCancelledAndDoesNotWrite() {
        submit(); onEmbed = () -> jobs.cancel(id, now.get()); worker.runOnce();
        assertThat(jobs.find(id).status()).isEqualTo("CANCELLED"); verifyNoInteractions(vectors);
    }

    @Test void cancelDuringFirstWritePreventsSecondBatchAndVerification() {
        chunks(17); submit(); onWrite = () -> jobs.cancel(id, now.get()); worker.runOnce();
        assertThat(jobs.find(id).status()).isEqualTo("CANCELLED"); assertThat(written).hasSize(16);
        verify(vectors, times(1)).upsert(any(), any(), anyList(), any()); verify(vectors, never()).read(any(), any(), anyList(), any());
    }

    @Test void totalDeadlineIncludesQueueAndDoesNotResetPerBatch() {
        chunks(17); submit(); now.addAndGet(110_000); onEmbed = () -> now.addAndGet(10_000); worker.runOnce();
        assertThat(jobs.find(id).status()).isEqualTo("TIMED_OUT"); verifyNoInteractions(vectors);
        verify(embedding, times(1)).embed(anyList(), any());
    }

    @Test void queuedExpiredBeforeDispatchNeverCallsModel() {
        submit(); now.addAndGet(120_000); assertThat(worker.runOnce()).isFalse();
        assertThat(jobs.find(id).status()).isEqualTo("TIMED_OUT"); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @CsvSource({"synthetic:2,a,2", "synthetic:1,d,2", "synthetic:1,a,3"})
    void changedModelSnapshotFailsBeforeEmbedding(String model, String revision, int dimensions) {
        submit(); when(embedding.snapshot(any())).thenReturn(new ModelSnapshot(model, revision.repeat(64), dimensions));
        worker.runOnce(); failed("MODEL_CHANGED"); verify(embedding, never()).embed(anyList(), any()); verifyNoInteractions(vectors);
    }

    @Test void modelChangedAfterEmbeddingPreventsManifestAndWrite() {
        submit(); when(embedding.snapshot(any())).thenReturn(new ModelSnapshot("synthetic:1", "a".repeat(64), 2),
                new ModelSnapshot("synthetic:1", "d".repeat(64), 2));
        worker.runOnce(); failed("MODEL_CHANGED"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void modelChangedAfterReadNeverMarksVerified() {
        submit(); onRead = () -> when(embedding.snapshot(any())).thenReturn(new ModelSnapshot("synthetic:1", "d".repeat(64), 2));
        worker.runOnce(); failed("MODEL_CHANGED"); assertThat(written).hasSize(2);
    }

    @Test void missingEmbeddingRowNeverSavesPartialManifest() {
        submit(); doReturn(List.of(new Vector("synthetic:1", List.of(1.0, 0.5)))).when(embedding).embed(anyList(), any());
        worker.runOnce(); failed("MANIFEST_MISMATCH"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void embeddingFailureNotRetried() {
        submit(); doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "SECRET")).when(embedding).embed(anyList(), any());
        worker.runOnce(); failed("VECTOR_UNAVAILABLE"); verify(embedding, times(1)).embed(anyList(), any()); verifyNoInteractions(vectors);
    }

    @Test void manifestInsertFailureRollsBackAllRowsBeforeExternalWrite() {
        submit(); doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("SECRET_SQL"); })
                .when(manifests).insertRows(anyString(), anyList());
        worker.runOnce(); failed("INTERNAL_ERROR"); assertThat(countManifest()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull(); verifyNoInteractions(vectors);
    }

    @Test void collectionCreatedOnlyAfterManifestStored() {
        submit(); when(vectors.inspect(any(), any())).thenReturn(false);
        doAnswer(call -> { outsideTransaction(); assertThat(countManifest()).isEqualTo(2); return null; }).when(vectors).create(any(), any());
        worker.runOnce(); assertThat(jobs.find(id).status()).isEqualTo("VERIFIED"); verify(vectors, times(1)).create(any(), any());
    }

    @Test void unknownCollectionCreationNotRetried() {
        submit(); when(vectors.inspect(any(), any())).thenReturn(false);
        doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "SECRET")).when(vectors).create(any(), any());
        worker.runOnce(); failed("WRITE_UNCONFIRMED"); assertThat(countManifest()).isEqualTo(2);
        verify(vectors, times(1)).create(any(), any()); verify(vectors, never()).upsert(any(), any(), anyList(), any());
    }

    @Test void unknownWriteRemainsFailedEvenWhenPointsWereActuallyStored() {
        chunks(17); submit(); onWrite = () -> { throw new BusinessException(ResultCode.SERVICE_BUSY, "SECRET"); };
        worker.runOnce(); failed("WRITE_UNCONFIRMED"); assertThat(written).hasSize(16); assertThat(countManifest()).isEqualTo(17);
        verify(vectors, times(1)).upsert(any(), any(), anyList(), any());
        assertThat(worker.runOnce()).isFalse(); verify(vectors, never()).read(any(), any(), anyList(), any());
    }

    @Test void missingPointDuringVerificationCannotBecomePartialSuccess() {
        submit(); onRead = written::clear; worker.runOnce(); failed("MANIFEST_MISMATCH");
        assertThat(countManifest()).isEqualTo(2);
    }

    @Test void incorrectPayloadDigestCannotBecomeVerified() {
        submit(); onRead = () -> written.replaceAll((key, row) -> new Stored(key, "d".repeat(64), row.vectorSha256()));
        worker.runOnce(); failed("MANIFEST_MISMATCH");
    }

    @Test void currentLeaseLostDuringWriteCannotMarkVerified() {
        submit(); onWrite = () -> db.jdbc.update("UPDATE rag_vector_job SET lease_until_ms=? WHERE id=?", now.get() + 1, id);
        onRead = () -> now.incrementAndGet(); worker.runOnce(); failed("LEASE_LOST");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test void verifiedUpdateFailureRollsBackFinalTransaction() {
        submit(); doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("SECRET"); })
                .when(jobs).verified(anyString(), anyString(), anyString(), anyInt(), anyLong());
        worker.runOnce(); failed("INTERNAL_ERROR");
        // 事务内异常必须回滚VERIFIED，但先前独立提交的完整预期清单仍保留。
        assertThat(countManifest()).isEqualTo(2);
    }

    @Test void realFinalCommitThenLostReceiptDoesNotOverwriteVerifiedOrReplay() {
        submit(); AtomicBoolean loseOnce = new AtomicBoolean(true);
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if ("VERIFIED".equals(jobs.find(id).status()) && loseOnce.compareAndSet(true, false))
                    throw new TransactionSystemException("SECRET_COMMIT_ACK");
            }
        };
        worker.close();
        worker = new KnowledgeVectorWorker(jobs, manifests, management, documents, members, embedding, vectors, uncertain, true, clock);
        assertThat(worker.runOnce()).isTrue();
        assertThat(jobs.find(id).status()).isEqualTo("VERIFIED"); assertThat(jobs.find(id).failureCode()).isNull();
        assertThat(worker.runOnce()).isFalse(); verify(embedding, times(1)).embed(anyList(), any());
    }

    @Test void closeDuringEmbeddingStopsBeforeWriteAndKeepsManifestEmpty() {
        submit(); onEmbed = worker::close; worker.runOnce(); failed("INTERNAL_ERROR");
        assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void interruptionDuringEmbeddingPreservesFlagAndStopsBeforeWrite() {
        submit(); onEmbed = () -> Thread.currentThread().interrupt(); worker.runOnce();
        assertThat(Thread.currentThread().isInterrupted()).isTrue(); Thread.interrupted();
        failed("INTERNAL_ERROR"); verifyNoInteractions(vectors);
    }

    @Test void clockRollbackDuringMetadataIsDetectedEvenBeforeOriginalDeadline() {
        submit(); onEmbed = () -> {
            now.addAndGet(20); onSnapshot = () -> now.addAndGet(-10);
        };
        worker.runOnce(); failed("INTERNAL_ERROR"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void readFailureIsUnavailableRatherThanMissingKnowledge() {
        submit(); doThrow(new BusinessException(ResultCode.SERVICE_BUSY, "SECRET")).when(vectors).read(any(), any(), anyList(), any());
        worker.runOnce(); failed("VECTOR_UNAVAILABLE"); verify(vectors, times(1)).read(any(), any(), anyList(), any());
    }

    @ParameterizedTest @EnumSource(value = ResultCode.class, names = {"UNAUTHORIZED", "SERVICE_BUSY", "SYSTEM_ERROR"})
    void collectionFailureStopsWithoutRetryOrUpsert(ResultCode code) {
        submit(); doThrow(new BusinessException(code, "SECRET")).when(vectors).inspect(any(), any());
        worker.runOnce(); failed("VECTOR_UNAVAILABLE"); verify(vectors, times(1)).inspect(any(), any());
        verify(vectors, never()).upsert(any(), any(), anyList(), any());
    }

    @Test void nonFiniteVectorNeverPersistsManifest() {
        submit(); doReturn(List.of(new Vector("synthetic:1", List.of(Double.NaN, 0.5)), new Vector("synthetic:1", List.of(1.0, 0.5))))
                .when(embedding).embed(anyList(), any());
        worker.runOnce(); failed("MANIFEST_MISMATCH"); assertThat(countManifest()).isZero(); verifyNoInteractions(vectors);
    }

    @Test void claimLostToAnotherWorkerDoesNotEmbed() {
        submit(); doReturn(0).when(jobs).claim(anyString(), anyString(), anyLong(), anyLong());
        assertThat(worker.runOnce()).isFalse(); verifyNoInteractions(embedding, vectors);
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED");
    }

    @Test void storageExceptionReleasesLocalSlotForLaterSafePoll() {
        submit(); doThrow(new DataAccessResourceFailureException("SECRET_SQL")).doCallRealMethod().when(jobs).pending(anyLong());
        assertThatThrownBy(worker::runOnce).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(worker.runOnce()).isTrue(); assertThat(jobs.find(id).status()).isEqualTo("VERIFIED");
    }

    @Test void twoWorkersRaceSameJobOnlyOneEmbeds() throws Exception {
        submit(); var other = fresh(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        onEmbed = () -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(worker::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(other.runOnce()).isFalse(); assertThat(worker.runOnce()).isFalse();
            release.countDown(); assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.find(id).status()).isEqualTo("VERIFIED"); verify(embedding, times(1)).embed(anyList(), any());
        } finally { release.countDown(); pool.shutdownNow(); other.close(); }
    }

    @Test void abandonedRunningNeverReplayed() {
        submit(); String token = UUID.randomUUID().toString(); jobs.claim(id, token, now.get(), now.get() + 1);
        now.incrementAndGet(); assertThat(worker.runOnce()).isFalse(); failed("LEASE_LOST"); verifyNoInteractions(embedding, vectors);
    }

    @Test void schedulerIsIdempotentAndExecutesQueuedWork() throws Exception {
        submit(); var completed = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { completed.countDown(); }
            });
            return result;
        })
                .when(jobs).verified(anyString(), anyString(), anyString(), anyInt(), anyLong());
        worker.start(); worker.start();
        assertThat(completed.await(8, TimeUnit.SECONDS)).isTrue(); worker.close();
        assertThat(jobs.find(id).status()).isEqualTo("VERIFIED"); verify(embedding, times(1)).embed(anyList(), any());
    }
}
