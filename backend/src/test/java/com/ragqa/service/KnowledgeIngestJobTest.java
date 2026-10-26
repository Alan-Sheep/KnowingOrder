package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeDocuments.Version;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.rag.ingest.Utf8TextParser;
import com.ragqa.service.impl.*;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 隔离H2、真实Mapper与事务；可控时钟和故障注入，不接开发库或模型。 */
class KnowledgeIngestJobTest {
    static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    static final AuthenticatedUserPrincipal READER = new AuthenticatedUserPrincipal(1, 1);
    @TempDir Path root;
    EnterpriseDatabase db;
    KnowledgeDocumentService docs;
    KnowledgeIngestJobService service;
    KnowledgeIngestJobMapper jobs;
    KnowledgeDocumentMapper mapper;
    PrivateKnowledgeFiles files;
    Utf8TextParser parser;
    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission) VALUES(1,401,103,'MAINTAIN')");
        var configuration = db.factory.getConfiguration();
        configuration.addMapper(KnowledgeDocumentMapper.class); configuration.addMapper(KnowledgeManagementMapper.class);
        configuration.addMapper(KnowledgeIngestJobMapper.class);
        var sql = new SqlSessionTemplate(db.factory);
        mapper = spy(sql.getMapper(KnowledgeDocumentMapper.class));
        jobs = spy(sql.getMapper(KnowledgeIngestJobMapper.class));
        files = spy(new PrivateKnowledgeFiles(root.toString())); parser = spy(new Utf8TextParser());
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)), sql.getMapper(EnterpriseDirectoryMapper.class));
        var manager = new DataSourceTransactionManager(db.dataSource);
        docs = new KnowledgeDocumentService(members, new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class)),
                sql.getMapper(KnowledgeManagementMapper.class), mapper, files, parser, manager, clock);
        service = new KnowledgeIngestJobService(jobs, sql.getMapper(KnowledgeManagementMapper.class), mapper, members, docs, manager, clock);
    }
    @AfterEach void close() throws Exception { if (service != null) service.close(); if (db != null) db.close(); }
    Version upload(String text) {
        return docs.upload(ADMIN, 1L, 401L, new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(), null, null, "虚构制度", null, null),
                new MockMultipartFile("file", "虚构.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)));
    }
    long revision(Version v) { return docs.document(ADMIN, 1L, 401L, v.documentId()).revision(); }
    KnowledgeIngestJobService.Job submit(Version v, String action) { return submit(v, UUID.randomUUID().toString(), revision(v), action); }
    KnowledgeIngestJobService.Job submit(Version v, String id, long rev, String action) {
        return service.submit(ADMIN, 1, 401, v.documentId(), v.id(), new KnowledgeIngestJobService.Submit(id, rev, action));
    }
    KnowledgeIngestJobService.Job read(Version v, String id) { return service.read(ADMIN, 1, 401, v.documentId(), v.id(), id); }
    int chunks(Version v) { return db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk WHERE version_id=?", Integer.class, v.id()); }
    void code(Runnable work, int code) { assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code)); }
    KnowledgeIngestJobService freshService(KnowledgeDocumentService ingestion) {
        var sql = new SqlSessionTemplate(db.factory);
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)), sql.getMapper(EnterpriseDirectoryMapper.class));
        return new KnowledgeIngestJobService(jobs, sql.getMapper(KnowledgeManagementMapper.class), mapper, members, ingestion,
                new DataSourceTransactionManager(db.dataSource), clock);
    }

    @Test void completeIdempotentTaskAndReadyReuseNeverDuplicateChunks() {
        var v = upload("审批制度\n" + "每日三十元。".repeat(500));
        var job = submit(v, "PARSE"); service.execute(job.id());
        assertThat(read(v, job.id()).status()).isEqualTo("SUCCEEDED");
        int count = chunks(v); assertThat(count).isGreaterThan(1); assertThat(revision(v)).isEqualTo(2);
        assertThat(submit(v, job.id(), 1, "PARSE").status()).isEqualTo("SUCCEEDED");
        var reuse = submit(v, "PARSE"); service.execute(reuse.id());
        assertThat(read(v, reuse.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(chunks(v)).isEqualTo(count); assertThat(revision(v)).isEqualTo(2);
        assertThat(docs.version(ADMIN, 1L, 401L, v.documentId(), v.id()).publicationStatus()).isEqualTo("DRAFT");
    }
    @Test void sameUuidChangedActionOrRevisionConflicts() {
        var v = upload("正文"); var job = submit(v, "PARSE");
        code(() -> submit(v, job.id(), 1, "REBUILD"), 409);
        code(() -> submit(v, job.id(), 2, "PARSE"), 409);
        assertThat(jobs.activeTotal()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"", "bad", "SELECT 1", "00000000-0000-4000-8000-00000000000A"})
    void invalidRequestIdNeverEntersQueue(String id) { var v = upload("正文"); code(() -> submit(v, id, 1, "PARSE"), 400); assertThat(jobs.activeTotal()).isZero(); }
    @Test void nullUnknownActionAndInvalidRevisionAreRejected() {
        var v = upload("正文");
        code(() -> service.submit(ADMIN,1,401,v.documentId(),v.id(),null),400);
        code(() -> submit(v, UUID.randomUUID().toString(), 0, "PARSE"),400);
        code(() -> submit(v, UUID.randomUUID().toString(), 1, "DELETE"),400);
    }
    @Test void readOnlyCrossEnterpriseOtherOwnerAndAuthRevocationCannotReadTasks() {
        var v = upload("正文"); var job = submit(v, "PARSE");
        code(() -> service.read(READER,1,401,v.documentId(),v.id(),job.id()),404);
        code(() -> service.read(ADMIN,2,401,v.documentId(),v.id(),job.id()),404);
        db.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE member_id=101 AND knowledge_space_id=401");
        code(() -> service.read(READER,1,401,v.documentId(),v.id(),job.id()),404);
        code(() -> service.cancel(READER,1,401,v.documentId(),v.id(),job.id()),404);
        db.jdbc.update("UPDATE sys_user SET auth_version=2 WHERE id=3");
        code(() -> read(v,job.id()),401);
    }
    @Test void activeVersionAndOwnerCapacityAreAtomicAndCancellationReleasesCapacity() {
        var a = upload("甲"); var b = upload("乙"); var c = upload("丙");
        var first = submit(a,"PARSE"); code(() -> submit(a,"PARSE"),409);
        submit(b,"PARSE"); code(() -> submit(c,"PARSE"),503);
        service.cancel(ADMIN,1,401,a.documentId(),a.id(),first.id());
        assertThat(submit(c,"PARSE").status()).isEqualTo("QUEUED");
    }
    @Test void queueGlobalCapacityAndMissingGateFailClosed() {
        var v = upload("正文"); var template = submit(v,"PARSE");
        for (int i=0;i<63;i++) db.jdbc.update("""
                INSERT INTO rag_ingest_job(id,enterprise_id,space_id,document_id,version_id,member_id,user_id,auth_version,expected_revision,action,created_ms,deadline_ms)
                SELECT ?,enterprise_id,space_id,document_id,version_id,?,user_id,auth_version,expected_revision,action,created_ms,deadline_ms FROM rag_ingest_job WHERE id=?
                """,UUID.randomUUID().toString(),900+i,template.id());
        var other = upload("新文档"); code(() -> submit(other,"PARSE"),503);
        db.jdbc.update("DELETE FROM rag_ingest_gate WHERE id=1");
        code(() -> submit(other,"PARSE"),503);
    }
    @Test void concurrentSameUuidCreatesOneDurableTask() throws Exception {
        var v = upload("正文"); String id=UUID.randomUUID().toString(); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> submit(v,id,1,"PARSE")); var b=pool.submit(() -> submit(v,id,1,"PARSE"));
            assertThat(a.get(5,TimeUnit.SECONDS)).isEqualTo(b.get(5,TimeUnit.SECONDS));
            assertThat(jobs.activeTotal()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidTextIsOneFailureAndManualRetryUsesNewRevision() {
        var v=upload("\u0000"); var job=submit(v,"PARSE"); service.execute(job.id());
        assertThat(read(v,job.id()).failureCode()).isEqualTo("INVALID_TEXT");
        assertThat(read(v,job.id()).attempts()).isEqualTo(1); assertThat(chunks(v)).isZero(); assertThat(revision(v)).isEqualTo(2);
        service.execute(job.id()); assertThat(read(v,job.id()).attempts()).isEqualTo(1);
        var retry=submit(v,"PARSE"); service.execute(retry.id()); assertThat(revision(v)).isEqualTo(3);
    }
    @Test void partialBatchFailureRollsBackAllChunksAndVersion() {
        var v=upload("正文".repeat(60_000)); var job=submit(v,"PARSE");
        doAnswer(call -> { if (((List<?>)call.getArgument(2)).size()<100) throw new IllegalStateException("合成写入故障"); return call.callRealMethod(); })
                .when(mapper).insertChunks(anyLong(),anyLong(),anyList());
        service.execute(job.id());
        assertThat(read(v,job.id()).status()).isEqualTo("FAILED"); assertThat(chunks(v)).isZero(); assertThat(revision(v)).isEqualTo(1);
        assertThat(docs.version(ADMIN,1L,401L,v.documentId(),v.id()).parseStatus()).isEqualTo("PENDING");
    }
    @Test void failedSuccessCasRollsBackChunksAndVersionTogether() {
        var v=upload("正文"); var job=submit(v,"PARSE");
        doReturn(0).when(jobs).finish(eq(job.id()),anyString(),eq("SUCCEEDED"),isNull(),anyLong());
        service.execute(job.id());
        assertThat(chunks(v)).isZero(); assertThat(revision(v)).isEqualTo(1);
        assertThat(read(v,job.id()).status()).isEqualTo("FAILED");
    }
    @Test void queuedCancelIsTerminalAndCannotBeExecuted() {
        var v=upload("正文"); var job=submit(v,"PARSE");
        service.cancel(ADMIN,1,401,v.documentId(),v.id(),job.id()); service.execute(job.id());
        assertThat(read(v,job.id()).status()).isEqualTo("CANCELLED"); assertThat(chunks(v)).isZero(); assertThat(jobs.activeTotal()).isZero();
    }
    @Test void cancelDuringParsingRejectsLateCommit() throws Exception { blockedCompletion(false); }
    @Test void timeoutDuringParsingRejectsLateCommit() throws Exception { blockedCompletion(true); }
    void blockedCompletion(boolean timeout) throws Exception {
        var v=upload("正文"); var job=submit(v,"PARSE"); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); }).when(parser).parse(any());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(() -> service.execute(job.id())); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            if (timeout) { now.addAndGet(31_000); assertThat(read(v,job.id()).status()).isEqualTo("TIMED_OUT"); }
            else service.cancel(ADMIN,1,401,v.documentId(),v.id(),job.id());
            release.countDown(); result.get(5,TimeUnit.SECONDS);
            assertThat(read(v,job.id()).status()).isEqualTo(timeout?"TIMED_OUT":"CANCELLED");
            assertThat(chunks(v)).isZero(); assertThat(revision(v)).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void expiredLeaseCanRecoverOnceAndOldTokenCannotFinish() {
        var v=upload("正文"); var job=submit(v,"PARSE"); String old=UUID.randomUUID().toString();
        assertThat(jobs.claim(job.id(),old,now.get(),now.get()+15_000)).isEqualTo(1);
        now.addAndGet(16_000); service.close(); service = freshService(docs); service.execute(job.id());
        assertThat(read(v,job.id()).status()).isEqualTo("SUCCEEDED"); assertThat(read(v,job.id()).attempts()).isEqualTo(2);
        assertThat(jobs.finish(job.id(),old,"FAILED","INTERNAL_ERROR",now.get())).isZero(); assertThat(chunks(v)).isEqualTo(1);
    }
    @Test void twoExpiredLeasesExhaustRecoveryBeforeTotalDeadline() {
        var v=upload("正文"); var job=submit(v,"PARSE");
        jobs.claim(job.id(),UUID.randomUUID().toString(),now.get(),now.get()+5_000); now.addAndGet(6_000);
        jobs.claim(job.id(),UUID.randomUUID().toString(),now.get(),now.get()+5_000); now.addAndGet(6_000);
        assertThat(read(v,job.id()).failureCode()).isEqualTo("RECOVERY_EXHAUSTED");
        service.execute(job.id()); assertThat(read(v,job.id()).attempts()).isEqualTo(2); assertThat(chunks(v)).isZero();
    }
    @Test void queuedTaskExpiresWithoutExtendingOriginalDeadline() {
        var v=upload("正文"); var job=submit(v,"PARSE"); now.addAndGet(30_000);
        service.execute(job.id()); assertThat(read(v,job.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(read(v,job.id()).deadlineMs()).isEqualTo(job.deadlineMs()); assertThat(chunks(v)).isZero();
    }
    @Test void revisionChangeAndPermissionRemovalBeforeExecutionFailWithoutChunks() {
        var v=upload("正文"); var job=submit(v,"PARSE");
        db.jdbc.update("UPDATE rag_document SET revision=2 WHERE id=?",v.documentId()); service.execute(job.id());
        assertThat(read(v,job.id()).failureCode()).isEqualTo("REVISION_CONFLICT"); assertThat(chunks(v)).isZero();
        var second=submit(v,"PARSE"); db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103 AND knowledge_space_id=401");
        service.execute(second.id()); assertThat(jobs.find(second.id()).failureCode()).isEqualTo("ACCESS_REVOKED");
        code(() -> read(v,second.id()),404);
    }
    @Test void permissionRevocationDuringOriginalReadStopsCommit() {
        var v=upload("正文"); var job=submit(v,"PARSE");
        doAnswer(call -> { byte[] bytes=(byte[])call.callRealMethod(); db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE member_id=103 AND knowledge_space_id=401"); return bytes; })
                .when(files).read(anyString(),anyLong(),anyString());
        service.execute(job.id()); assertThat(jobs.find(job.id()).failureCode()).isEqualTo("ACCESS_REVOKED"); assertThat(chunks(v)).isZero();
    }
    @Test void draftRebuildIsAtomicAndPublishedVersionCannotBeRebuilt() {
        var v=upload("正文".repeat(800)); var job=submit(v,"PARSE"); service.execute(job.id()); int count=chunks(v);
        var rebuild=submit(v,"REBUILD"); service.execute(rebuild.id());
        assertThat(read(v,rebuild.id()).status()).isEqualTo("SUCCEEDED"); assertThat(chunks(v)).isEqualTo(count); assertThat(revision(v)).isEqualTo(3);
        docs.lifecycle(ADMIN,1L,401L,v.documentId(),v.id(),revision(v),"PUBLISH");
        code(() -> submit(v,"REBUILD"),409); assertThat(chunks(v)).isEqualTo(count);
    }
    @Test void failedDraftRebuildPreservesPreviouslyReadyChunks() {
        var v=upload("正文"); var job=submit(v,"PARSE"); service.execute(job.id());
        var rebuild=submit(v,"REBUILD"); doThrow(new BusinessException(com.ragqa.common.ResultCode.PARAM_ERROR,"合成失败")).when(parser).parse(any()); service.execute(rebuild.id());
        assertThat(read(v,rebuild.id()).status()).isEqualTo("FAILED"); assertThat(chunks(v)).isEqualTo(1); assertThat(revision(v)).isEqualTo(2);
        assertThat(docs.version(ADMIN,1L,401L,v.documentId(),v.id()).parseStatus()).isEqualTo("READY");
    }
    @Test void historyIsOwnerScopedAndReadDoesNotExposeLeaseOrStorage() throws Exception {
        var v=upload("正文"); var job=submit(v,"PARSE");
        assertThat(service.history(ADMIN,1,401,v.documentId(),v.id())).hasSize(1);
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(read(v,job.id()));
        assertThat(json).doesNotContain("leaseToken","storageKey","authVersion","userId","原件路径");
        code(() -> service.read(ADMIN,1,401,v.documentId()+1,v.id(),job.id()),404);
    }

    @Test void staleWorkerCannotOverwriteSecondLeaseEvenWhenParsingReturnsLate() throws Exception {
        var v=upload("正文"); var job=submit(v,"PARSE");
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> { if(calls.incrementAndGet()==1) { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); } return call.callRealMethod(); }).when(parser).parse(any());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var old=pool.submit(() -> service.execute(job.id())); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            now.addAndGet(16_000); service.execute(job.id());
            release.countDown(); old.get(5,TimeUnit.SECONDS);
            assertThat(read(v,job.id()).status()).isEqualTo("SUCCEEDED"); assertThat(read(v,job.id()).attempts()).isEqualTo(2);
            assertThat(chunks(v)).isEqualTo(1); assertThat(revision(v)).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void alreadyCommittedSuccessSurvivesLostWorkerAcknowledgement() {
        var v=upload("正文"); var job=submit(v,"PARSE"); var failing=spy(docs);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("合成提交后进程回执丢失"); }).when(failing)
                .parseWithin(any(),anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyBoolean(),any(),any());
        service.close(); service=freshService(failing); service.execute(job.id());
        assertThat(read(v,job.id()).status()).isEqualTo("SUCCEEDED"); assertThat(chunks(v)).isEqualTo(1); assertThat(revision(v)).isEqualTo(2);
    }
    @Test void shutdownDuringParsingLeavesLeaseForBoundedRecovery() throws Exception {
        var v=upload("正文"); var job=submit(v,"PARSE"); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); }).when(parser).parse(any());
        var oldService=service; var pool=Executors.newSingleThreadExecutor();
        try {
            var work=pool.submit(() -> oldService.execute(job.id())); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            oldService.close(); release.countDown(); work.get(5,TimeUnit.SECONDS);
            assertThat(jobs.find(job.id()).status()).isEqualTo("RUNNING"); assertThat(chunks(v)).isZero();
            now.addAndGet(16_000); service=freshService(docs); service.execute(job.id());
            assertThat(read(v,job.id()).status()).isEqualTo("SUCCEEDED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
