package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeRetrievalMapper;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.mapper.KnowledgeVectorPublicationMapper;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.QdrantVectorClient.Hit;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 内部混合检索编排，尚未注册为Spring Bean，正式问答仍使用关键词实现。
 * 先授权并恢复封存清单，再查本机模型和限定代次的Qdrant，SQL复核后RRF融合。
 * 阈值和RRF常数显式传入；24文档/每文档24候选/两路各24/最终6条是保护上限，非质量保证。
 * 外部调用不持数据库事务；阶段共用父预算，双槽无排队、不重试、不静默降级。
 * 前后快照不能提供跨服务原子性或识别模型A→B→A；部署仍须固定模型及索引代次。
 */
public final class HybridKnowledgeRetriever implements KnowledgeRetriever {
    private static final Duration STAGE_LIMIT = Duration.ofSeconds(15);
    private final KnowledgeRetrievalMapper evidence;
    private final KnowledgeVectorPublicationMapper publications;
    private final EnterpriseResourceService resources;
    private final LocalEmbeddingClient embedding;
    private final QdrantVectorClient vectors;
    private final double threshold;
    private final int smoothing;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(2);

    private record Prepared(Stored job, VectorIndexManifest manifest) { }
    private record Candidate(Prepared source, Hit hit) { }

    public HybridKnowledgeRetriever(KnowledgeRetrievalMapper evidence, KnowledgeVectorPublicationMapper publications,
            EnterpriseResourceService resources, LocalEmbeddingClient embedding, QdrantVectorClient vectors,
            double threshold, int smoothing) {
        this(evidence, publications, resources, embedding, vectors, threshold, smoothing, Clock.systemUTC());
    }

