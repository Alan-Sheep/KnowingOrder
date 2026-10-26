package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.TextEmbeddings;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 显式运行的本机MySQL仓储验收，不启动应用、不执行DDL、不调用Embedding或Qdrant。
 * 每例独立提交合成企业数据，事务外检查结果；清理同时核对生成主键和随机标记。
 * 本类直接调用内部Mapper，不替代服务层授权、真实向量完整核验或生产容量验收。
 */
@Timeout(30)
class KnowledgeVectorMysqlIT {
    // 合成未来时刻令应用的当前时间调度器不能领取这些测试任务；测试不调用全局过期扫描。
    private static final long NOW = 4_000_000_000_000L;
    private static final List<String> TEXT = List.of("虚构制度：餐补三十元。", "虚构制度：需主管审批。");
    private static final String SOURCE = hash(String.join("\n", TEXT));
    private static final QdrantVectorClient.Index INDEX = new QdrantVectorClient.Index(
            "ko_mysql_it", "synthetic:1", "a".repeat(64), 2);
    private final String marker = "it_vector_" + UUID.randomUUID().toString().replace("-", "");
    private final List<String> taskIds = new ArrayList<>();
    private final List<Long> chunkIds = new ArrayList<>();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private KnowledgeVectorPublicationMapper publications;
    private EnterpriseMemberContext scope;
    private long enterpriseId;
    private long userId;
    private long memberId;
    private long spaceId;
    private long documentId;
    private long versionId;

    private record Build(Stored job, String token, VectorIndexManifest manifest) { }

