package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeDocumentMapper;
import com.ragqa.mapper.KnowledgeIngestJobMapper;
import com.ragqa.mapper.KnowledgeIngestJobMapper.Stored;
import com.ragqa.mapper.KnowledgeManagementMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** 只恢复安全的本地文字解析；队列持久化不等于发布，不调用任何外部模型。 */
@Service
public class KnowledgeIngestJobService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestJobService.class);
    private final KnowledgeIngestJobMapper jobs;
    private final KnowledgeManagementMapper management;
    private final KnowledgeDocumentMapper documents;
    private final EnterpriseMemberService members;
    private final KnowledgeDocumentService ingestion;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> daemon(r, "knowledge-ingest"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ingest-dispatch"));
    private boolean outageLogged;
    private volatile boolean closed;

    public record Submit(String requestId, Long expectedRevision, String action) { }
    public record Job(String id, Long documentId, Long versionId, long expectedRevision, String action,
            String status, int attempts, long createdMs, long deadlineMs, Long finishedMs, String failureCode, String message) { }

    @Autowired
    public KnowledgeIngestJobService(KnowledgeIngestJobMapper jobs, KnowledgeManagementMapper management,
            KnowledgeDocumentMapper documents, EnterpriseMemberService members, KnowledgeDocumentService ingestion,
            PlatformTransactionManager manager) {
        this(jobs, management, documents, members, ingestion, manager, Clock.systemUTC());
    }

    KnowledgeIngestJobService(KnowledgeIngestJobMapper jobs, KnowledgeManagementMapper management,
            KnowledgeDocumentMapper documents, EnterpriseMemberService members, KnowledgeDocumentService ingestion,
            PlatformTransactionManager manager, Clock clock) {
        this.jobs = jobs; this.management = management; this.documents = documents;
        this.members = members; this.ingestion = ingestion; this.clock = clock;
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    @PostConstruct public void start() { scheduler.scheduleWithFixedDelay(this::dispatch, 1, 1, TimeUnit.SECONDS); }

    public Job submit(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, Submit request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() <= 0
                || !Set.of("PARSE", "REBUILD").contains(Objects.toString(request.action(), ""))) throw invalid();
        uuid(request.requestId()); positive(document); positive(version);
        var scope = members.requireMember(user, enterprise);
        try {
            return tx.execute(status -> {
                if (jobs.lockGate() == null) throw new BusinessException(ResultCode.SERVICE_BUSY, "入库队列未初始化，请联系管理员");
                maintain(scope, space);
                expire();
                Stored old = jobs.find(request.requestId());
                if (old != null) {
                    own(old, scope, space, document, version);
                    if (old.expectedRevision() != request.expectedRevision() || !old.action().equals(request.action())) throw conflict();
                    return view(old);
                }
                var doc = documents.lockDocument(scope, space, document);
                if (doc == null) throw missing();
                var candidate = documents.readVersion(scope, space, document, version, clock.millis());
                if (candidate == null) throw missing();
                if (doc.revision() != request.expectedRevision() || !"DRAFT".equals(candidate.getPublicationStatus())) throw conflict();
                if (jobs.activeVersion(document, version) != 0) throw conflict();
                if (jobs.activeTotal() >= 64 || jobs.activeOwner(enterprise, scope.memberId()) >= 2)
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "入库任务已达上限，请等待已有任务结束");
                long now = clock.millis();
                var job = new Stored(request.requestId(), enterprise, space, document, version, scope.memberId(), scope.userId(),
                        scope.userAuthVersion(), request.expectedRevision(), request.action(), "QUEUED", 0, null, null, now, now + 30_000, null, null);
                if (jobs.insert(job) != 1) throw conflict();
                return view(job);
            });
        } catch (DuplicateKeyException ex) { throw conflict(); }
    }

    public List<Job> history(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version) {
        return tx.execute(status -> {
            var scope = members.requireMember(user, enterprise); maintain(scope, space);
            if (documents.readVersion(scope, space, document, version, clock.millis()) == null) throw missing();
            expire();
            return jobs.history(enterprise, space, scope.memberId(), document, version).stream().map(this::view).toList();
        });
    }

    public Job read(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, String id) {
        uuid(id);
        return tx.execute(status -> {
            var scope = members.requireMember(user, enterprise); maintain(scope, space);
            // 文档删除后仍可核对本人终态，但不返回任何原件内容或存储位置。
            expire(); Stored job = jobs.find(id); own(job, scope, space, document, version);
            return view(job);
        });
    }

    public Job cancel(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, String id) {
        uuid(id);
        return tx.execute(status -> {
            var scope = members.requireMember(user, enterprise); maintain(scope, space);
            expire(); Stored job = jobs.find(id); own(job, scope, space, document, version);
            jobs.cancel(id, clock.millis());
            return view(jobs.find(id));
        });
    }

    /** 每次只读16项候选，节点两执行槽；抢占依靠数据库CAS，不能依赖本机Map互斥。 */
    public void dispatch() {
        if (closed) return;
        try {
            expire();
            for (Stored job : jobs.pending(clock.millis())) {
                if (running.size() >= 2) break;
                if (!running.add(job.id())) continue;
                try { workers.execute(() -> { try { execute(job.id()); } finally { running.remove(job.id()); } }); }
                catch (RejectedExecutionException ex) { running.remove(job.id()); }
            }
            outageLogged = false;
        } catch (RuntimeException ex) {
            if (!outageLogged) LOG.warn("ingest_queue_unavailable: 请核对数据库及20261019_03迁移；不记录SQL、路径或正文");
            outageLogged = true;
        }
    }

    /** 先CAS取得新令牌，解析在事务外；旧执行者即使迟到也不能完成新租约。 */
    void execute(String id) {
        if (closed) return;
        String token = UUID.randomUUID().toString();
        MDC.put("request_id", id);
        try {
            long now = clock.millis();
            if (jobs.claim(id, token, now, now + 15_000) != 1) return;
            Stored job = jobs.find(id);
            var user = new AuthenticatedUserPrincipal(job.userId(), job.authVersion());
            var scope = members.requireMember(user, job.enterpriseId());
            if (scope.memberId() != job.memberId()) throw missing();
            LOG.info("ingest_started: job_id={}, attempt={}", id, job.attempts());
            ingestion.parseWithin(user, job.enterpriseId(), job.spaceId(), job.documentId(), job.versionId(),
                    job.expectedRevision(), "REBUILD".equals(job.action()),
                    () -> { if (closed || Thread.currentThread().isInterrupted() || jobs.owns(id, token, clock.millis()) != 1) throw conflict(); },
                    () -> { if (closed || Thread.currentThread().isInterrupted() || jobs.finish(id, token, "SUCCEEDED", null, clock.millis()) != 1) throw conflict(); });
            LOG.info("ingest_finished: job_id={}, status=SUCCEEDED", id);
        } catch (BusinessException ex) {
            if (closed || Thread.currentThread().isInterrupted()) return; // 留给新节点按原租约恢复，不在停机时另起失败写入。
            fail(id, token, switch (ex.getCode()) {
                case 400 -> "INVALID_TEXT"; case 401, 404 -> "ACCESS_REVOKED";
                case 409 -> "REVISION_CONFLICT"; case 504 -> "PARSE_TIMEOUT";
                default -> "ORIGINAL_UNAVAILABLE";
            });
        } catch (Exception ex) {
            LOG.error("ingest_failed: job_id={}, type={}", id, ex.getClass().getSimpleName());
            if (!closed && !Thread.currentThread().isInterrupted()) fail(id, token, "INTERNAL_ERROR");
        } finally { MDC.remove("request_id"); }
    }

    private void fail(String id, String token, String failure) {
        try {
            int applied = jobs.finish(id, token, "FAILED", failure, clock.millis());
            LOG.info("ingest_finished: job_id={}, status=FAILED, code={}, applied={}", id, failure, applied == 1);
        } catch (DataAccessException ex) {
            LOG.error("ingest_result_unknown: job_id={}；重连后先核对持久状态，租约和总截止仍有效", id);
        }
    }

    private void expire() { jobs.expire(clock.millis()); jobs.exhaust(clock.millis()); }
    private void maintain(EnterpriseMemberContext scope, long space) {
        members.requireCurrent(scope);
        if (management.lockMaintain(scope, space) == null) throw missing();
    }
    private void own(Stored job, EnterpriseMemberContext scope, long space, long document, long version) {
        if (job == null || job.enterpriseId() != scope.enterpriseId() || job.spaceId() != space
                || job.memberId() != scope.memberId() || job.userId() != scope.userId()
                || job.documentId() != document || job.versionId() != version) throw missing();
    }
    private Job view(Stored job) {
        String message = switch (job.status()) {
            case "QUEUED" -> "已受理，等待本机解析；刷新或断网不会重复提交";
            case "RUNNING" -> "正在校验原件、解析并写入片段；最多等待至任务总截止";
            case "SUCCEEDED" -> "片段入库完成，尚未自动发布；请检查后手动发布";
            case "CANCELLED" -> "任务已取消，迟到解析不会覆盖此终态";
            case "TIMED_OUT" -> "任务已超时，未自动重提；请刷新版本后人工重试";
            default -> "入库未完成，请核对失败分类、权限与最新修订后人工重试";
        };
        return new Job(job.id(), job.documentId(), job.versionId(), job.expectedRevision(), job.action(), job.status(),
                job.attempts(), job.createdMs(), job.deadlineMs(), job.finishedMs(), job.failureCode(), message);
    }
    private static void uuid(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw invalid(); }
        catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static void positive(long id) { if (id <= 0) throw invalid(); }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "入库编号、动作或修订号不合法"); }
    private static BusinessException missing() { return new BusinessException(ResultCode.NOT_FOUND, "任务不存在或不可访问"); }
    private static BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "任务或文档已变化，请先核对原请求与最新版本"); }
    private static Thread daemon(Runnable work, String name) { Thread thread = new Thread(work, name); thread.setDaemon(true); return thread; }
    @PreDestroy public void close() { closed = true; scheduler.shutdownNow(); workers.shutdownNow(); }
}