    HybridKnowledgeRetriever(KnowledgeRetrievalMapper evidence, KnowledgeVectorPublicationMapper publications,
            EnterpriseResourceService resources, LocalEmbeddingClient embedding, QdrantVectorClient vectors,
            double threshold, int smoothing, Clock clock) {
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1 || smoothing < 1 || smoothing > 1000)
            throw new IllegalArgumentException("混合检索阈值或RRF常数不合法");
        this.evidence = Objects.requireNonNull(evidence); this.publications = Objects.requireNonNull(publications);
        this.resources = Objects.requireNonNull(resources); this.embedding = Objects.requireNonNull(embedding);
        this.vectors = Objects.requireNonNull(vectors); this.clock = Objects.requireNonNull(clock);
        this.threshold = threshold; this.smoothing = smoothing;
    }

    @Override
    public List<Evidence> retrieve(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline) {
        if (member == null || spaceId <= 0 || deadline == null || question == null || question.isBlank()
                || question.length() > 1200 || question.codePoints().anyMatch(cp ->
                    (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')
                    || Character.getType(cp) == Character.FORMAT || (cp >= 0xD800 && cp <= 0xDFFF)))
            throw new BusinessException(ResultCode.PARAM_ERROR, "混合检索需要有效身份、空间、截止时间及1200字符以内的问题");
        independent();
        Run run = new Run(deadline.limitedTo(STAGE_LIMIT));
        if (!slots.tryAcquire()) throw busy("混合检索已达并发上限，请稍后再试");
        try {
            run.check(); resources.requireSpace(member, spaceId); run.check();
            List<Stored> jobs = publications.candidates(member, spaceId, run.now());
            if (jobs.isEmpty()) throw busy("当前空间尚无可用向量索引，请联系知识负责人发布索引或选择关键词检索");
            var prepared = new ArrayList<Prepared>();
            for (Stored job : jobs) { run.check(); prepared.add(new Prepared(job, manifest(job))); }
            List<String> terms = KeywordKnowledgeRetriever.terms(question);
            List<Evidence> lexical = terms.isEmpty() ? List.of() : evidence.search(member, spaceId, run.now(), terms);
            if (lexical == null || lexical.size() > 24) throw conflict();
            current(member, spaceId, jobs, run);
            model(prepared, run);
            current(member, spaceId, jobs, run);
            List<Vector> encoded = embedding.embed(List.of(question), run.budget);
            run.check();
            if (encoded == null || encoded.size() != 1 || encoded.get(0) == null) throw conflict();
            Vector query = encoded.get(0);
            var first = prepared.get(0).manifest().index();
            if (!first.model().equals(query.model()) || first.dimensions() != query.values().size()) throw conflict();
            try { HybridRetrievalRanking.cosine(query, query); } catch (BusinessException ex) { throw conflict(); }

            var semantic = new ArrayList<Candidate>();
            var seen = new HashSet<Long>();
            for (Prepared source : prepared) {
                current(member, spaceId, source.job(), run);
                List<Hit> hits = vectors.query(source.manifest().index(), source.manifest().scope(), query,
                        threshold, 24, run.budget);
                current(member, spaceId, source.job(), run);
                if (hits == null || hits.size() > 24) throw conflict();
                Map<Long, String> expected = new HashMap<>();
                for (var row : source.manifest().expected()) expected.put(row.chunkId(), row.contentSha256());
                for (Hit hit : hits) {
                    if (hit == null || !seen.add(hit.chunkId()) || !Double.isFinite(hit.score())
                            || hit.score() < -1.000001 || hit.score() > 1.000001 || hit.score() + 0.000001 < threshold
                            || !Objects.equals(expected.get(hit.chunkId()), hit.contentSha256())
                            || hit.contentSha256() == null) throw conflict();
                    semantic.add(new Candidate(source, hit));
                }
            }
            // 最多24*24条只作全局语义排序；各文档的相似度可比较之前已核相同模型修订和维度。
            semantic.sort(Comparator.comparingDouble((Candidate row) -> row.hit().score()).reversed()
                    .thenComparingLong(row -> row.hit().chunkId()));
            List<Candidate> best = semantic.subList(0, Math.min(24, semantic.size()));
            var admitted = new HashMap<Long, Evidence>();
            var lexicalIds = new ArrayList<Long>();
            for (Evidence row : lexical) {
                if (row == null || lexicalIds.contains(row.chunkId())) throw conflict();
                Evidence fresh = read(member, spaceId, row.chunkId(), run);
                if (!row.equals(fresh)) throw conflict();
                lexicalIds.add(row.chunkId()); admitted.put(row.chunkId(), fresh);
            }
            for (Candidate row : best) {
                Evidence fresh = read(member, spaceId, row.hit().chunkId(), run);
                Stored job = row.source().job();
                if (fresh.documentId() != job.documentId() || fresh.versionId() != job.versionId()
                        || !sha256(fresh.content()).equals(row.hit().contentSha256())) throw conflict();
                Evidence previous = admitted.put(fresh.chunkId(), fresh);
                if (previous != null && !previous.equals(fresh)) throw conflict();
            }
            var fused = HybridRetrievalRanking.fuse(lexicalIds, best.stream().map(row -> row.hit().chunkId()).toList(), smoothing, 24);
            var selected = new ArrayList<Evidence>();
            for (var row : fused) {
                Evidence next = admitted.get(row.chunkId());
                boolean overlaps = selected.stream().anyMatch(previous -> previous.versionId().equals(next.versionId())
                        && Math.min(previous.endOffset(), next.endOffset()) - Math.max(previous.startOffset(), next.startOffset()) > 120);
                if (!overlaps) selected.add(next);
                if (selected.size() == 6) break;
            }
            current(member, spaceId, jobs, run);
            model(prepared, run);
            // 外部调用结束后重核封存清单及最终证据，不能把查询前的授权快照当最终准入。
            for (Prepared source : prepared) {
                run.check();
                if (!source.manifest().expected().equals(manifest(source.job()).expected())) throw conflict();
            }
            current(member, spaceId, jobs, run);
            for (Evidence row : selected) if (!row.equals(read(member, spaceId, row.chunkId(), run))) throw conflict();
            current(member, spaceId, jobs, run);
            return List.copyOf(selected);
        } catch (BusinessException ex) { throw ex; }
        catch (RuntimeException ex) { throw busy("混合检索未完成，已停止等待；请稍后再试，不会自动重试或使用旧索引"); }
        finally { slots.release(); }
    }

    /** 引用读取仍以当前业务资料权限为准；索引撤回不等于删除可授权访问的业务原文。 */
    @Override
    public Evidence requireEvidence(EnterpriseMemberContext member, long spaceId, long chunkId) {
        if (member == null || spaceId <= 0 || chunkId <= 0)
            throw new BusinessException(ResultCode.PARAM_ERROR, "引用资源编号或身份不合法");
        independent(); resources.requireSpace(member, spaceId);
        Evidence row = evidence.evidence(member, spaceId, clock.millis(), chunkId);
        if (row == null) throw new BusinessException(ResultCode.NOT_FOUND, "资料不存在、已失效或不可访问");
        validate(row); return row;
    }

    private Evidence read(EnterpriseMemberContext member, long space, Long chunk, Run run) {
        run.check();
        if (chunk == null || chunk <= 0) throw conflict();
        Evidence row = evidence.evidence(member, space, run.now(), chunk);
        run.check();
        if (row == null || !chunk.equals(row.chunkId())) throw conflict();
        validate(row); return row;
    }

    private VectorIndexManifest manifest(Stored job) {
        try {
            return VectorIndexManifest.restore(new QdrantVectorClient.Index(job.collectionName(), job.modelName(), job.modelRevision(), job.dimensions()),
                    new QdrantVectorClient.Scope(job.enterpriseId(), job.spaceId(), job.documentId(), job.versionId(), job.generation()),
                    job.expectedRevision(), job.sourceSha256(), job.expectedChunks(), publications.entries(job.id()), job.manifestSha256());
        } catch (BusinessException | IllegalArgumentException ex) { throw conflict(); }
    }

    private void model(List<Prepared> sources, Run run) {
        run.check();
        var installed = embedding.snapshot(run.budget);
        run.check();
        for (Prepared source : sources) {
            var expected = source.manifest().index();
            if (installed == null || !expected.model().equals(installed.model()) || !expected.revision().equals(installed.revision())
                    || expected.dimensions() != installed.dimensions()) throw conflict();
        }
    }

    private void current(EnterpriseMemberContext member, long space, List<Stored> expected, Run run) {
        run.check(); resources.requireSpace(member, space);
        if (!expected.equals(publications.candidates(member, space, run.now()))) throw conflict();
        run.check();
    }

    private void current(EnterpriseMemberContext member, long space, Stored expected, Run run) {
        run.check();
        if (!expected.equals(publications.candidate(member, space, expected.documentId(), run.now()))) throw conflict();
        run.check();
    }

    private final class Run {
        private final TaskDeadline budget;
        private long observed;
        private Run(TaskDeadline budget) { this.budget = budget; observed = clock.millis(); }
        private long now() { check(); return observed; }
        private void check() {
            long now = clock.millis();
            if (Thread.currentThread().isInterrupted() || now < 0 || now < observed) throw busy("混合检索已取消或时钟异常，未返回旧结果");
            observed = now; budget.timeoutFor(Duration.ofSeconds(2));
        }
    }

    private static void validate(Evidence row) {
        if (row.chunkId() == null || row.chunkId() <= 0 || row.documentId() == null || row.documentId() <= 0
                || row.versionId() == null || row.versionId() <= 0 || row.versionNo() < 1 || row.chunkIndex() < 0
                || row.content() == null || row.content().isBlank() || row.content().length() > 1000 || row.startOffset() < 0
                || (long) row.endOffset() - row.startOffset() != row.content().length()
                || row.startLine() < 1 || row.endLine() < row.startLine()) throw conflict();
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前JDK不支持SHA-256"); }
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw busy("混合检索须独立调用，不能持有外层事务等待向量服务");
    }
    private static BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "检索期间权限、版本、模型或证据发生变化，未返回结果；请刷新后重试");
    }
    private static BusinessException busy(String message) { return new BusinessException(ResultCode.SERVICE_BUSY, message); }
}
