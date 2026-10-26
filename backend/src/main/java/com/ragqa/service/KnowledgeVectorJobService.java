package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeDocumentMapper;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.LocalEmbeddingClient.ModelSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 仅授权受理与本人状态核对；无调度、向量写入或READY发布，不自动重放失败任务。 */
@Service
public final class KnowledgeVectorJobService {
    private static final int GLOBAL_LIMIT = 64;
    private static final int MEMBER_LIMIT = 2;
    private static final long JOB_LIMIT_MS = 120_000;
    private static final Duration ADMISSION_LIMIT = Duration.ofSeconds(15);
    private static final Set<String> FAILURES = Set.of("ACCESS_REVOKED", "REVISION_CONFLICT", "MODEL_CHANGED",
            "MANIFEST_MISMATCH", "WRITE_UNCONFIRMED", "VECTOR_UNAVAILABLE", "INTERNAL_ERROR", "TOTAL_TIMEOUT", "LEASE_LOST");
    private final KnowledgeVectorJobMapper jobs;
    private final KnowledgeManagementMapper management;
    private final KnowledgeDocumentMapper documents;
    private final EnterpriseMemberService members;
    private final LocalEmbeddingClient embedding;
    private final boolean enabled;
    private final TransactionTemplate tx;
    private final Clock clock;

    public record Submit(String requestId, Long expectedRevision) { }
    /** 公开状态不返回代次、模型、摘要、租约、账号认证版本或原件位置。 */
    public record Job(String id, Long documentId, Long versionId, long expectedRevision, String status,
            int expectedChunks, int verifiedChunks, long createdMs, long deadlineMs, Long finishedMs,
            String failureCode, String message) { }
    private record Source(String sha256, int chunks) { }

    @Autowired
    public KnowledgeVectorJobService(KnowledgeVectorJobMapper jobs, KnowledgeManagementMapper management,
            KnowledgeDocumentMapper documents, EnterpriseMemberService members, LocalEmbeddingClient embedding,
            PlatformTransactionManager manager, @Value("${knowingorder.qdrant.enabled:false}") boolean enabled) {
        this(jobs, management, documents, members, embedding, manager, enabled, Clock.systemUTC());
    }

    KnowledgeVectorJobService(KnowledgeVectorJobMapper jobs, KnowledgeManagementMapper management,
            KnowledgeDocumentMapper documents, EnterpriseMemberService members, LocalEmbeddingClient embedding,
            PlatformTransactionManager manager, boolean enabled, Clock clock) {
        this.jobs = jobs; this.management = management; this.documents = documents;
        this.members = members; this.embedding = embedding; this.enabled = enabled; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        // gate锁后计数必须看到刚提交的受理，不沿用MySQL默认可重复读的旧快照。
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Job submit(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, Submit request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() <= 0) throw invalid();
        validate(enterprise, space, document, version, request.requestId());
        independent();
        long received = clock.millis();
        TaskDeadline budget = TaskDeadline.start(ADMISSION_LIMIT);
        guard(budget, received);
        var scope = members.requireMember(user, enterprise);
        String fingerprint = hash("vector-submit:v1\n" + enterprise + "\n" + space + "\n" + document + "\n" + version
                + "\n" + request.expectedRevision());
        // 预检和入队保持相同锁序：gate → 有效成员/维护空间 → 文档；事务外才访问模型。
        Stored existing = transaction(() -> {
            guard(budget, received);
            gate(); maintain(scope, space);
            Stored old = existing(scope, space, document, version, request, fingerprint);
            if (old != null) return old;
            if (!enabled) throw new BusinessException(ResultCode.SERVICE_BUSY, "向量索引尚未启用，请联系管理员");
            source(scope, space, document, version, request.expectedRevision());
            capacity(scope, space, document, version);
            guard(budget, received);
            return null;
        });
        if (existing != null) return view(existing);
        guard(budget, received);
        ModelSnapshot snapshot = embedding.snapshot(budget);
        guard(budget, received);
        return transaction(() -> {
            guard(budget, received);
            gate(); maintain(scope, space);
            // 模型核验期间同请求可能已由别的节点受理；优先返回首次任务，不比较当前模型。
            Stored old = existing(scope, space, document, version, request, fingerprint);
            if (old != null) return view(old);
            Source source = source(scope, space, document, version, request.expectedRevision());
            capacity(scope, space, document, version);
            guard(budget, received);
            var job = new Stored(request.requestId(), UUID.randomUUID().toString(), enterprise, space, document, version,
                    scope.memberId(), scope.userId(), scope.userAuthVersion(), request.expectedRevision(), fingerprint,
                    source.sha256(), collection(snapshot), snapshot.model(), snapshot.revision(), snapshot.dimensions(),
                    source.chunks(), "QUEUED", null, null, null, 0, received, Math.addExact(received, JOB_LIMIT_MS), null, null);
            if (jobs.insert(job) != 1) throw conflict();
            guard(budget, received);
            return view(job);
        });
    }

    public Job read(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, String id) {
        return access(user, enterprise, space, document, version, id, false);
    }

    public Job cancel(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version, String id) {
        return access(user, enterprise, space, document, version, id, true);
    }

    private Job access(AuthenticatedUserPrincipal user, long enterprise, long space, long document, long version,
            String id, boolean cancel) {
        validate(enterprise, space, document, version, id); independent();
        var scope = members.requireMember(user, enterprise);
        return transaction(() -> {
            gate(); maintain(scope, space);
            own(jobs.find(id), scope, space, document, version);
            expire();
            if (cancel) jobs.cancel(id, clock.millis());
            // 文档删除后仍可查本人终态；保持当前维护授权，不读取已删原件或片段。
            return view(jobs.find(id));
        });
    }

