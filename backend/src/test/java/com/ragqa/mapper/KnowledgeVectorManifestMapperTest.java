package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import org.apache.ibatis.session.LocalCacheScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** 隔离H2实际执行迁移、Mapper和提交/回滚；没有开发库、模型或Qdrant调用。 */
class KnowledgeVectorManifestMapperTest {
    private static final long CREATED = 1_800_000_000_000L;
    private static final String SOURCE = "b".repeat(64);
    private static final String MODEL_REVISION = "a".repeat(64);
    private final String id = UUID.randomUUID().toString();
    private final String token = UUID.randomUUID().toString();
    private final String generation = UUID.randomUUID().toString();
    private final Clock clock = fixed(CREATED + 10);
    private JdbcDataSource dataSource;
    private Connection anchor;
    private JdbcTemplate jdbc;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private VectorIndexManifest manifest;
    private String migration;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:vector_manifest_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=3000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        anchor = dataSource.getConnection();
        for (String name : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql")) {
            String script = Files.readString(Path.of("sql/migrations/" + name), StandardCharsets.UTF_8);
            if (name.startsWith("20261020")) migration = script;
            script = script.replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                    .replaceAll("(?i)CHARACTER SET ascii", "");
            ScriptUtils.executeSqlScript(anchor, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        }
        jdbc = new JdbcTemplate(dataSource);
        var sql = session();
        jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
        manifests = sql.getMapper(KnowledgeVectorManifestMapper.class);
        manager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        manifest = capture(17, false);
        seed(17);
    }

    @AfterEach void close() throws Exception { if (anchor != null) anchor.close(); }

