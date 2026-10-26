package com.ragqa.mapper;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 内部发布指针仓储：VERIFIED任务与READY候选指针分开保存，不改变业务文档的发布版本。
 * 写入口必须由上层先核当前维护权，再以同数据源非只读短事务调用；异常必须传播回滚。
 * 本仓储不核Qdrant或模型；checked须来自上层事务外的完整核验，禁止直接绑定客户端请求。
 */
@Mapper
public interface KnowledgeVectorPublicationMapper {
    record Pointer(long enterpriseId, long spaceId, long documentId, String jobId, long revision, long updatedMs) {
        @Override public String toString() { return "VectorPublication[revision=" + revision + ",ready=" + (jobId != null) + "]"; }
    }
    record Fragment(long chunkId, int chunkIndex, String content) {
        @Override public String toString() { return "VectorPublicationFragment[正文不记录]"; }
    }

    /**
     * 首次预期指针修订为0；每次实际切换加1。同一次CAS的提交回执丢失允许核对后幂等返回。
     * 固定锁序为文档→版本→任务→清单/片段→指针；上层先锁维护空间，不在事务内等待HTTP。
     */
    default Pointer publish(Stored expected, List<QdrantVectorClient.Stored> checked, long revision, Clock clock) {
        if (expected == null || !uuid(expected.id()) || !"VERIFIED".equals(expected.status())
                || !hash(expected.manifestSha256()) || expected.expectedChunks() < 1 || expected.expectedChunks() > 1000
                || expected.verifiedChunks() != expected.expectedChunks() || expected.finishedMs() == null
                || expected.finishedMs() < expected.createdMs() || expected.finishedMs() >= expected.deadlineMs()
                || checked == null || checked.size() != expected.expectedChunks()
                || checked.stream().anyMatch(Objects::isNull)) throw invalid();
        validate(expected.enterpriseId(), expected.spaceId(), expected.documentId(), revision, clock);
        var observed = List.copyOf(checked);
        long last = 0;
        for (var row : observed) {
            if (row.chunkId() <= last || !hash(row.contentSha256()) || !hash(row.vectorSha256())) throw invalid();
            last = row.chunkId();
        }
        transaction();
        long started = clock.millis();
        if (started < 0 || expected.finishedMs() > started) throw conflict();
        if (lockDocument(expected.enterpriseId(), expected.spaceId(), expected.documentId()) == null
                || lockSource(expected, started) == null || !expected.equals(lockVerified(expected.id()))
                || !observed.equals(entries(expected.id()))) throw conflict();
        var fragments = fragments(expected.documentId(), expected.versionId());
        if (fragments.size() != observed.size()) throw conflict();
        var indexes = new HashSet<Integer>();
        for (int n = 0; n < fragments.size(); n++) {
            var row = fragments.get(n);
            if (row.chunkId() != observed.get(n).chunkId() || row.chunkIndex() < 0
                    || row.chunkIndex() >= fragments.size() || !indexes.add(row.chunkIndex())
                    || row.content() == null || row.content().isBlank() || row.content().length() > 1200
                    || !sha256(row.content()).equals(observed.get(n).contentSha256())) throw conflict();
        }
        Pointer current = lockPointer(expected.enterpriseId(), expected.spaceId(), expected.documentId());
        long now = time(clock, started, current);
        Pointer result = change(current, expected.enterpriseId(), expected.spaceId(), expected.documentId(),
                revision, expected.id(), now);
        // 有效期可能在等锁期间结束；最终再核来源，失败连同本次指针修改一起回滚。
        if (lockSource(expected, time(clock, started, result)) == null) throw conflict();
        return result;
    }

    /** 撤回不依赖旧任务或原文仍可读取；保留空指针墓碑，防止晚到的旧publish复活。 */
    default Pointer withdraw(long enterprise, long space, long document, long revision, String expectedJob, Clock clock) {
        validate(enterprise, space, document, revision, clock);
        if (expectedJob != null && !uuid(expectedJob)) throw invalid();
        transaction();
        long started = clock.millis();
        if (lockDocument(enterprise, space, document) == null) throw conflict();
        Pointer current = lockPointer(enterprise, space, document);
        long now = time(clock, started, current);
        if (current != null && current.revision() == revision + 1 && current.jobId() == null) return current;
        if ((current == null && (revision != 0 || expectedJob != null)) || (current != null
                && (current.revision() != revision || !Objects.equals(current.jobId(), expectedJob)))) throw conflict();
        Pointer result = write(current, enterprise, space, document, revision, null, now);
        time(clock, started, result);
        return result;
    }

