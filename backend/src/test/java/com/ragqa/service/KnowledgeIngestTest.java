package com.ragqa.service;

import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeDocuments;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.ingest.Utf8TextParser;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.service.impl.KnowledgeSpaceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实Mapper、Spring事务和本地私有文件，数据库为隔离H2，不替代MySQL锁与部署验收。 */
class KnowledgeIngestTest {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private static final AuthenticatedUserPrincipal READER = new AuthenticatedUserPrincipal(1, 1);
    private static final long NOW = 1_800_000_000_000L;
    @TempDir Path root;
    private EnterpriseDatabase db;
    private KnowledgeDocumentService service;
    private KnowledgeManagementService management;
    private long spaceId;

    @BeforeEach
    void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        var config = db.factory.getConfiguration();
        config.addMapper(KnowledgeSpaceMapper.class);
        config.addMapper(KnowledgeManagementMapper.class);
        config.addMapper(KnowledgeDocumentMapper.class);
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        var sql = new SqlSessionTemplate(db.factory);
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        var manager = new DataSourceTransactionManager(db.dataSource);
        var create = transactional(new KnowledgeSpaceServiceImpl(members, sql.getMapper(EnterpriseDirectoryMapper.class),
                sql.getMapper(KnowledgeSpaceMapper.class)), manager);
        spaceId = create.createSpace(ADMIN, 1L, "知识入库测试").id();
        management = transactional(new KnowledgeManagementService(members, sql.getMapper(KnowledgeManagementMapper.class)), manager);
        service = new KnowledgeDocumentService(members, new EnterpriseResourceServiceImpl(members,
                sql.getMapper(EnterpriseResourceMapper.class)), sql.getMapper(KnowledgeManagementMapper.class),
                sql.getMapper(KnowledgeDocumentMapper.class), new PrivateKnowledgeFiles(root.toString()),
                new Utf8TextParser(), manager, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @AfterEach
    void close() throws Exception { if (db != null) db.close(); }

    @Test
    void uploadParsePublishReadWithdrawAndDeletePreserveVersionBoundaries() {
        grantReader();
        var first = upload("审批细则\n" + "须经理确认。".repeat(250));
        long doc = first.documentId();
        assertThat(first.parseStatus()).isEqualTo("PENDING");
        assertThat(service.list(READER, 1L, spaceId, 1, 20).getTotal()).isZero();
        code(() -> service.original(READER, 1L, spaceId, doc, first.id()), 404);
        var ready = service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc));
        assertThat(ready.chunkCount()).isGreaterThan(1);
        assertThat(service.parse(ADMIN, 1L, spaceId, doc, first.id(), 1L)).isEqualTo(ready);
        var chunks = service.chunks(ADMIN, 1L, spaceId, doc, first.id(), 1, 1);
        assertThat(chunks.getRecords()).hasSize(1);
        assertThat(chunks.getTotal()).isEqualTo(ready.chunkCount());
        service.lifecycle(ADMIN, 1L, spaceId, doc, first.id(), revision(doc), "PUBLISH");
        assertThat(service.list(READER, 1L, spaceId, 1, 20).getRecords()).extracting(KnowledgeDocuments.Document::id).containsExactly(doc);
        assertThat(service.original(READER, 1L, spaceId, doc, first.id()).bytes()).isNotEmpty();
        service.lifecycle(ADMIN, 1L, spaceId, doc, first.id(), revision(doc), "WITHDRAW");
        code(() -> service.original(READER, 1L, spaceId, doc, first.id()), 404);
        code(() -> service.chunks(READER, 1L, spaceId, doc, first.id(), 1, 20), 404);
        assertThat(service.versions(READER, 1L, spaceId, doc, 1, 20).getTotal()).isZero();
        service.lifecycle(ADMIN, 1L, spaceId, doc, null, revision(doc), "DELETE_DOCUMENT");
        code(() -> service.version(ADMIN, 1L, spaceId, doc, first.id()), 404);
        assertThat(service.list(ADMIN, 1L, spaceId, 1, 20).getTotal()).isZero();
    }

    @Test
    void identicalUploadRequestIsIdempotentButChangedContentOrParametersIsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        var metadata = new KnowledgeDocumentService.Upload(key, null, null, "制度", null, null);
        var first = service.upload(ADMIN, 1L, spaceId, metadata, file("制度.md", "正文"));
        assertThat(service.upload(ADMIN, 1L, spaceId, metadata, file("制度.md", "正文"))).isEqualTo(first);
        code(() -> service.upload(ADMIN, 1L, spaceId, metadata, file("制度.md", "不同内容")), 409);
        code(() -> service.upload(ADMIN, 1L, spaceId,
                new KnowledgeDocumentService.Upload(key, null, null, "其他标题", null, null), file("制度.md", "正文")), 409);
        assertThat(count("rag_document_version")).isEqualTo(1);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(1); }
    }

    @Test
    void concurrentIdenticalRequestsCommitOneVersionAndOneOriginal() throws Exception {
        String key = UUID.randomUUID().toString();
        var metadata = new KnowledgeDocumentService.Upload(key, null, null, "并发", null, null);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.of(
                    () -> service.upload(ADMIN, 1L, spaceId, metadata, file("制度.txt", "同一内容")),
                    () -> service.upload(ADMIN, 1L, spaceId, metadata, file("制度.txt", "同一内容"))), 15, TimeUnit.SECONDS);
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
            assertThat(count("rag_document_version")).isEqualTo(1);
            try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(1); }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void newDraftFailureNeverReplacesExistingPublishedVersion() {
        grantReader();
        var first = upload("已生效版本");
        long doc = first.documentId();
        service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc));
        service.lifecycle(ADMIN, 1L, spaceId, doc, first.id(), revision(doc), "PUBLISH");
        var second = service.upload(ADMIN, 1L, spaceId, new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(),
                doc, revision(doc), "制度", null, null), new MockMultipartFile("file", "新版.txt", "text/plain", new byte[] {(byte) 0xff}));
        code(() -> service.parse(ADMIN, 1L, spaceId, doc, second.id(), revision(doc)), 400);
        assertThat(service.version(ADMIN, 1L, spaceId, doc, second.id()).failureCode()).isEqualTo("INVALID_TEXT");
        assertThat(service.version(READER, 1L, spaceId, doc, first.id()).publicationStatus()).isEqualTo("PUBLISHED");
        code(() -> service.version(READER, 1L, spaceId, doc, second.id()), 404);
        code(() -> service.lifecycle(ADMIN, 1L, spaceId, doc, second.id(), revision(doc), "PUBLISH"), 409);
    }

    @Test
    void replacementPublishesAtomicallyAndHidesOldVersionWithoutFallback() {
        grantReader();
        var first = upload("原版本");
        long doc = first.documentId();
        service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc));
        service.lifecycle(ADMIN, 1L, spaceId, doc, first.id(), revision(doc), "PUBLISH");
        var second = service.upload(ADMIN, 1L, spaceId, new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(),
                doc, revision(doc), "制度", null, null), file("新版.md", "新版本"));
        service.parse(ADMIN, 1L, spaceId, doc, second.id(), revision(doc));
        service.lifecycle(ADMIN, 1L, spaceId, doc, second.id(), revision(doc), "PUBLISH");
        assertThat(service.versions(READER, 1L, spaceId, doc, 1, 20).getRecords()).extracting(KnowledgeDocuments.Version::id).containsExactly(second.id());
        code(() -> service.original(READER, 1L, spaceId, doc, first.id()), 404);
        service.lifecycle(ADMIN, 1L, spaceId, doc, second.id(), revision(doc), "WITHDRAW");
        assertThat(service.list(READER, 1L, spaceId, 1, 20).getTotal()).isZero();
    }

    @Test
    void failedVersionInsertRollsBackDocumentAndDiscardsOnlyItsNewOriginal() throws Exception {
        db.jdbc.execute("ALTER TABLE rag_document_version ADD CONSTRAINT fail_new_version CHECK (version_no > 1)");
        assertThatThrownBy(() -> upload("不能提交")).isInstanceOf(RuntimeException.class);
        assertThat(count("rag_document_version")).isZero();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_document WHERE knowledge_space_id=?", Integer.class, spaceId)).isZero();
        try (var paths = Files.list(root)) { assertThat(paths.count()).isZero(); }
    }

    @Test
    void failedChunkBatchCommitsNoHalfFinishedChunksAndCanBeRetried() {
        var first = upload("很长的正文。".repeat(400));
        long doc = first.documentId();
        db.jdbc.execute("ALTER TABLE rag_chunk ADD CONSTRAINT fail_chunks CHECK(chunk_index < 1)");
        assertThatThrownBy(() -> service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc))).isInstanceOf(RuntimeException.class);
        assertThat(count("rag_chunk")).isZero();
        assertThat(service.version(ADMIN, 1L, spaceId, doc, first.id()).parseStatus()).isEqualTo("PENDING");
        assertThat(revision(doc)).isEqualTo(1);
        db.jdbc.execute("ALTER TABLE rag_chunk DROP CONSTRAINT fail_chunks");
        assertThat(service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc)).chunkCount()).isGreaterThan(1);
    }

    @Test
    void failedPublicationPointerRollsBackVersionState() {
        var first = upload("制度正文");
        long doc = first.documentId();
        service.parse(ADMIN, 1L, spaceId, doc, first.id(), revision(doc));
        db.jdbc.execute("ALTER TABLE rag_document ADD CONSTRAINT fail_pointer CHECK(published_version_id IS NULL)");
        assertThatThrownBy(() -> service.lifecycle(ADMIN, 1L, spaceId, doc, first.id(), revision(doc), "PUBLISH"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.version(ADMIN, 1L, spaceId, doc, first.id()).publicationStatus()).isEqualTo("DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../制度.txt", "C:\\制度.txt", "制度.doc", "制度.exe", "制度.png", "制度.txt\n"})
    void rejectsUnsafeNamesAndUnimplementedFormats(String name) {
        code(() -> service.upload(ADMIN, 1L, spaceId, metadata(), file(name, "正文")), 400);
        assertThat(count("rag_document_version")).isZero();
    }

    @Test
    void emptyOversizedInvalidDateAndRequestKeysAreRejected() {
        code(() -> service.upload(ADMIN, 1L, spaceId, metadata(), file("空.txt", "")), 400);
        code(() -> service.upload(ADMIN, 1L, spaceId, metadata(), new MockMultipartFile("file", "大.txt", "text/plain", new byte[1024 * 1024 + 1])), 413);
        code(() -> service.upload(ADMIN, 1L, spaceId, new KnowledgeDocumentService.Upload("bad", null, null, "标题", null, null), file("文.txt", "文")), 400);
        code(() -> service.upload(ADMIN, 1L, spaceId, new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(), null, null, "标题", NOW, NOW), file("文.txt", "文")), 400);
        code(() -> service.upload(null, 1L, spaceId, metadata(), file("文.txt", "文")), 401);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 501, Long.MAX_VALUE})
    void invalidPaginationIsBounded(long size) { code(() -> service.list(ADMIN, 1L, spaceId, 1, size), 400); }

    @Test
    void readOnlyGrantAndCrossEnterpriseNeverAllowUploadOrLifecycle() {
        grantReader();
        var first = upload("只读资料");
        code(() -> service.upload(READER, 1L, spaceId, metadata(), file("文.txt", "文")), 404);
        code(() -> service.parse(READER, 1L, spaceId, first.documentId(), first.id(), 1L), 404);
        code(() -> service.list(READER, 2L, spaceId, 1, 20), 404);
        code(() -> service.version(ADMIN, 1L, 401L, first.documentId(), first.id()), 404);
        code(() -> service.lifecycle(READER, 1L, spaceId, first.documentId(), first.id(), 1L, "PUBLISH"), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE sys_user SET auth_version=2 WHERE id=3",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=103", "UPDATE rag_knowledge_space SET status=1 WHERE id=%s",
            "UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=%s"})
    void revocationBlocksFurtherReadAndWrite(String fixedSql) {
        var first = upload("私有资料");
        db.jdbc.update(fixedSql.contains("%s") ? fixedSql.formatted(spaceId) : fixedSql);
        assertThatThrownBy(() -> service.original(ADMIN, 1L, spaceId, first.documentId(), first.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(ADMIN, 1L, spaceId, metadata(), file("文.txt", "文"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredAndFutureVersionsCannotPublishAndStaleRevisionCannotOverride() {
        for (long[] interval : new long[][] { {NOW - 20, NOW}, {NOW + 1, NOW + 20} }) {
            var version = service.upload(ADMIN, 1L, spaceId, new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(),
                    null, null, "日期边界", interval[0], interval[1]), file("制度.txt", "正文"));
            service.parse(ADMIN, 1L, spaceId, version.documentId(), version.id(), 1L);
            code(() -> service.lifecycle(ADMIN, 1L, spaceId, version.documentId(), version.id(), revision(version.documentId()), "PUBLISH"), 409);
        }
        var version = upload("并发版本");
        service.parse(ADMIN, 1L, spaceId, version.documentId(), version.id(), 1L);
        code(() -> service.lifecycle(ADMIN, 1L, spaceId, version.documentId(), version.id(), 1L, "PUBLISH"), 409);
    }

    @Test
    void spaceManagementChecksVersionTargetsAndRevocationImmediately() {
        assertThat(management.rename(ADMIN, 1L, spaceId, 1L, "新名称").permissionVersion()).isEqualTo(2);
        code(() -> management.rename(ADMIN, 1L, spaceId, 1L, "过时覆盖"), 409);
        code(() -> management.rename(ADMIN, 1L, spaceId, null, "缺版本"), 400);
        code(() -> management.changeGrant(ADMIN, 1L, spaceId, 2L, "MEMBER", 201L, "READ", false), 404);
        management.changeGrant(ADMIN, 1L, spaceId, 2L, "MEMBER", 101L, "READ", false);
        code(() -> management.grants(READER, 1L, spaceId), 404);
        code(() -> management.rename(READER, 1L, spaceId, 3L, "只读不能改"), 404);
        management.changeGrant(ADMIN, 1L, spaceId, 3L, "MEMBER", 101L, null, true);
        code(() -> service.list(READER, 1L, spaceId, 1, 20), 404);
        code(() -> management.changeGrant(ADMIN, 1L, spaceId, 4L, "MEMBER", 103L, null, true), 400);
    }

    @Test
    void grantFailureDoesNotAdvancePermissionVersion() {
        db.jdbc.execute("ALTER TABLE rag_knowledge_grant ADD CONSTRAINT fail_grant CHECK(member_id <> 101) NOCHECK");
        assertThatThrownBy(() -> management.changeGrant(ADMIN, 1L, spaceId, 1L, "MEMBER", 101L, "READ", false)).isInstanceOf(RuntimeException.class);
        assertThat(db.jdbc.queryForObject("SELECT permission_version FROM rag_knowledge_space WHERE id=?", Long.class, spaceId)).isEqualTo(1);
        assertThat(management.grants(ADMIN, 1L, spaceId)).hasSize(1);
    }

    @Test
    void ownerMustBeSameEnterpriseEffectiveMaintainerAndHaveCurrentRevision() {
        var version = upload("有负责人的文档");
        long doc = version.documentId();
        assertThat(service.document(ADMIN, 1L, spaceId, doc).ownerMemberId()).isEqualTo(103);
        code(() -> service.assignOwner(ADMIN, 1L, spaceId, doc, revision(doc), 101L), 400);
        code(() -> service.assignOwner(ADMIN, 1L, spaceId, doc, revision(doc), 201L), 400);
        management.changeGrant(ADMIN, 1L, spaceId, 1L, "MEMBER", 101L, "MAINTAIN", false);
        assertThat(service.assignOwner(ADMIN, 1L, spaceId, doc, revision(doc), 101L).ownerMemberId()).isEqualTo(101);
        code(() -> service.assignOwner(ADMIN, 1L, spaceId, doc, 1L, 103L), 409);
    }

    @Test
    void expiryAtExactBoundaryAndMissingChunksBlockUse() {
        grantReader();
        var version = upload("有效期与片段完整性");
        long doc = version.documentId();
        service.parse(ADMIN, 1L, spaceId, doc, version.id(), revision(doc));
        service.lifecycle(ADMIN, 1L, spaceId, doc, version.id(), revision(doc), "PUBLISH");
        db.jdbc.update("UPDATE rag_document_version SET valid_until_ms=? WHERE id=?", NOW, version.id());
        code(() -> service.document(READER, 1L, spaceId, doc), 404);
        code(() -> service.original(READER, 1L, spaceId, doc, version.id()), 404);
        db.jdbc.update("UPDATE rag_document_version SET valid_until_ms=NULL WHERE id=?", version.id());
        service.lifecycle(ADMIN, 1L, spaceId, doc, version.id(), revision(doc), "WITHDRAW");
        db.jdbc.update("DELETE FROM rag_chunk WHERE version_id=?", version.id());
        code(() -> service.lifecycle(ADMIN, 1L, spaceId, doc, version.id(), revision(doc), "PUBLISH"), 409);
    }

    @Test
    void legacyMetadataEndpointCannotBypassManagedDocumentLifecycle() {
        grantReader();
        var version = upload("不得通过旧入口读取草稿标题");
        var sql = new SqlSessionTemplate(db.factory);
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        var resources = new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class));
        code(() -> resources.requireDocument(members.requireMember(READER, 1L), version.documentId()), 404);
    }

    @Test
    void revocationDuringOriginalReadIsCheckedAgainBeforeReturn() {
        var version = upload("读取期间撤权");
        var files = spy(new PrivateKnowledgeFiles(root.toString()));
        doAnswer(call -> {
            byte[] bytes = (byte[]) call.callRealMethod();
            db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=?", spaceId);
            return bytes;
        }).when(files).read(anyString(), anyLong(), anyString());
        code(() -> using(files, new Utf8TextParser()).original(ADMIN, 1L, spaceId, version.documentId(), version.id()), 404);
    }

    @Test
    void saturatedOriginalReadsRejectImmediatelyAndReleaseSlots() throws Exception {
        var version = upload("饱和读取");
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var files = spy(new PrivateKnowledgeFiles(root.toString()));
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(files).read(anyString(), anyLong(), anyString());
        var constrained = using(files, new Utf8TextParser());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<KnowledgeDocumentService.Original>>();
            for (int i = 0; i < 4; i++) jobs.add(executor.submit(() -> constrained.original(ADMIN, 1L, spaceId, version.documentId(), version.id())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            code(() -> constrained.original(ADMIN, 1L, spaceId, version.documentId(), version.id()), 503);
            release.countDown();
            for (var job : jobs) assertThat(job.get(10, TimeUnit.SECONDS).bytes()).isNotEmpty();
            assertThat(constrained.original(ADMIN, 1L, spaceId, version.documentId(), version.id()).bytes()).isNotEmpty();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void concurrentParsersCommitOneSetOfChunks() throws Exception {
        var version = upload("并发解析正文。".repeat(250));
        var entered = new CountDownLatch(2);
        var parser = spy(new Utf8TextParser());
        doAnswer(call -> { entered.countDown(); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); })
                .when(parser).parse(any(byte[].class));
        var concurrent = using(new PrivateKnowledgeFiles(root.toString()), parser);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var jobs = executor.invokeAll(List.of(
                    () -> concurrent.parse(ADMIN, 1L, spaceId, version.documentId(), version.id(), 1L),
                    () -> concurrent.parse(ADMIN, 1L, spaceId, version.documentId(), version.id(), 1L)), 15, TimeUnit.SECONDS);
            assertThat(jobs.get(0).get()).isEqualTo(jobs.get(1).get());
            assertThat(count("rag_chunk")).isEqualTo(service.version(ADMIN, 1L, spaceId, version.documentId(), version.id()).chunkCount());
            assertThat(revision(version.documentId())).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    private KnowledgeDocumentService using(PrivateKnowledgeFiles files, Utf8TextParser parser) {
        var sql = new SqlSessionTemplate(db.factory);
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        return new KnowledgeDocumentService(members, new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class)),
                sql.getMapper(KnowledgeManagementMapper.class), sql.getMapper(KnowledgeDocumentMapper.class), files, parser,
                new DataSourceTransactionManager(db.dataSource), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private KnowledgeDocuments.Version upload(String content) { return service.upload(ADMIN, 1L, spaceId, metadata(), file("制度.md", content)); }
    private KnowledgeDocumentService.Upload metadata() { return new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(), null, null, "制度", null, null); }
    private MockMultipartFile file(String name, String content) { return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)); }
    private long revision(long doc) { return db.jdbc.queryForObject("SELECT revision FROM rag_document WHERE id=?", Long.class, doc); }
    private int count(String table) { return db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void grantReader() { management.changeGrant(ADMIN, 1L, spaceId, 1L, "MEMBER", 101L, "READ", false); }
    private void code(Runnable work, int expected) { assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected)); }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target, DataSourceTransactionManager manager) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return (T) factory.getProxy();
    }
}
