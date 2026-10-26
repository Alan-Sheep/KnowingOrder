package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeDocuments.Chunk;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeDocumentMapper;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.mapper.KnowledgeVectorManifestMapper;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 授权索引执行器：单节点一个槽，无内存排队、不重放未知写入；VERIFIED不等于READY发布。 */
@Service
public final class KnowledgeVectorWorker {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeVectorWorker.class);
    private final KnowledgeVectorJobMapper jobs;
    private final KnowledgeVectorManifestMapper manifests;
    private final KnowledgeManagementMapper management;
    private final KnowledgeDocumentMapper documents;
    private final EnterpriseMemberService members;
    private final LocalEmbeddingClient embedding;
    private final QdrantVectorClient vectors;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;
    private boolean outageLogged;

    @Autowired
    public KnowledgeVectorWorker(KnowledgeVectorJobMapper jobs, KnowledgeVectorManifestMapper manifests,
            KnowledgeManagementMapper management, KnowledgeDocumentMapper documents, EnterpriseMemberService members,
            LocalEmbeddingClient embedding, QdrantVectorClient vectors, PlatformTransactionManager manager,
            @Value("${knowingorder.qdrant.enabled:false}") boolean enabled) {
        this(jobs, manifests, management, documents, members, embedding, vectors, manager, enabled, Clock.systemUTC());
    }

    KnowledgeVectorWorker(KnowledgeVectorJobMapper jobs, KnowledgeVectorManifestMapper manifests,
            KnowledgeManagementMapper management, KnowledgeDocumentMapper documents, EnterpriseMemberService members,
            LocalEmbeddingClient embedding, QdrantVectorClient vectors, PlatformTransactionManager manager,
            boolean enabled, Clock clock) {
        this.jobs = jobs; this.manifests = manifests; this.management = management; this.documents = documents;
        this.members = members; this.embedding = embedding; this.vectors = vectors; this.enabled = enabled; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 默认关闭时不建线程、不读取尚未迁移的表；启用后每轮最多执行一个任务。 */
    @PostConstruct public synchronized void start() {
        if (!enabled || closed || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "knowledge-vector"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    private void poll() {
        try { runOnce(); outageLogged = false; }
        catch (RuntimeException ex) {
            // 不记录SQL、原文或第三方异常正文；存储恢复后可读取原任务，不能重放RUNNING。
            if (!outageLogged) LOG.warn("向量任务存储或调度不可用，等待后续核对，不重放运行中任务");
            outageLogged = true;
        }
    }

    /** 内部调度入口，不接受客户端提供的身份；返回是否抢到任务，不表示索引成功。 */
    public boolean runOnce() {
        if (!enabled || closed || !busy.compareAndSet(false, true)) return false;
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new BusinessException(ResultCode.SERVICE_BUSY, "向量执行器不能持有外层事务调用");
            if (Thread.currentThread().isInterrupted()) return false;
            jobs.expire(clock.millis()); jobs.abandon(clock.millis());
            for (Stored candidate : jobs.pending(clock.millis())) {
                String token = UUID.randomUUID().toString();
                if (transaction(() -> jobs.claim(candidate.id(), token, clock.millis(), candidate.deadlineMs())) != 1) continue;
                execute(candidate, token);
                return true;
            }
            return false;
        } finally { busy.set(false); }
    }

    private void execute(Stored job, String token) {
        String failure = "INTERNAL_ERROR";
        try {
            long started = clock.millis();
            if (job.createdMs() < 0 || job.deadlineMs() <= started || job.deadlineMs() - job.createdMs() > 120_000
                    || job.expectedChunks() < 1 || job.expectedChunks() > VectorIndexManifest.MAX_CHUNKS)
                throw new Stop("REVISION_CONFLICT");
            var run = new Run(job, token, TaskDeadline.start(Duration.ofMillis(job.deadlineMs() - started)), started);
            List<Chunk> chunks = checked(run, true, null);
            Index index = new Index(job.collectionName(), job.modelName(), job.modelRevision(), job.dimensions());
            Scope scope = new Scope(job.enterpriseId(), job.spaceId(), job.documentId(), job.versionId(), job.generation());
            failure = "VECTOR_UNAVAILABLE";
            model(run, index);
            var points = new ArrayList<Point>(chunks.size());
            for (int offset = 0; offset < chunks.size(); offset += 16) {
                checked(run, false, null);
                List<Chunk> batch = chunks.subList(offset, Math.min(offset + 16, chunks.size()));
                failure = "VECTOR_UNAVAILABLE";
                var output = embedding.embed(batch.stream().map(Chunk::content).toList(), run.deadline);
                if (output == null || output.size() != batch.size()) throw new Stop("MANIFEST_MISMATCH");
                model(run, index);
                failure = "MANIFEST_MISMATCH";
                for (int i = 0; i < batch.size(); i++) {
                    var vector = output.get(i);
                    if (vector == null || !index.model().equals(vector.model()) || vector.values().size() != index.dimensions())
                        throw new Stop("MODEL_CHANGED");
                    QdrantVectorClient.vectorSha256(vector);
                    points.add(new Point(batch.get(i).id(), sha256(batch.get(i).content()), vector));
                }
                checked(run, false, null);
            }
            failure = "MANIFEST_MISMATCH";
            var manifest = VectorIndexManifest.capture(index, scope, job.expectedRevision(), job.sourceSha256(), job.expectedChunks(), points);
            // 先提交完整预期，才允许第一次Qdrant写；清单保存失败时外部写入次数为零。
            checkpoint(run, chunks, () -> manifests.persist(job.id(), token, manifest, clock));
            failure = "VECTOR_UNAVAILABLE";
            if (!vectors.inspect(index, run.deadline)) {
                checked(run, false, null);
                failure = "WRITE_UNCONFIRMED";
                vectors.create(index, run.deadline);
                checked(run, false, null);
            }
            for (int offset = 0; offset < points.size(); offset += 16) {
                checked(run, false, null);
                failure = "WRITE_UNCONFIRMED";
                vectors.upsert(index, scope, points.subList(offset, Math.min(offset + 16, points.size())), run.deadline);
                checked(run, false, null);
            }
            failure = "VECTOR_UNAVAILABLE";
            VectorIndexManifest.Verification verification;
            try { verification = manifest.verify(vectors, run.deadline, () -> checked(run, false, null)); }
            catch (BusinessException ex) {
                if (ex.getCode() == ResultCode.CONFLICT.getCode()) throw new Stop("MANIFEST_MISMATCH");
                throw ex;
            }
            model(run, index);
            // 最后一次短事务重读完整片段、清单和租约，再CAS到VERIFIED；这里没有READY指针。
            failure = "MANIFEST_MISMATCH";
            checkpoint(run, chunks, () -> {
                manifests.persist(job.id(), token, manifest, clock);
                run.time();
                if (jobs.verified(job.id(), token, verification.manifestSha256(), verification.checkedChunks(), clock.millis()) != 1)
                    throw new Stop("MANIFEST_MISMATCH");
                return 1;
            });
        } catch (RuntimeException ex) {
            if (ex instanceof Stop stop) failure = stop.code;
            else if (!(ex instanceof BusinessException)) failure = "INTERNAL_ERROR";
            // 提交回执丢失时可能已经VERIFIED；fail只更新本租约活RUNNING，不覆盖成功/取消/超时。
            jobs.expire(clock.millis()); jobs.abandon(clock.millis());
            jobs.fail(job.id(), token, failure, clock.millis());
        }
    }

    private void model(Run run, Index index) {
        run.time();
        LocalEmbeddingClient.ModelSnapshot snapshot;
        try { snapshot = embedding.snapshot(run.deadline); }
        catch (BusinessException ex) {
            if (ex.getCode() == ResultCode.CONFLICT.getCode()) throw new Stop("MODEL_CHANGED");
            throw ex;
        }
        run.time();
        if (!index.model().equals(snapshot.model()) || !index.revision().equals(snapshot.revision())
                || index.dimensions() != snapshot.dimensions()) throw new Stop("MODEL_CHANGED");
    }

    private List<Chunk> checked(Run run, boolean full, List<Chunk> expected) {
        return transaction(() -> {
            run.time();
            Stored job = run.job;
            EnterpriseMemberContext member;
            try {
                member = members.requireMember(new AuthenticatedUserPrincipal(job.userId(), job.authVersion()), job.enterpriseId());
                if (member.memberId() != job.memberId() || management.lockMaintain(member, job.spaceId()) == null)
                    throw new Stop("ACCESS_REVOKED");
            } catch (BusinessException ex) { throw new Stop("ACCESS_REVOKED"); }
            // 与受理/取消一致：空间 → 文档 → 任务；不先锁任务再反向请求空间锁。
            var document = documents.lockDocument(member, job.spaceId(), job.documentId());
            var version = documents.readVersion(member, job.spaceId(), job.documentId(), job.versionId(), clock.millis());
            if (document == null || version == null || !Objects.equals(document.id(), job.documentId())
                    || !Objects.equals(document.knowledgeSpaceId(), job.spaceId()) || document.revision() != job.expectedRevision()
                    || document.ownerMemberId() == null || !Objects.equals(version.getId(), job.versionId())
                    || !Objects.equals(version.getDocumentId(), job.documentId()) || !"READY".equals(version.getParseStatus())
                    || !Set.of("DRAFT", "PUBLISHED", "WITHDRAWN").contains(Objects.toString(version.getPublicationStatus(), ""))
                    || !job.sourceSha256().equals(version.getContentSha256())
                    || !Objects.equals(version.getChunkCount(), job.expectedChunks())
                    || documents.chunkCount(job.documentId(), job.versionId()) != job.expectedChunks()) throw new Stop("REVISION_CONFLICT");
            if (jobs.owns(job.id(), run.token, clock.millis()) != 1) throw new Stop("LEASE_LOST");
            List<Chunk> result = full ? chunks(member, job) : List.of();
            if (expected != null && !expected.equals(result)) throw new Stop("REVISION_CONFLICT");
            run.time();
            return result;
        });
    }

    private <T> T checkpoint(Run run, List<Chunk> expected, Supplier<T> mutation) {
        return transaction(() -> {
            // 同一个事务内复核来源并写状态；checked加入本事务，不跨HTTP持锁。
            checked(run, true, expected);
            T result = mutation.get(); run.time(); return result;
        });
    }

    private List<Chunk> chunks(EnterpriseMemberContext member, Stored job) {
        var result = new ArrayList<Chunk>(job.expectedChunks());
        var ids = new HashSet<Long>();
        // 沿用分页插件的500上限，不能申请1000后被插件裁掉而仍当作完整快照。
        for (int offset = 0; offset < job.expectedChunks(); offset += 500) {
            var page = documents.chunks(new Page<Chunk>(offset / 500 + 1, 500, false), member,
                    job.spaceId(), job.documentId(), job.versionId(), clock.millis()).getRecords();
            if (page.size() != Math.min(500, job.expectedChunks() - offset)) throw new Stop("REVISION_CONFLICT");
            for (Chunk chunk : page) {
                if (chunk == null || chunk.id() == null || chunk.id() <= 0 || !ids.add(chunk.id())
                        || chunk.chunkIndex() != result.size() || chunk.content() == null || chunk.content().isBlank()
                        || chunk.content().length() > 1200 || chunk.content().codePoints().anyMatch(cp ->
                        Character.getType(cp) == Character.SURROGATE || Character.getType(cp) == Character.FORMAT
                                || (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')))
                    throw new Stop("REVISION_CONFLICT");
                result.add(chunk);
            }
        }
        return List.copyOf(result);
    }

    private <T> T transaction(Supplier<T> work) { return tx.execute(status -> work.get()); }

    private final class Run {
        private final Stored job;
        private final String token;
        private final TaskDeadline deadline;
        private long observed;
        private Run(Stored job, String token, TaskDeadline deadline, long started) {
            this.job = job; this.token = token; this.deadline = deadline; this.observed = started;
        }
        private void time() {
            long now = clock.millis();
            if (closed || Thread.currentThread().isInterrupted() || now < observed || now < job.createdMs())
                throw new Stop("INTERNAL_ERROR");
            if (now >= job.deadlineMs()) throw new Stop("LEASE_LOST");
            observed = now;
            deadline.timeoutFor(Duration.ofSeconds(5));
        }
    }

    private static final class Stop extends RuntimeException {
        private final String code;
        private Stop(String code) { super(code); this.code = code; }
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前运行环境缺少SHA-256"); }
    }

    @PreDestroy public synchronized void close() {
        closed = true;
        if (scheduler != null) scheduler.shutdownNow();
    }
}