    private SqlSessionTemplate session() throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var result = new SqlSessionTemplate(factory.getObject());
        configuration.addMapper(KnowledgeVectorJobMapper.class);
        configuration.addMapper(KnowledgeVectorManifestMapper.class);
        return result;
    }

    private void seed(int count) {
        jobs.insert(new Stored(id, generation, 1, 401, 501, 701, 103, 3, 1, 7, "c".repeat(64), SOURCE,
                "ko_manifest_store", "synthetic:1", MODEL_REVISION, 2, count, "QUEUED", null, null, null, 0,
                CREATED, CREATED + 120_000, null, null));
        assertThat(jobs.claim(id, token, CREATED + 1, CREATED + 60_000)).isEqualTo(1);
    }

    private VectorIndexManifest capture(int count, boolean changed) {
        var points = new ArrayList<Point>();
        for (int n = 1; n <= count; n++) points.add(new Point(1000L + n, SOURCE,
                new Vector("synthetic:1", List.of(changed && n == count ? 4.0 : 3.0, 4.0))));
        return VectorIndexManifest.capture(new Index("ko_manifest_store", "synthetic:1", MODEL_REVISION, 2),
                new Scope(1, 401, 501, 701, generation), 7, SOURCE, count, points);
    }
    private int store(VectorIndexManifest input) { return tx.execute(status -> manifests.persist(id, token, input, clock)); }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_manifest_entry", Integer.class); }
    private void conflict(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(409));
    }
    private static Clock fixed(long ms) { return Clock.fixed(Instant.ofEpochMilli(ms), ZoneOffset.UTC); }
    private static Clock changing(long first, long second) {
        var calls = new AtomicInteger();
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(calls.getAndIncrement() == 0 ? first : second); }
        };
    }

    @Test void schemaHasLogicalAssociationAndCompositeUniquenessWithoutBusinessBodies() throws Exception {
        try (var keys = anchor.getMetaData().getImportedKeys(null, null, "rag_vector_manifest_entry")) {
            assertThat(keys.next()).isFalse();
        }
        assertThat(migration).doesNotContain("FOREIGN KEY", "ALTER TABLE", "DROP TABLE", "IF NOT EXISTS");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name='rag_vector_manifest_entry'", String.class))
                .containsExactlyInAnyOrder("job_id", "chunk_id", "content_sha256", "vector_sha256");
    }

    @ParameterizedTest @ValueSource(ints = {1, 16, 17, 1000})
    void storesCompleteExpectedSetAndHeaderAtomicallyWithoutFinishingTask(int chunks) {
        jdbc.update("UPDATE rag_vector_job SET expected_chunks=? WHERE id=?", chunks, id);
        var expected = capture(chunks, false);
        assertThat(store(expected)).isEqualTo(1);
        assertThat(count()).isEqualTo(chunks);
        assertThat(manifests.rows(id)).isEqualTo(expected.expected());
        var current = jobs.find(id);
        assertThat(current.manifestSha256()).isEqualTo(expected.sha256());
        assertThat(current.status()).isEqualTo("RUNNING");
        assertThat(current.verifiedChunks()).isZero();
        assertThat(current.deadlineMs()).isEqualTo(CREATED + 120_000);
        assertThat(current.leaseUntilMs()).isEqualTo(CREATED + 60_000);
    }

    @Test void identicalRetryAfterNewMapperInstanceReturnsOriginalRowsWithoutInsertion() throws Exception {
        assertThat(store(manifest)).isEqualTo(1);
        manifests = session().getMapper(KnowledgeVectorManifestMapper.class);
        assertThat(store(manifest)).isZero();
        assertThat(count()).isEqualTo(17);
        assertThat(jobs.find(id).manifestSha256()).isEqualTo(manifest.sha256());
    }

    @Test void reversedInputOrderHasSameManifestAndDoesNotRewriteEntries() {
        store(manifest);
        var points = new ArrayList<Point>();
        for (int n = 17; n >= 1; n--) points.add(new Point(1000L + n, SOURCE, new Vector("synthetic:1", List.of(3.0, 4.0))));
        var reversed = VectorIndexManifest.capture(manifest.index(), manifest.scope(), 7, SOURCE, 17, points);
        assertThat(store(reversed)).isZero();
        assertThat(manifests.rows(id)).isEqualTo(manifest.expected());
    }

    @Test void changedVectorCannotReplaceBoundManifest() {
        store(manifest);
        conflict(() -> store(capture(17, true)));
        assertThat(manifests.rows(id)).isEqualTo(manifest.expected());
        assertThat(jobs.find(id).manifestSha256()).isEqualTo(manifest.sha256());
    }

    @Test void noAmbientTransactionIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> manifests.persist(id, token, manifest, clock))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @Test void readOnlyTransactionCannotAccidentallyPersistManifest() {
        var readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> manifests.persist(id, token, manifest, clock)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "bad", "' OR 1=1 --", "00000000-0000-4000-8000-00000000000A"})
    void malformedIdOrTokenIsRejectedWithoutSqlInjection(String value) {
        for (Runnable work : List.<Runnable>of(() -> manifests.persist(value, token, manifest, clock),
                () -> manifests.persist(id, value, manifest, clock))) {
            assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(400));
        }
        assertThat(count()).isZero();
        assertThat(manifests.rows("' OR 1=1 --")).isEmpty();
    }

    @Test void nullManifestOrClockIsRejected() {
        assertThatThrownBy(() -> manifests.persist(id, token, null, clock)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manifests.persist(id, token, manifest, null)).isInstanceOf(BusinessException.class);
        assertThat(count()).isZero();
    }

    @Test void unknownTaskOrWrongLeaseCannotPersistOrDiscoverEntries() {
        conflict(() -> tx.execute(status -> manifests.persist(UUID.randomUUID().toString(), token, manifest, clock)));
        conflict(() -> tx.execute(status -> manifests.persist(id, UUID.randomUUID().toString(), manifest, clock)));
        assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {
            "enterprise_id=2", "space_id=402", "document_id=502", "version_id=702",
            "generation='00000000-0000-4000-8000-000000000001'", "expected_revision=8",
            "source_sha256=REPEAT('d',64)", "expected_chunks=16", "collection_name='ko_other'",
            "model_name='synthetic:2'", "model_revision=REPEAT('d',64)", "dimensions=3"})
    void everyHeaderFieldMustMatchBeforeAnyEntryIsInserted(String assignment) {
        // 仅固定测试常量，不接收用户SQL；生产Mapper全部参数绑定。
        jdbc.update("UPDATE rag_vector_job SET " + assignment + " WHERE id=?", id);
        conflict(() -> store(manifest));
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @ParameterizedTest @ValueSource(longs = {1_799_999_999_999L, 1_800_000_060_000L, 1_800_000_120_000L})
    void timeBeforeCreationOrAtLeaseOrDeadlineBoundaryCannotStore(long time) {
        conflict(() -> tx.execute(status -> manifests.persist(id, token, manifest, fixed(time))));
        assertThat(count()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "CANCELLED", "FAILED", "TIMED_OUT", "VERIFIED"})
    void onlyRunningTasksCanBindOrReplayManifest(String state) {
        if (state.equals("QUEUED")) jdbc.update("UPDATE rag_vector_job SET status='QUEUED' WHERE id=?", id);
        else if (state.equals("CANCELLED")) jobs.cancel(id, CREATED + 5);
        else if (state.equals("FAILED")) jobs.fail(id, token, "INTERNAL_ERROR", CREATED + 5);
        else if (state.equals("TIMED_OUT")) jobs.expire(CREATED + 120_000);
        else { store(manifest); jobs.verified(id, token, manifest.sha256(), 17, CREATED + 5); }
        int before = count();
        conflict(() -> store(manifest));
        assertThat(count()).isEqualTo(before);
    }

    @Test void partialLegacyEntriesAreNotAdoptedDeletedOrCompleted() {
        manifests.insertRows(id, manifest.expected().subList(0, 1));
        conflict(() -> store(manifest));
        assertThat(count()).isEqualTo(1);
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"DELETE FROM rag_vector_manifest_entry WHERE chunk_id=1001",
            "UPDATE rag_vector_manifest_entry SET content_sha256=REPEAT('d',64) WHERE chunk_id=1001",
            "UPDATE rag_vector_manifest_entry SET vector_sha256=REPEAT('d',64) WHERE chunk_id=1001",
            "UPDATE rag_vector_job SET manifest_sha256=REPEAT('d',64)"})
    void corruptedBoundRowsOrHeaderNeverReturnIdempotentSuccess(String sql) {
        store(manifest);
        jdbc.update(sql);
        conflict(() -> store(manifest));
    }

    @Test void extraStoredEntryCannotHideBehindExpectedCount() {
        store(manifest);
        jdbc.update("INSERT INTO rag_vector_manifest_entry VALUES(?,?,?,?)", id, 9999, SOURCE, SOURCE);
        conflict(() -> store(manifest));
        assertThat(count()).isEqualTo(18);
    }

    @Test void boundedReadDetectsOversizedCorruptManifestInsteadOfAcceptingFirstThousand() {
        jdbc.update("UPDATE rag_vector_job SET expected_chunks=1000 WHERE id=?", id);
        var full = capture(1000, false);
        store(full);
        jdbc.update("INSERT INTO rag_vector_manifest_entry VALUES(?,?,?,?),(?,?,?,?)",
                id, 9998, SOURCE, SOURCE, id, 9999, SOURCE, SOURCE);
        assertThat(manifests.rows(id)).hasSize(1001);
        conflict(() -> store(full));
        assertThat(count()).isEqualTo(1002);
    }

    @ParameterizedTest @ValueSource(longs = {1_800_000_000_009L, 1_800_000_060_000L, 1_800_000_120_000L})
    void lateSealOrClockRollbackRollsBackAllInsertedEntries(long end) {
        conflict(() -> tx.execute(status -> manifests.persist(id, token, manifest, changing(CREATED + 10, end))));
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @Test void lateIdempotentReadAlsoFailsRatherThanPretendingLeaseWasRenewed() {
        store(manifest);
        conflict(() -> tx.execute(status -> manifests.persist(id, token, manifest,
                changing(CREATED + 10, CREATED + 60_000))));
        assertThat(count()).isEqualTo(17);
    }

    @Test void realDatabaseErrorAtSealRollsBackEntriesAndHeaderTogether() {
        jdbc.execute("ALTER TABLE rag_vector_job ADD CONSTRAINT synthetic_block_seal CHECK(manifest_sha256 IS NULL)");
        assertThatThrownBy(() -> store(manifest)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @Test void callerFailureAfterPersistRollsBackBothTables() {
        assertThatThrownBy(() -> tx.execute(status -> {
            manifests.persist(id, token, manifest, clock);
            throw new IllegalStateException("合成上层失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
        assertThat(jobs.find(id).manifestSha256()).isNull();
    }

    @Test void actualCommitWithLostAcknowledgementIsRecoveredWithoutReinserting() {
        var loseOnce = new AtomicBoolean(true);
        var uncertain = new TransactionTemplate(new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if (loseOnce.compareAndSet(true, false)) throw new TransactionSystemException("合成提交回执丢失");
            }
        });
        assertThatThrownBy(() -> uncertain.execute(status -> manifests.persist(id, token, manifest, clock)))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(count()).isEqualTo(17);
        assertThat(store(manifest)).isZero();
        assertThat(count()).isEqualTo(17);
    }

    @Test void sameManifestConcurrentTransactionsInsertOnceAndOtherReturnsIdempotent() throws Exception {
        assertThat(compete(manifest, manifest)).containsExactlyInAnyOrder(1, 0);
        assertThat(count()).isEqualTo(17);
    }

    @Test void differentManifestConcurrentTransactionsKeepExactlyOneWinner() throws Exception {
        assertThat(compete(manifest, capture(17, true))).containsExactlyInAnyOrder(1, 409);
        assertThat(count()).isEqualTo(17);
        assertThat(jobs.find(id).manifestSha256()).isIn(manifest.sha256(), capture(17, true).sha256());
    }

    @Test void cancellationWaitsForManifestTransactionAndNeverReportsVerified() throws Exception {
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelling = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> tx.execute(status -> {
                int result = manifests.persist(id, token, manifest, clock);
                saved.countDown(); await(release); return result;
            }));
            assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> { cancelling.countDown(); return jobs.cancel(id, CREATED + 20); });
            assertThat(cancelling.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(count()).isZero(); // 不向其他事务暴露尚未提交的半份清单。
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(cancel.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jobs.find(id).status()).isEqualTo("CANCELLED");
            assertThat(jobs.find(id).verifiedChunks()).isZero();
            conflict(() -> store(manifest));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void taskLockContentionStopsWithinBoundWithoutPartialRowsOrAutomaticRetry() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var blocker = pool.submit(() -> tx.execute(status -> {
                manifests.lock(id, token, CREATED + 10); locked.countDown(); await(release); return null;
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            long began = System.nanoTime();
            assertThatThrownBy(() -> store(manifest)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - began)).isLessThan(java.time.Duration.ofSeconds(5));
            assertThat(count()).isZero();
            release.countDown(); blocker.get(5, TimeUnit.SECONDS);
            assertThat(jobs.find(id).manifestSha256()).isNull();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(6, TimeUnit.SECONDS)) throw new AssertionError("测试锁释放等待超时"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    private List<Integer> compete(VectorIndexManifest a, VectorIndexManifest b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Supplier<Integer> first = () -> outcome(a, start);
            var one = pool.submit(first::get);
            var two = pool.submit(() -> outcome(b, start));
            start.countDown();
            return List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private int outcome(VectorIndexManifest input, CountDownLatch start) {
        try {
            if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("并发起跑超时");
            return store(input);
        } catch (BusinessException ex) { return ex.getCode(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "bad", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "' OR 1=1 --"})
    void databaseRejectsMalformedContentAndVectorDigests(String value) {
        for (String field : List.of("content_sha256", "vector_sha256")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO rag_vector_manifest_entry(job_id,chunk_id," + field
                    + "," + (field.equals("content_sha256") ? "vector_sha256" : "content_sha256") + ") VALUES(?,?,?,?)",
                    id, 1001, value, SOURCE)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(count()).isZero();
    }

    @Test void duplicateChunkOrNonpositiveChunkCannotProducePartialBatch() {
        var row = manifest.expected().get(0);
        assertThatThrownBy(() -> tx.execute(status -> manifests.insertRows(id, List.of(row, row))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rag_vector_manifest_entry VALUES(?,?,?,?)", id, 0, SOURCE, SOURCE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