    private Pointer change(Pointer current, long enterprise, long space, long document, long revision, String job, long now) {
        if (current != null && Objects.equals(current.jobId(), job)
                && (current.revision() == revision || current.revision() == revision + 1)) return current;
        if ((current == null && revision != 0) || (current != null && current.revision() != revision)) throw conflict();
        return write(current, enterprise, space, document, revision, job, now);
    }

    private Pointer write(Pointer current, long enterprise, long space, long document, long revision, String job, long now) {
        var next = new Pointer(enterprise, space, document, job, revision + 1, now);
        int changed = current == null ? insertPointer(next) : replacePointer(next, revision);
        if (changed != 1) throw conflict();
        return next;
    }

    /** 以下都是内部SQL原语，不能绕过维护权检查直接暴露给Controller。已删文档仍能撤回残留指针。 */
    @Select("SELECT id FROM rag_document WHERE id=#{document} AND enterprise_id=#{enterprise} "
            + "AND knowledge_space_id=#{space} FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockDocument(@Param("enterprise") long enterprise, @Param("space") long space, @Param("document") long document);

    @Select("""
            SELECT v.id FROM rag_document_version v JOIN rag_document d ON d.id=v.document_id
              AND d.enterprise_id=v.enterprise_id AND d.knowledge_space_id=v.knowledge_space_id
            WHERE d.id=#{job.documentId} AND d.enterprise_id=#{job.enterpriseId} AND d.knowledge_space_id=#{job.spaceId}
              AND d.deleted=0 AND d.owner_member_id IS NOT NULL AND d.revision=#{job.expectedRevision}
              AND d.published_version_id=v.id AND v.id=#{job.versionId} AND v.parse_status='READY'
              AND v.publication_status='PUBLISHED' AND v.content_sha256=#{job.sourceSha256}
              AND v.chunk_count=#{job.expectedChunks}
              AND (v.valid_from_ms IS NULL OR v.valid_from_ms<=#{now})
              AND (v.valid_until_ms IS NULL OR v.valid_until_ms>#{now}) FOR UPDATE
            """)
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockSource(@Param("job") Stored job, @Param("now") long now);

    @Select("SELECT * FROM rag_vector_job WHERE id=#{id} AND status='VERIFIED' FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Stored lockVerified(String id);

    @Select("SELECT chunk_id,content_sha256,vector_sha256 FROM rag_vector_manifest_entry "
            + "WHERE job_id=#{id} ORDER BY chunk_id LIMIT 1001 FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<QdrantVectorClient.Stored> entries(String id);

    @Select("SELECT id AS chunkId,chunk_index AS chunkIndex,content FROM rag_chunk "
            + "WHERE document_id=#{document} AND version_id=#{version} ORDER BY id LIMIT 1001 FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Fragment> fragments(@Param("document") long document, @Param("version") long version);

    @Select("SELECT enterprise_id,space_id,document_id,job_id,revision,updated_ms FROM rag_vector_publication WHERE document_id=#{document} "
            + "AND enterprise_id=#{enterprise} AND space_id=#{space} FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Pointer lockPointer(@Param("enterprise") long enterprise, @Param("space") long space, @Param("document") long document);

    @Insert("INSERT INTO rag_vector_publication(enterprise_id,space_id,document_id,job_id,revision,updated_ms) "
            + "VALUES(#{enterpriseId},#{spaceId},#{documentId},#{jobId},#{revision},#{updatedMs})")
    @Options(timeout = 2)
    int insertPointer(Pointer pointer);

    @Update("UPDATE rag_vector_publication SET job_id=#{next.jobId},revision=#{next.revision},updated_ms=#{next.updatedMs} "
            + "WHERE document_id=#{next.documentId} AND enterprise_id=#{next.enterpriseId} AND space_id=#{next.spaceId} "
            + "AND revision=#{revision}")
    @Options(timeout = 2)
    int replacePointer(@Param("next") Pointer next, @Param("revision") long revision);

    /**
     * 仅返回当前授权、当前有效业务版本的候选；绝不回退旧代次，也不证明Qdrant/模型此刻可用。
     * 返回值包含内部摘要/模型信息，后续服务只可内部使用，不能原样作为HTTP响应。
     * 只允许自动提交读取或明确READ_COMMITTED事务，避免MySQL旧快照继续看到已撤回的权限/代次。
     */
    default Stored candidate(EnterpriseMemberContext scope, long spaceId, long documentId, long now) {
        if (scope == null || spaceId <= 0 || documentId <= 0 || now < 0) throw invalid();
        candidateRead();
        return selectCandidate(scope, spaceId, documentId, now);
    }

    /**
     * 空间级混合检索准备入口，返回已授权且仍有效的当前索引任务，不包含片段原文。
     * 24份是本阶段跨文档查询的保护上限，不是Qdrant容量；多取一份探测超限，禁止静默漏查。
     * 结果仅供内部使用；调用外部向量服务后仍须重核代次、内容摘要和权限。
     */
    default List<Stored> candidates(EnterpriseMemberContext scope, long spaceId, long now) {
        if (scope == null || spaceId <= 0 || now < 0) throw invalid();
        candidateRead();
        List<Stored> result = selectCandidates(scope, spaceId, now);
        if (result.size() > 24)
            throw new BusinessException(ResultCode.SERVICE_BUSY, "当前空间可用索引超过24份文档，未截断检索，请联系管理员核对检索容量");
        return List.copyOf(result);
    }

    private static void candidateRead() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引候选须独立读取或使用读已提交事务，不能沿用旧权限快照");
    }

    /** 两个读取入口共享同一组权限、版本和完整性条件，避免空间列表放宽单文档查询的边界。 */
    String CURRENT_CANDIDATE_SQL = """
            SELECT j.* FROM rag_vector_publication p JOIN rag_vector_job j ON j.id=p.job_id
              AND j.enterprise_id=p.enterprise_id AND j.space_id=p.space_id AND j.document_id=p.document_id
            JOIN rag_document d ON d.id=p.document_id AND d.enterprise_id=p.enterprise_id AND d.knowledge_space_id=p.space_id
            JOIN rag_document_version v ON v.id=j.version_id AND v.document_id=d.id
              AND v.enterprise_id=d.enterprise_id AND v.knowledge_space_id=d.knowledge_space_id
            JOIN rag_knowledge_space s ON s.id=d.knowledge_space_id AND s.enterprise_id=d.enterprise_id
            """ + EnterpriseResourceMapper.ACTIVE_MEMBER + " WHERE s.id=#{spaceId} AND "
            + EnterpriseResourceMapper.SPACE_GRANT + """
              AND d.deleted=0 AND d.owner_member_id IS NOT NULL AND d.revision=j.expected_revision
              AND d.published_version_id=v.id AND v.publication_status='PUBLISHED' AND v.parse_status='READY'
              AND v.content_sha256=j.source_sha256 AND v.chunk_count=j.expected_chunks
              AND j.status='VERIFIED' AND j.manifest_sha256 IS NOT NULL AND j.verified_chunks=j.expected_chunks
              AND j.expected_chunks BETWEEN 1 AND 1000 AND j.finished_ms<=#{now} AND p.updated_ms<=#{now} AND #{now}>=0
              AND (v.valid_from_ms IS NULL OR v.valid_from_ms<=#{now})
              AND (v.valid_until_ms IS NULL OR v.valid_until_ms>#{now})
              AND j.expected_chunks=(SELECT COUNT(*) FROM rag_vector_manifest_entry x WHERE x.job_id=j.id)
              AND j.expected_chunks=(SELECT COUNT(*) FROM rag_chunk c WHERE c.document_id=d.id AND c.version_id=v.id)
              AND j.expected_chunks=(SELECT COUNT(*) FROM rag_vector_manifest_entry x JOIN rag_chunk c ON c.id=x.chunk_id
                WHERE x.job_id=j.id AND c.document_id=d.id AND c.version_id=v.id)
            """;

    /** 仅供candidate调用的内部SQL原语。 */
    @Select(CURRENT_CANDIDATE_SQL + " AND d.id=#{documentId}")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Stored selectCandidate(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("now") long now);

    /** 仅供candidates调用；排序用于稳定枚举，不作为语义相关性排序。 */
    @Select(CURRENT_CANDIDATE_SQL + " ORDER BY d.id ASC LIMIT 25")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Stored> selectCandidates(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("now") long now);

    private static void validate(long enterprise, long space, long document, long revision, Clock clock) {
        if (enterprise <= 0 || space <= 0 || document <= 0 || revision < 0 || revision == Long.MAX_VALUE || clock == null) throw invalid();
    }
    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引发布指针必须在调用方非只读短事务内修改");
    }
    private static long time(Clock clock, long started, Pointer current) {
        long now = clock.millis();
        if (Thread.currentThread().isInterrupted() || started < 0 || now < started || now - started >= 5000
                || (current != null && now < current.updatedMs()))
            throw new BusinessException(ResultCode.SERVICE_BUSY, "索引发布已中止或超时，请核对原指针，勿盲目重试");
        return now;
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前JDK不支持SHA-256"); }
    }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "索引发布范围、完整清单或预期修订不合法"); }
    private static BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "索引来源、任务、清单或发布指针已变化，请重新核对，未覆盖当前代次");
    }
}