    @BeforeEach
    void setup() throws Exception {
        String url = required("DB_URL");
        // 不输出连接串；只接受已确认的本机开发库，禁止凭据藏在URL里。
        assertThat(url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/rag_qa(\\?[^\\r\\n]*)?"))
                .as("本验收仅接受本机、显式端口、rag_qa开发库").isTrue();
        assertThat(url.matches("(?is).*[?&](user|password)=.*"))
                .as("账号密码只能由独立环境变量注入，不能写入连接串").isFalse();
        dataSource = new DriverManagerDataSource(url, required("DB_USERNAME"), required("DB_PASSWORD"));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "3000");
        properties.setProperty("socketTimeout", "8000");
        properties.setProperty("sessionVariables", "innodb_lock_wait_timeout=2");
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("rag_qa");
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_NAME IN (
                    'sys_user','sys_enterprise','sys_enterprise_member','rag_knowledge_space',
                    'rag_knowledge_grant','rag_document','rag_document_version','rag_chunk',
                    'rag_vector_gate','rag_vector_job','rag_vector_manifest_entry','rag_vector_publication')
                """, Integer.class)).as("所需12表须已迁移且可见；本测试不自动建表").isEqualTo(12);
        assertThat(jdbc.queryForList("SELECT id FROM rag_vector_gate", Integer.class)).containsExactly(1);
        manager = new DataSourceTransactionManager(dataSource);
        tx = transaction(manager);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setDefaultStatementTimeout(2);
        configuration.setLogImpl(NoLoggingImpl.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        for (Class<?> type : List.of(KnowledgeVectorJobMapper.class, KnowledgeVectorManifestMapper.class,
                KnowledgeVectorPublicationMapper.class)) configuration.addMapper(type);
        var sql = new SqlSessionTemplate(factory.getObject());
        jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
        manifests = sql.getMapper(KnowledgeVectorManifestMapper.class);
        publications = sql.getMapper(KnowledgeVectorPublicationMapper.class);
        tx.executeWithoutResult(status -> seed());
        scope = new EnterpriseMemberContext(enterpriseId, memberId, userId, null, "ADMIN", 1, 1, 1);
    }

    private void seed() {
        enterpriseId = insert("INSERT INTO sys_enterprise(name) VALUES(?)", marker);
        userId = insert("INSERT INTO sys_user(username,password_hash) VALUES(?,?)", marker, "不可登录的测试占位");
        memberId = insert("INSERT INTO sys_enterprise_member(enterprise_id,user_id,role) VALUES(?,?,'ADMIN')",
                enterpriseId, userId);
        spaceId = insert("INSERT INTO rag_knowledge_space(enterprise_id,name) VALUES(?,?)", enterpriseId, marker);
        jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) "
                + "VALUES(?,?,?,'MAINTAIN')", enterpriseId, spaceId, memberId);
        documentId = insert("""
                INSERT INTO rag_document(title,source_type,status,enterprise_id,knowledge_space_id,owner_member_id,revision)
                VALUES(?,'UPLOAD',2,?,?,?,7)
                """, marker, enterpriseId, spaceId, memberId);
        versionId = insert("""
                INSERT INTO rag_document_version(enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                  parse_status,publication_status,chunk_count,created_by_member)
                VALUES(?,?,?,1,?,'synthetic.md',?,?,?,100,'READY','PUBLISHED',2,?)
                """, enterpriseId, spaceId, documentId, uuid(), uuid(), SOURCE, "c".repeat(64), memberId);
        jdbc.update("UPDATE rag_document SET published_version_id=? WHERE id=? AND enterprise_id=?",
                versionId, documentId, enterpriseId);
        for (int n = 0; n < TEXT.size(); n++) chunkIds.add(insert("""
                INSERT INTO rag_chunk(document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                VALUES(?,?,?,?,0,?,1,1)
                """, documentId, versionId, n, TEXT.get(n), TEXT.get(n).length()));
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null || enterpriseId == 0) return;
        tx.executeWithoutResult(status -> {
            int owned = jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise WHERE id=? AND name=?",
                    Integer.class, enterpriseId, marker);
            if (owned == 0) return;
            jdbc.update("DELETE FROM rag_vector_publication WHERE enterprise_id=? AND space_id=? AND document_id=?",
                    enterpriseId, spaceId, documentId);
            jdbc.update("""
                    DELETE x FROM rag_vector_manifest_entry x JOIN rag_vector_job j ON j.id=x.job_id
                    WHERE j.enterprise_id=? AND j.member_id=? AND j.user_id=?
                    """, enterpriseId, memberId, userId);
            jdbc.update("DELETE FROM rag_vector_job WHERE enterprise_id=? AND member_id=? AND user_id=?",
                    enterpriseId, memberId, userId);
            jdbc.update("DELETE FROM rag_chunk WHERE document_id=? AND version_id=?", documentId, versionId);
            jdbc.update("DELETE FROM rag_document_version WHERE id=? AND document_id=? AND enterprise_id=?",
                    versionId, documentId, enterpriseId);
            jdbc.update("DELETE FROM rag_document WHERE id=? AND enterprise_id=? AND title=?",
                    documentId, enterpriseId, marker);
            jdbc.update("DELETE FROM rag_knowledge_grant WHERE enterprise_id=? AND knowledge_space_id=? AND member_id=?",
                    enterpriseId, spaceId, memberId);
            jdbc.update("DELETE FROM rag_knowledge_space WHERE id=? AND enterprise_id=? AND name=?", spaceId, enterpriseId, marker);
            jdbc.update("DELETE FROM sys_enterprise_member WHERE id=? AND enterprise_id=? AND user_id=?", memberId, enterpriseId, userId);
            jdbc.update("DELETE FROM sys_enterprise WHERE id=? AND name=?", enterpriseId, marker);
            jdbc.update("DELETE FROM sys_user WHERE id=? AND username=?", userId, marker);
        });
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM sys_enterprise WHERE id=?)
                  + (SELECT COUNT(*) FROM sys_user WHERE id=?)
                  + (SELECT COUNT(*) FROM sys_enterprise_member WHERE id=?)
                  + (SELECT COUNT(*) FROM rag_knowledge_space WHERE id=?)
                  + (SELECT COUNT(*) FROM rag_knowledge_grant WHERE enterprise_id=? AND knowledge_space_id=? AND member_id=?)
                  + (SELECT COUNT(*) FROM rag_document WHERE id=?)
                  + (SELECT COUNT(*) FROM rag_document_version WHERE id=?)
                  + (SELECT COUNT(*) FROM rag_chunk WHERE document_id=? AND version_id=?)
                  + (SELECT COUNT(*) FROM rag_vector_publication WHERE document_id=?)
                """, Integer.class, enterpriseId, userId, memberId, spaceId, enterpriseId, spaceId, memberId,
                documentId, versionId, documentId, versionId, documentId)).as("本例合成数据必须全部清理").isZero();
        for (String id : taskIds) {
            assertThat(jobs.find(id)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_manifest_entry WHERE job_id=?",
                    Integer.class, id)).isZero();
        }
        assertThat(jdbc.queryForList("SELECT id FROM rag_vector_gate", Integer.class)).containsExactly(1);
    }

    @Test
    void lifecycleSeparatesVerificationPublicationAndWithdrawal() {
        Build build = running();
        assertThat(jobs.find(build.job().id()).status()).isEqualTo("RUNNING");
        assertThat(store(build)).isEqualTo(1);
        assertThat(store(build)).isZero();
        assertThat(jobs.verified(build.job().id(), build.token(), build.manifest().sha256(), 1, NOW - 1000)).isZero();
        assertThat(jobs.verified(build.job().id(), build.token(), build.manifest().sha256(), 2, NOW - 1000)).isEqualTo(1);
        Stored verified = jobs.find(build.job().id());
        assertThat(candidate()).isNull();
        var first = publish(verified, build, 0);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(candidate()).isEqualTo(verified);
        assertThat(publish(verified, build, 0)).isEqualTo(first);
        var removed = tx.execute(status -> publications.withdraw(enterpriseId, spaceId, documentId, 1, verified.id(), fixed(NOW)));
        assertThat(removed.revision()).isEqualTo(2);
        assertThat(removed.jobId()).isNull();
        assertThat(candidate()).isNull();
        code(() -> publish(verified, build, 0), 409);
        assertThat(pointer()).isEqualTo(removed);
        assertThat(jdbc.queryForObject("SELECT revision FROM rag_document WHERE id=?", Long.class, documentId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT published_version_id FROM rag_document WHERE id=?", Long.class, documentId)).isEqualTo(versionId);
    }

    @Test
    void duplicateRowAfterSealingRollsBackBothManifestAndHeader() {
        Build build = running();
        mysqlError(() -> tx.executeWithoutResult(status -> {
            assertThat(manifests.persist(build.job().id(), build.token(), build.manifest(), fixed(NOW - 20_000))).isEqualTo(1);
            manifests.insertRows(build.job().id(), build.manifest().expected());
        }), 1062);
        assertThat(jobs.find(build.job().id()).manifestSha256()).isNull();
        assertThat(jobs.find(build.job().id()).status()).isEqualTo("RUNNING");
        var remaining = tx.execute(status -> manifests.rows(build.job().id()));
        assertThat(remaining).isEmpty();
    }

    @Test
    void databaseFailureAfterPointerInsertRollsBackPublication() {
        Build build = verified();
        mysqlError(() -> tx.executeWithoutResult(status -> {
            publications.publish(build.job(), build.manifest().expected(), 0, fixed(NOW));
            manifests.insertRows(build.job().id(), build.manifest().expected());
        }), 1062);
        assertThat(pointer()).isNull();
        assertThat(candidate()).isNull();
        assertThat(jobs.find(build.job().id())).isEqualTo(build.job());
    }

    @Test
    void mysqlEnforcesChecksNotNullAndAsciiCaseSensitivity() {
        Build build = verified();
        publish(build.job(), build, 0);
        mysqlError(() -> jdbc.update("UPDATE rag_vector_job SET dimensions=0 WHERE id=?", build.job().id()), 3819);
        mysqlError(() -> jdbc.update("UPDATE rag_vector_job SET generation=NULL WHERE id=?", build.job().id()), 1048);
        mysqlError(() -> jdbc.update("UPDATE rag_vector_manifest_entry SET content_sha256=? WHERE job_id=?",
                "A".repeat(64), build.job().id()), 3819);
        mysqlError(() -> jdbc.update("UPDATE rag_vector_publication SET revision=0 WHERE document_id=?", documentId), 3819);
        assertThat(jobs.find(build.job().id().toUpperCase(Locale.ROOT))).isNull();
        assertThat(jobs.find("' OR 1=1 --")).isNull();
        assertThat(jobs.find(build.job().id())).isEqualTo(build.job());
        assertThat(candidate()).isEqualTo(build.job());
    }

    @Test
    void concurrentDifferentGenerationsCannotBothPublishRevisionZero() throws Exception {
        Build first = verified(), second = verified();
        List<Integer> results = race(() -> publishAttempt(first), () -> publishAttempt(second));
        assertThat(results).containsExactlyInAnyOrder(1, 0);
        var current = pointer();
        assertThat(current.revision()).isEqualTo(1);
        assertThat(current.jobId()).isIn(first.job().id(), second.job().id());
        assertThat(candidate().id()).isEqualTo(current.jobId());
    }

    @Test
    void cancellationAndVerificationCommitOnlyOneTerminalState() throws Exception {
        Build build = running();
        store(build);
        List<Integer> results = race(
                () -> tx.execute(status -> jobs.cancel(build.job().id(), NOW - 1000)),
                () -> tx.execute(status -> jobs.verified(build.job().id(), build.token(), build.manifest().sha256(), 2, NOW - 1000)));
        assertThat(results).containsExactlyInAnyOrder(1, 0);
        assertThat(jobs.find(build.job().id()).status()).isIn("CANCELLED", "VERIFIED");
        assertThat(candidate()).isNull();
    }

    @Test
    void expiredLeaseAndTotalDeadlineRejectLateWritesWithoutExtension() {
        Build build = running();
        long leaseEnd = jobs.find(build.job().id()).leaseUntilMs();
        code(() -> tx.execute(status -> manifests.persist(build.job().id(), build.token(), build.manifest(), fixed(leaseEnd))), 409);
        assertThat(jobs.owns(build.job().id(), build.token(), leaseEnd)).isZero();
        assertThat(jobs.verified(build.job().id(), build.token(), build.manifest().sha256(), 2, leaseEnd)).isZero();
        assertThat(jobs.cancel(build.job().id(), build.job().deadlineMs())).isZero();
        assertThat(jobs.find(build.job().id()).deadlineMs()).isEqualTo(build.job().deadlineMs());
        var remaining = tx.execute(status -> manifests.rows(build.job().id()));
        assertThat(remaining).isEmpty();
    }

    @Test
    void candidateReadSeesCommittedRevocationAndRejectsRepeatableRead() throws Exception {
        Build build = verified();
        publish(build.job(), build, 0);
        var foreign = new EnterpriseMemberContext(enterpriseId + 1, memberId, userId, null, "ADMIN", 1, 1, 1);
        assertThat(publications.candidate(foreign, spaceId, documentId, NOW)).isNull();
        var repeatable = transaction(manager);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        code(() -> repeatable.execute(status -> candidate()), 503);
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(candidate()).isEqualTo(build.job());
                var revoked = executor.submit(() -> jdbc.update("UPDATE rag_knowledge_grant SET status=1 "
                        + "WHERE enterprise_id=? AND knowledge_space_id=? AND member_id=?", enterpriseId, spaceId, memberId));
                try { assertThat(revoked.get(5, TimeUnit.SECONDS)).isEqualTo(1); }
                catch (Exception ex) { throw new IllegalStateException("独立连接撤权未在时限内完成", ex); }
                assertThat(candidate()).isNull();
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void documentLockWaitEndsWithinBoundAndDoesNotLeavePointer() throws Exception {
        Build build = verified();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(publications.lockDocument(enterpriseId, spaceId, documentId)).isEqualTo(documentId);
                locked.countDown();
                await(release, 8);
            }));
            try {
                assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                assertThatThrownBy(() -> publish(build.job(), build, 0)).isInstanceOf(DataAccessException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(8000);
            } finally { release.countDown(); }
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(pointer()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void knownCommitWithLostReceiptIsReconciledByOriginalCas() {
        Build build = verified();
        PlatformTransactionManager lost = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                throw new TransactionSystemException("模拟真实提交后回执丢失");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        assertThatThrownBy(() -> transaction(lost).execute(status ->
                publications.publish(build.job(), build.manifest().expected(), 0, fixed(NOW))))
                .isInstanceOf(TransactionSystemException.class);
        var committed = pointer();
        assertThat(committed.revision()).isEqualTo(1);
        assertThat(publish(build.job(), build, 0)).isEqualTo(committed);
        assertThat(pointer().revision()).isEqualTo(1);
    }

    private Build running() {
        String id = uuid(), generation = uuid(), token = uuid();
        var job = new Stored(id, generation, enterpriseId, spaceId, documentId, versionId, memberId, userId,
                1, 7, "c".repeat(64), SOURCE, INDEX.collection(), INDEX.model(), INDEX.revision(), 2, 2,
                "QUEUED", null, null, null, 0, NOW - 50_000, NOW + 70_000, null, null);
        taskIds.add(id);
        assertThat(jobs.insert(job)).isEqualTo(1);
        assertThat(jobs.find(id).status()).isEqualTo("QUEUED");
        assertThat(jobs.claim(id, token, NOW - 40_000, NOW + 60_000)).isEqualTo(1);
        var points = new ArrayList<QdrantVectorClient.Point>();
        for (int n = 0; n < TEXT.size(); n++) points.add(new QdrantVectorClient.Point(chunkIds.get(n), hash(TEXT.get(n)),
                new TextEmbeddings.Vector(INDEX.model(), List.of(3.0, 4.0))));
        return new Build(job, token, VectorIndexManifest.capture(INDEX,
                new QdrantVectorClient.Scope(enterpriseId, spaceId, documentId, versionId, generation), 7, SOURCE, 2, points));
    }

    private Build verified() {
        Build build = running();
        assertThat(store(build)).isEqualTo(1);
        assertThat(jobs.verified(build.job().id(), build.token(), build.manifest().sha256(), 2, NOW - 1000)).isEqualTo(1);
        return new Build(jobs.find(build.job().id()), build.token(), build.manifest());
    }

    private int store(Build build) {
        return tx.execute(status -> manifests.persist(build.job().id(), build.token(), build.manifest(), fixed(NOW - 20_000)));
    }

    private KnowledgeVectorPublicationMapper.Pointer publish(Stored job, Build build, long revision) {
        return tx.execute(status -> publications.publish(job, build.manifest().expected(), revision, fixed(NOW)));
    }

    private KnowledgeVectorPublicationMapper.Pointer pointer() {
        return tx.execute(status -> publications.lockPointer(enterpriseId, spaceId, documentId));
    }

    private Stored candidate() { return publications.candidate(scope, spaceId, documentId, NOW); }

    private int publishAttempt(Build build) {
        try { publish(build.job(), build, 0); return 1; }
        catch (BusinessException ex) { assertThat(ex.getCode()).isEqualTo(409); return 0; }
    }

    private static List<Integer> race(Callable<Integer> left, Callable<Integer> right) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { ready.countDown(); await(start, 5); return left.call(); });
            var second = executor.submit(() -> { ready.countDown(); await(start, 5); return right.call(); });
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch, long seconds) {
        try { assertThat(latch.await(seconds, TimeUnit.SECONDS)).as("同步等待必须有上限").isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("实库测试等待被中断", ex); }
    }

    private static void code(Runnable work, int expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(expected));
    }

    private static void mysqlError(Runnable work, int expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(DataAccessException.class, ex -> {
            Throwable cause = ex.getMostSpecificCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(expected);
        });
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager) {
        var result = new TransactionTemplate(manager);
        result.setTimeout(5);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return result;
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int n = 0; n < values.length; n++) statement.setObject(n + 1, values[n]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static Clock fixed(long value) { return Clock.fixed(Instant.ofEpochMilli(value), ZoneOffset.UTC); }

    private static String uuid() { return "a" + UUID.randomUUID().toString().substring(1); }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("JDK须支持SHA-256", ex); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as("请在本次JUnit运行配置设置 %s，密钥不用发给AI", name).isNotBlank();
        return value;
    }
}
