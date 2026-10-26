package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import org.apache.ibatis.annotations.Options;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 真正执行增量DDL、Mapper及事务的隔离H2测试；不访问开发MySQL、Qdrant或模型。 */
class KnowledgeVectorJobMapperTest {
    private final long created = 1_800_000_000_000L;
    private final String token = UUID.randomUUID().toString();
    private final String hash = "a".repeat(64);
    private Connection anchor;
    private JdbcTemplate jdbc;
    private KnowledgeVectorJobMapper jobs;
    private TransactionTemplate tx;
    private MybatisConfiguration configuration;
    private String migration;

    @BeforeEach void setup() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:vector_jobs_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=3000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        anchor = dataSource.getConnection();
        migration = Files.readString(Path.of("sql/migrations/20261019_04_knowledge_vector_jobs.sql"), StandardCharsets.UTF_8);
        // H2不支持部分MySQL字符集/排序规则语法，仅移除这两类声明，不修改约束或任务SQL。
        String script = migration.replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                .replaceAll("(?i)CHARACTER SET ascii", "");
        ScriptUtils.executeSqlScript(anchor, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
        jdbc = new JdbcTemplate(dataSource);
        configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var sessionFactory = factory.getObject();
        configuration.addMapper(KnowledgeVectorJobMapper.class);
        jobs = new SqlSessionTemplate(sessionFactory).getMapper(KnowledgeVectorJobMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setTimeout(3);
    }

    @AfterEach void close() throws Exception { if (anchor != null) anchor.close(); }

    @Test void gateAndSchemaAreSeparateFromPublishedIndexes() throws Exception {
        assertThat(jobs.lockGate()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rag_vector_gate VALUES(2)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (String table : List.of("rag_vector_gate", "rag_vector_job")) {
            try (var keys = anchor.getMetaData().getImportedKeys(null, null, table)) {
                assertThat(keys.next()).isFalse();
            }
        }
        assertThat(migration).doesNotContain("FOREIGN KEY", "ALTER TABLE", "DROP TABLE", "IF NOT EXISTS");
        assertThat(count()).isZero();
    }

    @Test void insertsAndReadsAllImmutableInputsWithoutRecordingBodies() {
        Stored seed = seed(1, 101, 501, 601);
        assertThat(jobs.insert(seed)).isEqualTo(1);
        assertThat(jobs.find(seed.id())).isEqualTo(seed);
        assertThat(seed.toString()).isEqualTo("VectorJob[status=QUEUED]").doesNotContain(seed.id(), seed.modelName(), hash);
        assertThat(jobs.find(UUID.randomUUID().toString())).isNull();
        assertThat(jobs.find("' OR 1=1 --")).isNull();
        assertThat(count()).isEqualTo(1);
    }

    @Test void duplicateRequestOrGenerationNeverOverwritesOriginalInputs() {
        Stored first = seed(1, 101, 501, 601);
        jobs.insert(first);
        assertThatThrownBy(() -> jobs.insert(first)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        Stored other = seed(2, 201, 502, 602);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rag_vector_job(id,generation,enterprise_id,space_id,document_id,version_id,member_id,user_id,
                  auth_version,expected_revision,request_sha256,source_sha256,collection_name,model_name,model_revision,
                  dimensions,expected_chunks,created_ms,deadline_ms)
                SELECT ?,generation,enterprise_id,space_id,document_id,version_id,member_id,user_id,auth_version,
                  expected_revision,request_sha256,source_sha256,collection_name,model_name,model_revision,
                  dimensions,expected_chunks,created_ms,deadline_ms FROM rag_vector_job WHERE id=?
                """, other.id(), first.id())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jobs.find(first.id())).isEqualTo(first);
        assertThat(count()).isEqualTo(1);
    }

    @Test void ownerAndVersionCountsUseAllTenantScopeFields() {
        jobs.insert(seed(1, 101, 501, 601));
        jobs.insert(seed(1, 102, 501, 602));
        jobs.insert(seed(2, 101, 501, 601));
        assertThat(jobs.activeTotal()).isEqualTo(3);
        assertThat(jobs.activeOwner(1, 101)).isEqualTo(1);
        assertThat(jobs.activeOwner(2, 101)).isEqualTo(1);
        assertThat(jobs.activeVersion(1, 401, 501, 601)).isEqualTo(1);
        assertThat(jobs.activeVersion(1, 402, 501, 601)).isZero();
        assertThat(jobs.activeVersion(1, 401, 502, 601)).isZero();
        assertThat(jobs.activeVersion(1, 401, 501, 602)).isEqualTo(1);
    }

    @Test void dispatchOnlySelectsUpToSixteenUnexpiredQueuedTasksInStableOrder() {
        for (int n = 0; n < 20; n++) jobs.insert(seed(1, 101, 501 + n, 601 + n));
        var ordered = jobs.pending(created + 1);
        assertThat(ordered).hasSize(16);
        assertThat(ordered.stream().map(Stored::id).toList()).isSorted();
        assertThat(jobs.claim(ordered.get(0).id(), token, created + 1, created + 500)).isEqualTo(1);
        assertThat(jobs.pending(created + 2)).hasSize(16).noneMatch(j -> j.id().equals(ordered.get(0).id()));
        assertThat(jobs.pending(created - 1)).isEmpty();
        assertThat(jobs.pending(created + 30_000)).isEmpty();
    }

    @Test void claimIsOneShotCapsLeaseAndPreservesFirstDeadline() {
        Stored seed = insert();
        assertThat(jobs.claim(seed.id(), token, created + 1, created + 90_000)).isEqualTo(1);
        var current = jobs.find(seed.id());
        assertThat(current.status()).isEqualTo("RUNNING");
        assertThat(current.leaseUntilMs()).isEqualTo(seed.deadlineMs());
        assertThat(current.deadlineMs()).isEqualTo(seed.deadlineMs());
        assertThat(jobs.claim(seed.id(), UUID.randomUUID().toString(), created + 2, created + 3)).isZero();
        assertThat(jobs.owns(seed.id(), token, created + 1)).isEqualTo(1);
        assertThat(jobs.owns(seed.id(), UUID.randomUUID().toString(), created + 1)).isZero();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "bad", "' OR 1=1 --"})
    void malformedLeaseTokenDoesNotStartTask(String invalidToken) {
        Stored seed = insert();
        assertThat(jobs.claim(seed.id(), invalidToken, created + 1, created + 100)).isZero();
        assertThat(jobs.find(seed.id()).status()).isEqualTo("QUEUED");
    }

    @Test void noTimeTravelOrZeroLeaseCanStartTask() {
        Stored seed = insert();
        assertThat(jobs.claim(seed.id(), token, created - 1, created + 100)).isZero();
        assertThat(jobs.claim(seed.id(), token, created + 1, created + 1)).isZero();
        assertThat(jobs.claim(seed.id(), token, created + 30_000, created + 30_001)).isZero();
        assertThat(jobs.owns(seed.id(), token, created)).isZero();
    }

    @Test void sealOnceThenVerifyRequiresSameManifestAndCompleteCount() {
        Stored seed = running();
        assertThat(jobs.verified(seed.id(), token, hash, 17, created + 2)).isZero();
        assertThat(jobs.bindManifest(seed.id(), token, hash, 16, created + 2)).isZero();
        assertThat(jobs.bindManifest(seed.id(), token, hash, 17, created + 2)).isEqualTo(1);
        assertThat(jobs.bindManifest(seed.id(), token, "b".repeat(64), 17, created + 2)).isZero();
        assertThat(jobs.verified(seed.id(), token, hash, 16, created + 3)).isZero();
        assertThat(jobs.verified(seed.id(), token, "b".repeat(64), 17, created + 3)).isZero();
        assertThat(jobs.verified(seed.id(), token, hash, 17, created + 3)).isEqualTo(1);
        Stored done = jobs.find(seed.id());
        assertThat(done.status()).isEqualTo("VERIFIED");
        assertThat(done.verifiedChunks()).isEqualTo(17);
        assertThat(done.finishedMs()).isEqualTo(created + 3);
        assertThat(done.deadlineMs()).isEqualTo(seed.deadlineMs());
        assertThat(jobs.activeTotal()).isZero();
        assertThat(jobs.cancel(seed.id(), created + 4)).isZero();
        assertThat(jobs.fail(seed.id(), token, "INTERNAL_ERROR", created + 4)).isZero();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "invalid", "' OR 1=1 --"})
    void malformedManifestCannotBeBound(String manifest) {
        Stored seed = running();
        assertThat(jobs.bindManifest(seed.id(), token, manifest, 17, created + 2)).isZero();
        assertThat(jobs.find(seed.id()).manifestSha256()).isNull();
    }

    @Test void staleTokenAndLeaseCannotBindFailOrFinish() {
        Stored seed = running();
        String stale = UUID.randomUUID().toString();
        assertThat(jobs.bindManifest(seed.id(), stale, hash, 17, created + 2)).isZero();
        assertThat(jobs.fail(seed.id(), stale, "INTERNAL_ERROR", created + 2)).isZero();
        assertThat(jobs.bindManifest(seed.id(), token, hash, 17, created + 15_000)).isZero();
        assertThat(jobs.verified(seed.id(), token, hash, 17, created + 15_000)).isZero();
        assertThat(jobs.fail(seed.id(), token, "INTERNAL_ERROR", created + 15_000)).isZero();
        assertThat(jobs.owns(seed.id(), token, created - 1)).isZero();
        assertThat(jobs.owns(seed.id(), token, created + 15_000)).isZero();
    }

    @Test void lostLeaseFailsWithoutAutomaticReplayOrGenerationReuse() {
        Stored seed = running();
        assertThat(jobs.abandon(created + 15_000)).isEqualTo(1);
        var lost = jobs.find(seed.id());
        assertThat(lost.status()).isEqualTo("FAILED");
        assertThat(lost.failureCode()).isEqualTo("LEASE_LOST");
        assertThat(lost.generation()).isEqualTo(seed.generation());
        assertThat(jobs.pending(created + 15_001)).isEmpty();
        assertThat(jobs.claim(seed.id(), UUID.randomUUID().toString(), created + 15_001, created + 20_000)).isZero();
        assertThat(jobs.verified(seed.id(), token, hash, 17, created + 15_001)).isZero();
    }

    @Test void deadlineWinsOverLeaseLossAndCannotBeExtendedByLateCompletion() {
        Stored seed = running();
        assertThat(jobs.abandon(created + 30_000)).isZero();
        assertThat(jobs.expire(created + 30_000)).isEqualTo(1);
        assertThat(jobs.find(seed.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(jobs.find(seed.id()).failureCode()).isEqualTo("TOTAL_TIMEOUT");
        assertThat(jobs.expire(created + 30_001)).isZero();
        assertThat(jobs.verified(seed.id(), token, hash, 17, created + 30_001)).isZero();
        assertThat(jobs.cancel(seed.id(), created + 30_001)).isZero();
    }

    @Test void cancellationFreezesQueuedOrRunningState() {
        Stored queued = insert();
        assertThat(jobs.cancel(queued.id(), created + 1)).isEqualTo(1);
        assertThat(jobs.claim(queued.id(), token, created + 2, created + 100)).isZero();
        Stored running = running();
        assertThat(jobs.bindManifest(running.id(), token, hash, 17, created + 2)).isEqualTo(1);
        assertThat(jobs.cancel(running.id(), created + 3)).isEqualTo(1);
        assertThat(jobs.verified(running.id(), token, hash, 17, created + 4)).isZero();
        assertThat(jobs.find(running.id()).verifiedChunks()).isZero();
        assertThat(jobs.expire(created + 30_001)).isZero();
        assertThat(jobs.abandon(created + 15_000)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"ACCESS_REVOKED", "REVISION_CONFLICT", "MODEL_CHANGED", "MANIFEST_MISMATCH",
            "WRITE_UNCONFIRMED", "VECTOR_UNAVAILABLE", "INTERNAL_ERROR"})
    void onlySafeFailureClassificationCanBePersisted(String failure) {
        Stored seed = running();
        assertThat(jobs.fail(seed.id(), token, failure, created + 2)).isEqualTo(1);
        assertThat(jobs.find(seed.id()).failureCode()).isEqualTo(failure);
        assertThat(jobs.find(seed.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.fail(seed.id(), token, "INTERNAL_ERROR", created + 3)).isZero();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "token=TEST_SECRET", "' OR 1=1 --"})
    void arbitraryErrorTextIsNotStored(String failure) {
        Stored seed = running();
        assertThat(jobs.fail(seed.id(), token, failure, created + 2)).isZero();
        assertThat(jobs.find(seed.id()).failureCode()).isNull();
    }

    @Test void expiryAndLeaseCleanupAreBoundedToSixtyFourRows() {
        for (int n = 0; n < 65; n++) jobs.insert(seed(1, 101, 501 + n, 601 + n));
        assertThat(jobs.expire(created + 30_000)).isEqualTo(64);
        assertThat(jobs.activeTotal()).isEqualTo(1);
        assertThat(jobs.expire(created + 30_000)).isEqualTo(1);
        for (int n = 0; n < 65; n++) {
            Stored seed = seed(1, 101, 701 + n, 801 + n);
            jobs.insert(seed);
            assertThat(jobs.claim(seed.id(), UUID.randomUUID().toString(), created + 1, created + 100)).isEqualTo(1);
        }
        assertThat(jobs.abandon(created + 100)).isEqualTo(64);
        assertThat(jobs.activeTotal()).isEqualTo(1);
        assertThat(jobs.abandon(created + 100)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"dimensions=0", "dimensions=2049", "expected_chunks=0", "expected_chunks=1001",
            "enterprise_id=0", "auth_version=0", "expected_revision=0", "created_ms=-1", "deadline_ms=created_ms",
            "status='READY'", "status='RUNNING'", "status='VERIFIED'", "status='FAILED'", "verified_chunks=1"})
    void schemaRejectsInvalidStateOrBounds(String assignment) {
        Stored seed = insert();
        // assignment仅来自编译期测试常量；业务Mapper不得照此拼接用户输入。
        assertThatThrownBy(() -> jdbc.update("UPDATE rag_vector_job SET " + assignment + " WHERE id=?", seed.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jobs.find(seed.id()).status()).isEqualTo("QUEUED");
    }

    @Test void transactionsRollBackRequestAndManifestAndTerminalTogether() {
        Stored seed = seed(1, 101, 501, 601);
        assertThatThrownBy(() -> tx.execute(status -> {
            jobs.lockGate();
            jobs.insert(seed);
            throw new IllegalStateException("测试受理回滚");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.find(seed.id())).isNull();
        jobs.insert(seed);
        assertThat(jobs.claim(seed.id(), token, created + 1, created + 15_000)).isEqualTo(1);
        assertThatThrownBy(() -> tx.execute(status -> {
            assertThat(jobs.bindManifest(seed.id(), token, hash, 17, created + 2)).isEqualTo(1);
            assertThat(jobs.verified(seed.id(), token, hash, 17, created + 3)).isEqualTo(1);
            throw new IllegalStateException("测试终态回滚");
        })).isInstanceOf(IllegalStateException.class);
        Stored after = jobs.find(seed.id());
        assertThat(after.status()).isEqualTo("RUNNING");
        assertThat(after.manifestSha256()).isNull();
        assertThat(after.verifiedChunks()).isZero();
    }

    @Test void committedTerminalSurvivesLostAcknowledgementAndRejectsLateFailure() {
        Stored seed = running();
        assertThatThrownBy(() -> {
            tx.execute(status -> {
                assertThat(jobs.bindManifest(seed.id(), token, hash, 17, created + 2)).isEqualTo(1);
                assertThat(jobs.verified(seed.id(), token, hash, 17, created + 3)).isEqualTo(1);
                return null;
            });
            throw new IllegalStateException("测试提交后回执丢失");
        }).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.find(seed.id()).status()).isEqualTo("VERIFIED");
        assertThat(jobs.find(seed.id()).manifestSha256()).isEqualTo(hash);
        assertThat(jobs.fail(seed.id(), token, "WRITE_UNCONFIRMED", created + 4)).isZero();
        assertThat(jobs.claim(seed.id(), token, created + 4, created + 100)).isZero();
    }

    @Test void concurrentClaimHasOnlyOneWinnerAndNoInMemoryMutex() throws Exception {
        Stored seed = insert();
        List<Integer> results = race(
                () -> jobs.claim(seed.id(), UUID.randomUUID().toString(), created + 1, created + 100),
                () -> jobs.claim(seed.id(), UUID.randomUUID().toString(), created + 1, created + 100));
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(jobs.find(seed.id()).status()).isEqualTo("RUNNING");
    }

    @Test void concurrentCancelAndCompletionCannotOverwriteEachOthersTerminal() throws Exception {
        Stored seed = running();
        jobs.bindManifest(seed.id(), token, hash, 17, created + 2);
        List<Integer> results = race(() -> jobs.cancel(seed.id(), created + 3),
                () -> jobs.verified(seed.id(), token, hash, 17, created + 3));
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        Stored terminal = jobs.find(seed.id());
        assertThat(terminal.status()).isIn("VERIFIED", "CANCELLED");
        assertThat(terminal.verifiedChunks()).isEqualTo(terminal.status().equals("VERIFIED") ? 17 : 0);
    }

    @Test void gateCanSerializeCapacityCheckAndInsertWithinTransaction() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        Callable<Integer> submit = () -> tx.execute(status -> {
            jobs.lockGate();
            if (jobs.activeTotal() != 0) return 0;
            int result = jobs.insert(seed(1, 101, 501, 601));
            admitted.addAndGet(result);
            return result;
        });
        var results = race(submit, submit);
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(admitted.get()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test void queryTimeoutsAndBoundParametersAreAppliedToEveryOperation() {
        for (var method : KnowledgeVectorJobMapper.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Options.class)).as(method.getName()).isNotNull();
            assertThat(method.getAnnotation(Options.class).timeout()).isEqualTo(2);
        }
        var bound = configuration.getMappedStatement(KnowledgeVectorJobMapper.class.getName() + ".find")
                .getBoundSql("' OR 1=1 --");
        assertThat(bound.getSql()).contains("id=?").doesNotContain("OR 1=1");
    }

    private Stored seed(long enterprise, long member, long document, long version) {
        return new Stored(UUID.randomUUID().toString(), UUID.randomUUID().toString(), enterprise, 401, document, version,
                member, member, 1, 7, hash, hash, "ko_jobs_unit", "synthetic:v1", hash, 2, 17,
                "QUEUED", null, null, null, 0, created, created + 30_000, null, null);
    }
    private Stored insert() { Stored seed = seed(1, 101, 501, 601); jobs.insert(seed); return seed; }
    private Stored running() {
        Stored seed = insert();
        assertThat(jobs.claim(seed.id(), token, created + 1, created + 15_000)).isEqualTo(1);
        return seed;
    }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job", Integer.class); }
    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
