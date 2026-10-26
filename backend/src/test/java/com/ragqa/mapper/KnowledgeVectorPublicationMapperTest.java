package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 真实MyBatis及隔离H2事务；不操作开发MySQL，不访问Qdrant、模型或前端。 */
class KnowledgeVectorPublicationMapperTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String SOURCE = "b".repeat(64);
    private static final List<String> TEXT = List.of("虚构制度：餐补三十元。", "虚构制度：需主管审批。");
    private final EnterpriseMemberContext reader = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final Clock clock = fixed(NOW);
    private EnterpriseDatabase db;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private KnowledgeVectorPublicationMapper publications;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private Stored job;
    private List<com.ragqa.rag.retrieval.QdrantVectorClient.Stored> checked;

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        try (var connection = db.dataSource.getConnection()) {
            for (String name : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql",
                    "20261020_02_knowledge_vector_publication.sql")) {
                String script = Files.readString(Path.of("sql/migrations/" + name), StandardCharsets.UTF_8)
                        .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                        .replaceAll("(?i)CHARACTER SET ascii", "");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            }
        }
        var configuration = db.factory.getConfiguration();
        configuration.addMapper(KnowledgeVectorJobMapper.class);
        configuration.addMapper(KnowledgeVectorManifestMapper.class);
        configuration.addMapper(KnowledgeVectorPublicationMapper.class);
        var session = new SqlSessionTemplate(db.factory);
        jobs = session.getMapper(KnowledgeVectorJobMapper.class);
        manifests = session.getMapper(KnowledgeVectorManifestMapper.class);
        publications = session.getMapper(KnowledgeVectorPublicationMapper.class);
        manager = new DataSourceTransactionManager(db.dataSource);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=701 WHERE id=501");
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                  parse_status,publication_status,chunk_count,created_by_member)
                VALUES(701,1,401,501,1,?,'synthetic.md',?,?,?,100,'READY','PUBLISHED',2,103)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SOURCE, "c".repeat(64));
        for (int n = 0; n < TEXT.size(); n++) db.jdbc.update("""
                INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                VALUES(?,501,701,?,?,0,10,1,1)
                """, 1001 + n, n, TEXT.get(n));
        job = seedJob();
    }

    @AfterEach void close() throws Exception { if (db != null) db.close(); }

    private Stored seedJob() throws Exception {
        return seedJob(501, 701, 1001);
    }

    private Stored seedJob(long document, long version, long firstChunk) throws Exception {
        String id = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jobs.insert(new Stored(id, generation, 1, 401, document, version, 103, 3, 1, 7, "c".repeat(64), SOURCE,
                "ko_publication_test", "synthetic:1", "a".repeat(64), 2, 2, "QUEUED", null, null, null, 0,
                NOW - 50_000, NOW + 70_000, null, null));
        assertThat(jobs.claim(id, token, NOW - 40_000, NOW + 60_000)).isEqualTo(1);
        var points = new ArrayList<Point>();
        for (int n = 0; n < TEXT.size(); n++) points.add(new Point(firstChunk + n, hash(TEXT.get(n)),
                new Vector("synthetic:1", List.of(3.0, 4.0))));
        var manifest = VectorIndexManifest.capture(new Index("ko_publication_test", "synthetic:1", "a".repeat(64), 2),
                new Scope(1, 401, document, version, generation), 7, SOURCE, 2, points);
        tx.execute(status -> manifests.persist(id, token, manifest, fixed(NOW - 20_000)));
        assertThat(jobs.verified(id, token, manifest.sha256(), 2, NOW - 1000)).isEqualTo(1);
        checked = manifest.expected();
        return jobs.find(id);
    }

    /** 每份文档都走真实封存、核验和发布，不复制不匹配的旧清单摘要。 */
    private Stored seedPublishedDocument(int number) throws Exception {
        long document = 10_000L + number, version = 20_000L + number, firstChunk = 30_000L + number * 2L;
        db.jdbc.update("""
                INSERT INTO rag_document(id,title,source_type,enterprise_id,knowledge_space_id,
                  owner_member_id,published_version_id,revision)
                VALUES(?,'虚构容量测试制度','UPLOAD',1,401,103,?,7)
                """, document, version);
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                  parse_status,publication_status,chunk_count,created_by_member)
                VALUES(?,1,401,?,1,?,'synthetic.md',?,?,?,100,'READY','PUBLISHED',2,103)
                """, version, document, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SOURCE, "c".repeat(64));
        for (int n = 0; n < TEXT.size(); n++) db.jdbc.update("""
                INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                VALUES(?,?,?,?,?,0,?,1,1)
                """, firstChunk + n, document, version, n, TEXT.get(n), TEXT.get(n).length());
        Stored result = seedJob(document, version, firstChunk);
        tx.execute(status -> publications.publish(result, checked, 0, clock));
        return result;
    }

    private KnowledgeVectorPublicationMapper.Pointer publish(long revision) {
        return tx.execute(status -> publications.publish(job, checked, revision, clock));
    }
    private KnowledgeVectorPublicationMapper.Pointer withdraw(long revision, String id) {
        return tx.execute(status -> publications.withdraw(1, 401, 501, revision, id, clock));
    }
    private KnowledgeVectorPublicationMapper.Pointer pointer() {
        return tx.execute(status -> publications.lockPointer(1, 401, 501));
    }
    private Stored candidate() { return publications.candidate(reader, 401, 501, NOW); }
    private List<Stored> candidates() { return publications.candidates(reader, 401, NOW); }
    private void conflict(Runnable action) { code(action, 409); }
    private void code(Runnable action, int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(expected));
    }
    private static Clock fixed(long time) { return Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC); }
    private static Clock changing(long before, long after) {
        var calls = new AtomicInteger();
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(calls.getAndIncrement() == 0 ? before : after); }
        };
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void schemaUsesOneLogicalPointerWithoutPhysicalForeignKeys() throws Exception {
        try (var connection = db.dataSource.getConnection();
             var keys = connection.getMetaData().getImportedKeys(null, null, "rag_vector_publication")) {
            assertThat(keys.next()).isFalse();
        }
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
        assertThat(jobs.find(job.id()).status()).isEqualTo("VERIFIED");
    }

    @Test void candidateListNeedsExplicitPublicationAndReturnsImmutableInternalSnapshots() {
        assertThat(candidates()).isEmpty();
        publish(0);
        List<Stored> result = candidates();
        assertThat(result).containsExactly(job);
        assertThatThrownBy(() -> result.add(job)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(pointer().revision()).isEqualTo(1);
        assertThat(jobs.find(job.id())).isEqualTo(job);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job", Integer.class)).isEqualTo(1);
    }

    @Test void candidateListFollowsOnlyCurrentGenerationAndNeverResurrectsWithdrawnJob() throws Exception {
        publish(0);
        Stored second = seedJob();
        assertThat(candidates()).containsExactly(job);
        tx.execute(status -> publications.publish(second, checked, 1, clock));
        assertThat(candidates()).containsExactly(second);
        withdraw(2, second.id());
        assertThat(candidates()).isEmpty();
        assertThat(jobs.find(job.id()).status()).isEqualTo("VERIFIED");
        assertThat(jobs.find(second.id()).status()).isEqualTo("VERIFIED");
    }

    @ParameterizedTest @ValueSource(ints = {1, 24, 25})
    void candidateListHasStableDocumentOrderAndRejectsCapacityInsteadOfTruncating(int count) throws Exception {
        var expected = new ArrayList<Stored>();
        for (int n = count - 1; n >= 0; n--) expected.add(0, seedPublishedDocument(n));
        if (count <= 24) {
            assertThat(candidates()).containsExactlyElementsOf(expected);
        } else {
            assertThatThrownBy(this::candidates).isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(503);
                assertThat(error.getMessage()).contains("超过24份", "未截断");
            });
            // 不可用文档不占授权候选容量；只改本测试内存库的最后一份文档。
            db.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=?", expected.get(24).documentId());
            assertThat(candidates()).containsExactlyElementsOf(expected.subList(0, 24));
        }
    }

    @Test void candidateListBindsLargeSpaceIdAndDoesNotDuplicateOverlappingGrants() {
        publish(0);
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,department_id,permission) VALUES(1,401,11,'READ')");
        assertThat(candidates()).containsExactly(job);
        assertThat(publications.candidates(reader, Long.MAX_VALUE, NOW)).isEmpty();
        assertThat(publications.candidates(reader, 402, NOW)).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {-1, 1, 4, 8})
    void candidateListRejectsNonReadCommittedTransactionsBeforeQuery(int isolation) {
        var previousSnapshot = new TransactionTemplate(manager);
        previousSnapshot.setIsolationLevel(isolation);
        code(() -> previousSnapshot.execute(status -> candidates()), 503);
    }

    @Test void candidateListAllowsReadOnlyReadCommittedAndRejectsInvalidArguments() {
        publish(0);
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        List<Stored> result = readOnly.execute(status -> candidates());
        assertThat(result).containsExactly(job);
        code(() -> publications.candidates(null, 401, NOW), 400);
        code(() -> publications.candidates(reader, 0, NOW), 400);
        code(() -> publications.candidates(reader, -1, NOW), 400);
        code(() -> publications.candidates(reader, 401, -1), 400);
    }

    @Test void candidateListSeesSeparatelyCommittedRevocationInsideReadCommitted() throws Exception {
        publish(0);
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                assertThat(candidates()).containsExactly(job);
                try {
                    executor.submit(() -> db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401"))
                            .get(3, TimeUnit.SECONDS);
                } catch (Exception error) { throw new IllegalStateException("测试撤权未能有限完成", error); }
                assertThat(candidates()).isEmpty();
                return null;
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void publishIsSeparateFromVerifiedAndUsesCurrentBusinessVersion() {
        assertThat(candidate()).isNull();
        assertThat(publish(0).revision()).isEqualTo(1);
        assertThat(candidate()).isEqualTo(job);
        assertThat(jobs.find(job.id())).isEqualTo(job);
        assertThat(db.jdbc.queryForObject("SELECT revision FROM rag_document WHERE id=501", Long.class)).isEqualTo(7);
        assertThat(db.jdbc.queryForObject("SELECT published_version_id FROM rag_document WHERE id=501", Long.class)).isEqualTo(701);
    }

    @Test void identicalOriginalCasAndCurrentStateAreIdempotent() {
        var first = publish(0);
        assertThat(publish(0)).isEqualTo(first);
        assertThat(publish(1)).isEqualTo(first);
        conflict(() -> publish(2));
        assertThat(pointer()).isEqualTo(first);
    }

    @Test void switchesGenerationAndRejectsDelayedSwitchOrWithdrawal() throws Exception {
        Stored second = seedJob();
        publish(0);
        var next = tx.execute(status -> publications.publish(second, checked, 1, clock));
        assertThat(next.revision()).isEqualTo(2);
        assertThat(candidate()).isEqualTo(second);
        conflict(() -> publish(0));
        conflict(() -> withdraw(1, job.id()));
        conflict(() -> withdraw(2, job.id()));
        assertThat(pointer()).isEqualTo(next);
    }

    @Test void sameJobAfterRoundTripDoesNotLetOldCasSucceed() throws Exception {
        Stored second = seedJob();
        publish(0);
        tx.execute(status -> publications.publish(second, checked, 1, clock));
        publish(2);
        conflict(() -> publish(0));
        assertThat(pointer().revision()).isEqualTo(3);
    }

    @Test void withdrawalKeepsTombstoneAndOldPublishCannotResurrectIt() {
        publish(0);
        var removed = withdraw(1, job.id());
        assertThat(removed.jobId()).isNull();
        assertThat(removed.revision()).isEqualTo(2);
        assertThat(withdraw(1, job.id())).isEqualTo(removed);
        assertThat(candidate()).isNull();
        conflict(() -> publish(0));
        conflict(() -> publish(1));
        assertThat(publish(2).revision()).isEqualTo(3);
    }

    @Test void withdrawingAbsentAndAlreadyWithdrawnPointersFencesInflightFirstPublish() {
        var first = withdraw(0, null);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(withdraw(0, null)).isEqualTo(first);
        conflict(() -> publish(0));
        assertThat(withdraw(1, null).revision()).isEqualTo(2);
        conflict(() -> publish(1));
        assertThat(publish(2).revision()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_document SET deleted=1 WHERE id=501",
            "UPDATE rag_document SET owner_member_id=NULL WHERE id=501",
            "UPDATE rag_document SET revision=8 WHERE id=501",
            "UPDATE rag_document SET published_version_id=NULL WHERE id=501",
            "UPDATE rag_document SET knowledge_space_id=402 WHERE id=501",
            "UPDATE rag_document_version SET publication_status='DRAFT' WHERE id=701",
            "UPDATE rag_document_version SET publication_status='WITHDRAWN' WHERE id=701",
            "UPDATE rag_document_version SET publication_status='DELETED' WHERE id=701",
            "UPDATE rag_document_version SET parse_status='FAILED' WHERE id=701",
            "UPDATE rag_document_version SET chunk_count=1 WHERE id=701",
            "UPDATE rag_document_version SET content_sha256=REPEAT('d',64) WHERE id=701",
            "UPDATE rag_document_version SET enterprise_id=2 WHERE id=701",
            "UPDATE rag_document_version SET valid_from_ms=1800000000001 WHERE id=701",
            "UPDATE rag_document_version SET valid_until_ms=1800000000000 WHERE id=701"})
    void refusesStaleOrUnpublishedSourceAndDoesNotFallBack(String mutation) {
        publish(0);
        db.jdbc.update(mutation);
        conflict(() -> publish(1));
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
        assertThat(pointer().revision()).isEqualTo(1);
    }

    @Test void inclusiveStartExclusiveEndAndFinishedBuildDeadlineAreDistinct() {
        db.jdbc.update("UPDATE rag_document_version SET valid_from_ms=?,valid_until_ms=? WHERE id=701", NOW, NOW + 1);
        assertThat(publish(0).revision()).isEqualTo(1);
        assertThat(candidate()).isEqualTo(job);
        assertThat(candidates()).containsExactly(job);
        assertThat(publications.candidate(reader, 401, 501, NOW + 1)).isNull();
        assertThat(publications.candidates(reader, 401, NOW + 1)).isEmpty();
        db.jdbc.update("UPDATE rag_document_version SET valid_until_ms=NULL WHERE id=701");
        assertThat(tx.execute(status -> publications.publish(job, checked, 1, fixed(NOW + 120_000))).revision()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE rag_vector_job SET collection_name='ko_changed'",
            "UPDATE rag_vector_job SET generation='00000000-0000-0000-0000-000000000001'",
            "UPDATE rag_vector_job SET manifest_sha256=REPEAT('d',64)",
            "UPDATE rag_vector_job SET model_revision=REPEAT('d',64)",
            "UPDATE rag_vector_job SET expected_revision=8",
            "UPDATE rag_vector_job SET member_id=102"})
    void trustedTaskSnapshotCannotSilentlyChange(String mutation) {
        db.jdbc.update(mutation);
        conflict(() -> publish(0));
        assertThat(pointer()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {
            "DELETE FROM rag_vector_manifest_entry WHERE chunk_id=1002",
            "UPDATE rag_vector_manifest_entry SET vector_sha256=REPEAT('d',64) WHERE chunk_id=1002",
            "UPDATE rag_vector_manifest_entry SET content_sha256=REPEAT('d',64) WHERE chunk_id=1002",
            "UPDATE rag_vector_manifest_entry SET chunk_id=9999 WHERE chunk_id=1002",
            "DELETE FROM rag_chunk WHERE id=1002",
            "UPDATE rag_chunk SET content='修改过的正文' WHERE id=1002",
            "UPDATE rag_chunk SET content='' WHERE id=1002",
            "UPDATE rag_chunk SET chunk_index=3 WHERE id=1002",
            "UPDATE rag_chunk SET version_id=0 WHERE id=1002"})
    void requiresEveryManifestRowAndCurrentSourceFragment(String mutation) {
        db.jdbc.update(mutation);
        conflict(() -> publish(0));
        assertThat(pointer()).isNull();
    }

    @Test void countEqualityDoesNotAcceptWrongChunkMappingForCandidate() {
        publish(0);
        db.jdbc.update("UPDATE rag_vector_manifest_entry SET chunk_id=9999 WHERE chunk_id=1002");
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "CANCELLED", "TIMED_OUT"})
    void nonVerifiedTaskCannotBecomeReadyCandidate(String terminal) {
        publish(0);
        db.jdbc.update("UPDATE rag_vector_job SET status=?,verified_chunks=0 WHERE id=?", terminal, job.id());
        conflict(() -> publish(1));
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
    }

    @Test void firstPublicationCanHappenAfterCompletedBuildDeadlineWithoutExtendingIt() {
        var result = tx.execute(status -> publications.publish(job, checked, 0, fixed(NOW + 120_000)));
        assertThat(result.revision()).isEqualTo(1);
        assertThat(jobs.find(job.id()).deadlineMs()).isEqualTo(job.deadlineMs());
    }

    @Test void candidateRejectsOldOrUnspecifiedTransactionSnapshotsButAcceptsReadCommitted() {
        publish(0);
        Stored current = tx.execute(status -> candidate());
        assertThat(current).isEqualTo(job);
        for (int isolation : List.of(TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_REPEATABLE_READ)) {
            var oldSnapshot = new TransactionTemplate(manager);
            oldSnapshot.setIsolationLevel(isolation);
            code(() -> oldSnapshot.execute(status -> candidate()), 503);
        }
        code(() -> publications.candidate(null, 401, 501, NOW), 400);
        code(() -> publications.candidate(reader, 0, 501, NOW), 400);
        code(() -> publications.candidate(reader, 401, 0, NOW), 400);
        code(() -> publications.candidate(reader, 401, 501, -1), 400);
    }

    @Test void cannotPublishWithoutNonReadOnlyCallerTransaction() {
        code(() -> publications.publish(job, checked, 0, clock), 503);
        code(() -> publications.withdraw(1, 401, 501, 0, null, clock), 503);
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        code(() -> readOnly.execute(status -> publications.publish(job, checked, 0, clock)), 503);
        code(() -> readOnly.execute(status -> publications.withdraw(1, 401, 501, 0, null, clock)), 503);
        assertThat(pointer()).isNull();
    }

    @ParameterizedTest @ValueSource(longs = {-1, Long.MAX_VALUE})
    void invalidPointerRevisionsAreRejected(long revision) {
        code(() -> publish(revision), 400);
        code(() -> withdraw(revision, null), 400);
        assertThat(pointer()).isNull();
    }

    @Test void rejectsNullPartialDuplicateReorderedAndInvalidManifestInput() {
        code(() -> publications.publish(null, checked, 0, clock), 400);
        code(() -> publications.publish(job, null, 0, clock), 400);
        code(() -> publications.publish(job, checked.subList(0, 1), 0, clock), 400);
        code(() -> publications.publish(job, List.of(checked.get(0), checked.get(0)), 0, clock), 400);
        code(() -> publications.publish(job, List.of(checked.get(1), checked.get(0)), 0, clock), 400);
        code(() -> publications.publish(job, java.util.Arrays.asList(checked.get(0), null), 0, clock), 400);
        code(() -> publications.publish(job, checked, 0, null), 400);
        code(() -> publications.withdraw(1, 401, 501, 0, "' OR 1=1 --", clock), 400);
        assertThat(pointer()).isNull();
    }

    @Test void actualCallerRollbackRestoresInsertionSwitchAndTombstone() throws Exception {
        assertThatThrownBy(() -> tx.execute(status -> {
            publications.publish(job, checked, 0, clock);
            throw new IllegalStateException("故意中断调用方事务");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(pointer()).isNull();
        publish(0);
        Stored second = seedJob();
        assertThatThrownBy(() -> tx.execute(status -> {
            publications.publish(second, checked, 1, clock);
            throw new IllegalStateException("故意中断切换");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(candidate()).isEqualTo(job);
        assertThatThrownBy(() -> tx.execute(status -> {
            publications.withdraw(1, 401, 501, 1, job.id(), clock);
            throw new IllegalStateException("故意中断撤回");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(candidate()).isEqualTo(job);
        assertThat(pointer().revision()).isEqualTo(1);
    }

    @Test void actualCommitWithLostReceiptIsReconciledByOriginalCas() {
        PlatformTransactionManager lost = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                throw new TransactionSystemException("模拟真实提交后回执丢失");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var lostTx = new TransactionTemplate(lost);
        assertThatThrownBy(() -> lostTx.execute(status -> publications.publish(job, checked, 0, clock)))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(pointer().revision()).isEqualTo(1);
        assertThat(publish(0)).isEqualTo(pointer());
    }

    @Test void cancelledOrDeletedSourceCanStillHaveResidualPointerWithdrawn() {
        publish(0);
        db.jdbc.update("UPDATE rag_document SET deleted=1 WHERE id=501");
        db.jdbc.update("DELETE FROM rag_vector_manifest_entry");
        db.jdbc.update("DELETE FROM rag_vector_job");
        assertThat(withdraw(1, job.id()).jobId()).isNull();
        assertThat(candidate()).isNull();
    }

    @Test void expiryDuringLockWaitRollsBackNewPointer() {
        db.jdbc.update("UPDATE rag_document_version SET valid_until_ms=? WHERE id=701", NOW + 10);
        conflict(() -> tx.execute(status -> publications.publish(job, checked, 0, changing(NOW, NOW + 20))));
        assertThat(pointer()).isNull();
    }

    @ParameterizedTest @ValueSource(longs = {-1, 5000})
    void clockRollbackOrTransactionBudgetExhaustionAborts(long change) {
        code(() -> tx.execute(status -> publications.publish(job, checked, 0, changing(NOW, NOW + change))), 503);
        assertThat(pointer()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {
            "UPDATE sys_user SET status=1 WHERE id=1",
            "UPDATE sys_user SET deleted=1 WHERE id=1",
            "UPDATE sys_user SET auth_version=2 WHERE id=1",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=101",
            "UPDATE sys_enterprise_member SET permission_version=2 WHERE id=101",
            "UPDATE sys_enterprise_member SET role='ADMIN' WHERE id=101",
            "UPDATE sys_enterprise SET status=1 WHERE id=1",
            "UPDATE sys_enterprise SET permission_version=2 WHERE id=1",
            "UPDATE rag_knowledge_space SET status=1 WHERE id=401",
            "UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401"})
    void candidateAlwaysRechecksCurrentIdentityAndReadGrant(String mutation) {
        publish(0);
        assertThat(candidate()).isEqualTo(job);
        db.jdbc.update(mutation);
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
    }

    @Test void administratorHasNoImplicitReadAndOtherEnterpriseCannotRead() {
        publish(0);
        var admin = new EnterpriseMemberContext(1, 103, 3, 11L, "ADMIN", 1, 1, 1);
        var other = new EnterpriseMemberContext(2, 201, 1, 22L, "EMPLOYEE", 1, 1, 1);
        assertThat(publications.candidate(admin, 401, 501, NOW)).isNull();
        assertThat(publications.candidates(admin, 401, NOW)).isEmpty();
        assertThat(publications.candidate(other, 401, 501, NOW)).isNull();
        assertThat(publications.candidates(other, 401, NOW)).isEmpty();
        assertThat(publications.candidate(reader, 402, 501, NOW)).isNull();
        assertThat(publications.candidate(reader, 401, 503, NOW)).isNull();
        conflict(() -> tx.execute(status -> publications.withdraw(2, 401, 501, 1, job.id(), clock)));
    }

    @Test void departmentGrantNeedsCurrentActiveSameEnterpriseDepartment() {
        publish(0);
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,department_id,permission) VALUES(1,401,11,'READ')");
        assertThat(candidate()).isEqualTo(job);
        assertThat(candidates()).containsExactly(job);
        db.jdbc.update("UPDATE sys_department SET status=1 WHERE id=11");
        assertThat(candidate()).isNull();
        assertThat(candidates()).isEmpty();
    }

    @Test void concurrentFirstPublishAndWithdrawalCannotReviveStaleGeneration() throws Exception {
        var release = new CountDownLatch(1);
        var locked = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(status -> {
                var result = publications.withdraw(1, 401, 501, 0, null, clock);
                locked.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("等待超时"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return result;
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> { conflict(() -> publish(0)); return true; });
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).jobId()).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(pointer().revision()).isEqualTo(1);
        assertThat(candidate()).isNull();
    }

    @Test void concurrentDifferentGenerationsHaveExactlyOneWinner() throws Exception {
        Stored second = seedJob();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (Stored input : List.of(job, second)) tasks.add(pool.submit(() -> {
                if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("等待超时");
                try { tx.execute(status -> publications.publish(input, checked, 0, clock)); return 1; }
                catch (BusinessException ex) { assertThat(ex.getCode()).isEqualTo(409); return 0; }
            }));
            start.countDown();
            assertThat(tasks.get(0).get(5, TimeUnit.SECONDS) + tasks.get(1).get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(pointer().revision()).isEqualTo(1);
        assertThat(candidate()).isIn(job, second);
    }
}
