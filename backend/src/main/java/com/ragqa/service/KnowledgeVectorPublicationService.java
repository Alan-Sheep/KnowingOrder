package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.mapper.KnowledgeVectorPublicationMapper;
import com.ragqa.mapper.KnowledgeVectorPublicationMapper.Pointer;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.LocalEmbeddingClient;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.VectorIndexManifest;
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

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 授权索引发布服务，仅内部Java入口；正式问答和HTTP/UI接线另行验收。
 *
 * <p>一、为何这样做及替代方案：复用Spring TransactionTemplate，将模型/向量HTTP放事务外，
 * 最终短事务重核维护权和固定来源。整段@Transactional会持锁等待外部服务；直接信VERIFIED
 * 会漏掉构建后的撤权、模型换版及点丢失。未增加SDK、分布式事务或自动重试。</p>
 * <p>二、数据调用链：publish(可信身份,企业/空间/文档,任务UUID/预期指针修订)先短事务取
 * 本人VERIFIED任务和完整清单，restore重算原封存SHA，再核模型、按16点全量只读核对、
 * 再核模型，最终事务内维护权→文档/版本→任务/完整清单/原文→CAS指针。
 * 例：17点分16+1两批；预期0首次返回READY/revision=1，撤回预期1返回WITHDRAWN/revision=2。
 * READY只描述当前索引指针，不能宣称正式向量问答已启用。read只核数据库，不证明外部服务健康。
 * 任务UUID只用于定位，不能代替身份；发布仅本人任务，撤回由当前维护者按预期指针执行。</p>
 * <p>三、故障与边界：总预算15秒，外部单次沿用客户端5秒，每段事务5秒；单实例2槽无等待队列。
 * 所有批次前后核权和来源；中断、时钟回退、预算耗尽不发布。失败不改任务、不补写向量。
 * 撤回/状态读取不调用模型或Qdrant，服务停用仍能撤回残留指针。数据库回执不明提示先查询原指针，
 * 不回传底层SQL/上游正文。多节点并发仍靠数据库CAS，2槽不是跨节点总配额。
 * 外部核验和提交有间隙，不能保证原子跨库快照；检索时仍须再核代次、授权和来源。
 * 模型前后快照不能识别A→B→A，部署须固定不可变模型实例；payload摘要不证明实际向量未损坏。</p>
 * <p>四、优化与代价：先测1000片段/63批延迟，再评估版本不可变摘要与批量核验；缓存要承担撤权失效，
 * 跨节点公平配额须额外协调。不能靠加线程、放宽清单或无限重试提高通过率。
 * 学习点：RAG代次一致性、乐观CAS、短事务/TOCTOU复核、背压、幂等和未知提交结果。
 * 本单元没有MCP/Redis、原文外发、模型下载、自动迁移或Git提交。</p>
 * <p>开工只读核对Spring官方GitHub TransactionTemplate.java的execute/getTransaction/commit及异常回滚，
 * blob=475994bd3136da0030fb0482bdab0057b822a061；不复制代码，不把blob当发布版本。
 * 上一单元指针迁移/四方面记录见sql/migrations/20261020_02_knowledge_vector_publication.sql。
 * 验证：在backend沿用E盘临时目录运行mvn -o -Dtest=KnowledgeVectorPublicationServiceTest test，
 * 使用H2真实权限/事务和外部方法替身；不替代MySQL/Qdrant/模型或生产容量验收。
 * 2026-10-20本轮最终10组602项通过，失败/错误/跳过均为0，其中本服务61项；
 * 报告后缀vector-publish-service-final-20261020。覆盖提交超时但数据已落库、回执丢失、回滚、
 * 撤权/来源变化、两槽背压及1/16/17/1000点完整核验。提交后超时只提示查原指针，不宣称回滚。
 * 初次测试因沙箱无法在E盘创建测试代理临时文件报错，获准后原路径运行通过，未降低断言。
 * 当前单元仅三个源码/测试文件，后续HTTP/UI、迁移实库与正式混合检索仍待接续验收。</p>
 */
@Service
public final class KnowledgeVectorPublicationService {
    private static final Duration TOTAL = Duration.ofSeconds(15);
    private final KnowledgeVectorJobMapper jobs;
    private final KnowledgeVectorPublicationMapper publications;
    private final KnowledgeManagementMapper management;
    private final EnterpriseMemberService members;
    private final LocalEmbeddingClient embedding;
    private final QdrantVectorClient vectors;
    private final boolean enabled;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Semaphore slots = new Semaphore(2);