    private Stored existing(EnterpriseMemberContext scope, long space, long document, long version,
            Submit request, String fingerprint) {
        Stored old = jobs.find(request.requestId());
        if (old != null) {
            own(old, scope, space, document, version);
            if (old.expectedRevision() != request.expectedRevision() || !fingerprint.equals(old.requestSha256())) throw conflict();
        }
        expire();
        return old == null ? null : jobs.find(old.id());
    }

    private Source source(EnterpriseMemberContext scope, long space, long document, long version, long revision) {
        var doc = documents.lockDocument(scope, space, document);
        var source = documents.readVersion(scope, space, document, version, clock.millis());
        if (doc == null || source == null || doc.id() != document || doc.knowledgeSpaceId() != space
                || !Objects.equals(source.getId(), version) || !Objects.equals(source.getDocumentId(), document)) throw missing();
        if (doc.revision() != revision || doc.ownerMemberId() == null || !"READY".equals(source.getParseStatus())
                || !Set.of("DRAFT", "PUBLISHED", "WITHDRAWN").contains(Objects.toString(source.getPublicationStatus(), ""))
                || source.getChunkCount() == null || source.getChunkCount() < 1 || source.getChunkCount() > 1000
                || source.getContentSha256() == null || !source.getContentSha256().matches("[0-9a-f]{64}")
                || documents.chunkCount(document, version) != source.getChunkCount()) throw conflict();
        return new Source(source.getContentSha256(), source.getChunkCount());
    }

    private void capacity(EnterpriseMemberContext scope, long space, long document, long version) {
        if (jobs.activeVersion(scope.enterpriseId(), space, document, version) != 0) throw conflict();
        if (jobs.activeTotal() >= GLOBAL_LIMIT || jobs.activeOwner(scope.enterpriseId(), scope.memberId()) >= MEMBER_LIMIT)
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引任务已达上限，请等待已有任务结束；勿重复提交");
    }

    private void gate() {
        if (!Integer.valueOf(1).equals(jobs.lockGate()))
            throw new BusinessException(ResultCode.SERVICE_BUSY, "向量任务队列未初始化，请联系管理员核对迁移");
    }
    private void maintain(EnterpriseMemberContext scope, long space) {
        members.requireCurrent(scope);
        if (management.lockMaintain(scope, space) == null) throw missing();
    }
    private void expire() { long now = clock.millis(); jobs.expire(now); jobs.abandon(now); }
    private void own(Stored job, EnterpriseMemberContext scope, long space, long document, long version) {
        if (job == null || job.enterpriseId() != scope.enterpriseId() || job.spaceId() != space
                || job.documentId() != document || job.versionId() != version || job.memberId() != scope.memberId()
                || job.userId() != scope.userId()) throw missing();
    }

    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status -> work.get()); }
        catch (DuplicateKeyException ex) { throw conflict(); }
        catch (DataAccessException | TransactionException ex) {
            // 提交回执丢失可能已经入队，不改编号重试，不把底层SQL/连接信息传给用户。
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引任务存储暂不可用，请先用原请求编号查询状态；不要更换编号重复提交");
        }
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引受理与状态操作须独立调用，不能持有外层事务等待模型");
    }
    private void guard(TaskDeadline budget, long received) {
        if (Thread.currentThread().isInterrupted() || clock.millis() < received || received < 0
                || received > Long.MAX_VALUE - JOB_LIMIT_MS)
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引受理已中止，请核对原请求状态后重试");
        budget.timeoutFor(ADMISSION_LIMIT);
        if (clock.millis() >= received + ADMISSION_LIMIT.toMillis())
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引受理已超时，请先核对原请求状态");
    }
    private static String collection(ModelSnapshot model) {
        return "ko_m_" + hash("vector-model:v1\n" + model.model() + "\n" + model.revision() + "\n" + model.dimensions());
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前运行环境缺少SHA-256"); }
    }
    private static void validate(long enterprise, long space, long document, long version, String id) {
        if (enterprise <= 0 || space <= 0 || document <= 0 || version <= 0) throw invalid();
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw invalid(); }
        catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static Job view(Stored job) {
        String message = switch (job.status()) {
            case "QUEUED" -> "索引任务已受理，等待处理；当前尚未发布可用索引";
            case "RUNNING" -> "正在构建和核对索引；任务受首次总截止约束";
            case "VERIFIED" -> "预期索引点已核对，尚未发布；核对成功不等于已投入问答";
            case "CANCELLED" -> "任务已取消；已发生的向量写入不会因此自动回滚";
            case "TIMED_OUT" -> "索引任务已超时，不会自动重提；请先核对原任务";
            case "FAILED" -> "索引构建未完成，未发布；请核对权限、版本和失败分类";
            default -> throw new BusinessException(ResultCode.SERVICE_BUSY, "索引任务状态异常，请联系管理员核对");
        };
        String failure = job.failureCode() == null ? null : FAILURES.contains(job.failureCode()) ? job.failureCode() : "INTERNAL_ERROR";
        return new Job(job.id(), job.documentId(), job.versionId(), job.expectedRevision(), job.status(), job.expectedChunks(),
                job.verifiedChunks(), job.createdMs(), job.deadlineMs(), job.finishedMs(), failure, message);
    }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "索引请求编号、资源编号或修订号不合法"); }
    private static BusinessException missing() { return new BusinessException(ResultCode.NOT_FOUND, "索引任务或资源不存在或不可访问"); }
    private static BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "任务或文档已变化，请核对原请求、最新修订与完整片段"); }
}