    public record Publish(String jobId, Long expectedRevision) { }
    public record Withdraw(String expectedJobId, Long expectedRevision) { }
    /** 不暴露模型、原件摘要、向量代次、租约或账号信息。 */
    public record State(Long documentId, String jobId, long revision, String status, Long updatedMs, String message) { }
    private record Prepared(Stored job, VectorIndexManifest manifest) { }

    @Autowired
    public KnowledgeVectorPublicationService(KnowledgeVectorJobMapper jobs, KnowledgeVectorPublicationMapper publications,
            KnowledgeManagementMapper management, EnterpriseMemberService members, LocalEmbeddingClient embedding,
            QdrantVectorClient vectors, PlatformTransactionManager manager,
            @Value("${knowingorder.qdrant.enabled:false}") boolean enabled) {
        this(jobs, publications, management, members, embedding, vectors, manager, enabled, Clock.systemUTC());
    }

    KnowledgeVectorPublicationService(KnowledgeVectorJobMapper jobs, KnowledgeVectorPublicationMapper publications,
            KnowledgeManagementMapper management, EnterpriseMemberService members, LocalEmbeddingClient embedding,
            QdrantVectorClient vectors, PlatformTransactionManager manager, boolean enabled, Clock clock) {
        this.jobs = jobs; this.publications = publications; this.management = management; this.members = members;
        this.embedding = embedding; this.vectors = vectors; this.enabled = enabled; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public State publish(AuthenticatedUserPrincipal user, long enterprise, long space, long document, Publish request) {
        validate(enterprise, space, document, request == null ? null : request.expectedRevision());
        if (request == null || !uuid(request.jobId())) throw invalid();
        independent();
        if (!enabled) throw busy("向量发布尚未启用，请联系管理员");
        Run run = new Run();
        if (!slots.tryAcquire()) throw busy("索引发布核验已达并发上限，请稍后查询原指针，勿重复提交");
        try {
            run.check();
            EnterpriseMemberContext scope = members.requireMember(user, enterprise);
            Prepared prepared = transaction(() -> {
                run.check(); maintain(scope, space);
                Stored job = jobs.find(request.jobId());
                if (job == null || job.enterpriseId() != enterprise || job.spaceId() != space || job.documentId() != document
                        || job.memberId() != scope.memberId() || job.userId() != scope.userId()) throw missing();
                if (job.authVersion() != scope.userAuthVersion()) throw conflict();
                current(scope, job, run);
                Pointer pointer = publications.lockPointer(enterprise, space, document);
                long revision = request.expectedRevision();
                if (pointer == null ? revision != 0 : !(pointer.revision() == revision
                        || (pointer.revision() == revision + 1 && job.id().equals(pointer.jobId())))) throw conflict();
                VectorIndexManifest manifest;
                try {
                    manifest = VectorIndexManifest.restore(index(job), vectorScope(job), job.expectedRevision(),
                            job.sourceSha256(), job.expectedChunks(), publications.entries(job.id()), job.manifestSha256());
                } catch (BusinessException | IllegalArgumentException ex) { throw conflict(); }
                run.check();
                return new Prepared(job, manifest);
            });
            model(prepared.job(), run);
            prepared.manifest().verify(vectors, run.budget, () -> transaction(() -> {
                run.check(); maintain(scope, space); current(scope, prepared.job(), run); return null;
            }));
            model(prepared.job(), run);
            Pointer pointer = transaction(() -> {
                run.check(); maintain(scope, space);
                Pointer result = publications.publish(prepared.job(), prepared.manifest().expected(), request.expectedRevision(), clock);
                run.check();
                return result;
            });
            run.check();
            return view(pointer, "READY");
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw busy("索引发布未确认，请查询原指针状态，不要盲目重复提交"); }
        finally { slots.release(); }
    }

    public State withdraw(AuthenticatedUserPrincipal user, long enterprise, long space, long document, Withdraw request) {
        validate(enterprise, space, document, request == null ? null : request.expectedRevision());
        if (request == null || (request.expectedJobId() != null && !uuid(request.expectedJobId()))) throw invalid();
        independent(); Run run = new Run();
        Pointer result = transaction(() -> {
            run.check();
            EnterpriseMemberContext scope = members.requireMember(user, enterprise);
            run.check(); maintain(scope, space);
            Pointer pointer = publications.withdraw(enterprise, space, document, request.expectedRevision(), request.expectedJobId(), clock);
            run.check(); return pointer;
        });
        run.check();
        return view(result, "WITHDRAWN");
    }

    /** 维护者核对指针；READY只表示数据库候选有效，故障状态不伪装成无资料。 */
    public State read(AuthenticatedUserPrincipal user, long enterprise, long space, long document) {
        validate(enterprise, space, document, 0L); independent(); Run run = new Run();
        State result = transaction(() -> {
            run.check();
            EnterpriseMemberContext scope = members.requireMember(user, enterprise);
            run.check(); maintain(scope, space);
            if (publications.lockDocument(enterprise, space, document) == null) throw missing();
            Pointer pointer = publications.lockPointer(enterprise, space, document);
            String status = pointer == null ? "UNPUBLISHED" : pointer.jobId() == null ? "WITHDRAWN"
                    : publications.candidate(scope, space, document, clock.millis()) == null ? "STALE" : "READY";
            run.check();
            return pointer == null ? new State(document, null, 0, status, null, "尚未发布向量索引") : view(pointer, status);
        });
        run.check();
        return result;
    }

    private void current(EnterpriseMemberContext scope, Stored expected, Run run) {
        if (publications.lockDocument(scope.enterpriseId(), expected.spaceId(), expected.documentId()) == null) throw missing();
        if (publications.lockSource(expected, clock.millis()) == null
                || !expected.equals(publications.lockVerified(expected.id())) || !"VERIFIED".equals(expected.status())
                || expected.finishedMs() == null || expected.finishedMs() < expected.createdMs()
                || expected.finishedMs() > clock.millis() || expected.finishedMs() >= expected.deadlineMs()
                || expected.verifiedChunks() != expected.expectedChunks()) throw conflict();
        run.check();
    }

    private void model(Stored job, Run run) {
        run.check();
        var snapshot = embedding.snapshot(run.budget);
        run.check();
        if (snapshot == null || !Objects.equals(snapshot.model(), job.modelName())
                || !Objects.equals(snapshot.revision(), job.modelRevision()) || snapshot.dimensions() != job.dimensions()) throw conflict();
    }

    private void maintain(EnterpriseMemberContext scope, long space) {
        members.requireCurrent(scope);
        if (management.lockMaintain(scope, space) == null) throw missing();
    }
    private <T> T transaction(Supplier<T> work) {
        try { return tx.execute(status -> work.get()); }
        catch (DuplicateKeyException ex) { throw conflict(); }
        catch (DataAccessException | TransactionException ex) {
            throw busy("索引存储操作未确认，请先查询原指针状态，不要更换修订号盲目重试");
        }
    }
    private final class Run {
        private final TaskDeadline budget = TaskDeadline.start(TOTAL);
        private final long received = clock.millis();
        private long observed = received;
        private void check() {
            long now = clock.millis();
            if (Thread.currentThread().isInterrupted() || received < 0 || now < observed || now - received >= TOTAL.toMillis())
                throw busy("索引操作已中止或超时，请核对原指针状态，勿重复提交");
            observed = now;
            budget.timeoutFor(Duration.ofSeconds(5));
        }
    }
    private static QdrantVectorClient.Index index(Stored job) {
        return new QdrantVectorClient.Index(job.collectionName(), job.modelName(), job.modelRevision(), job.dimensions());
    }
    private static QdrantVectorClient.Scope vectorScope(Stored job) {
        return new QdrantVectorClient.Scope(job.enterpriseId(), job.spaceId(), job.documentId(), job.versionId(), job.generation());
    }
    private static State view(Pointer pointer, String status) {
        String message = switch (status) {
            case "READY" -> "索引代次已发布；实际回答仍以检索时的授权、版本和证据核验为准";
            case "WITHDRAWN" -> "索引已撤回，旧代次不会自动恢复；原向量保留待受控清理";
            default -> "当前索引已失效，不能用于回答；请核对最新版本并重建";
        };
        return new State(pointer.documentId(), pointer.jobId(), pointer.revision(), status, pointer.updatedMs(), message);
    }
    private static void validate(long enterprise, long space, long document, Long revision) {
        if (enterprise <= 0 || space <= 0 || document <= 0 || revision == null || revision < 0 || revision == Long.MAX_VALUE) throw invalid();
    }
    private static boolean uuid(String id) {
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw busy("索引发布须独立调用，不能持有外层事务等待模型或向量服务");
    }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "索引任务、资源编号或预期指针修订不合法"); }
    private static BusinessException missing() { return new BusinessException(ResultCode.NOT_FOUND, "索引任务或资源不存在或不可访问"); }
    private static BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "索引版本、模型、完整清单或发布修订已变化，请核对原指针"); }
    private static BusinessException busy(String message) { return new BusinessException(ResultCode.SERVICE_BUSY, message); }
}
