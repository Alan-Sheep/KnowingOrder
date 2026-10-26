package com.ragqa.mapper;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.retrieval.QdrantVectorClient;
import com.ragqa.rag.retrieval.VectorIndexManifest;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 内部完整预期清单仓储，不保存原文或向量，不产生资源授权、任务完成或READY发布。
 * persist必须由调用方以同数据源短事务包围，异常须传播以回滚；锁顺序为任务行再清单行。
 */
@Mapper
public interface KnowledgeVectorManifestMapper {
    /** 新存返回1，相同清单重传返回0；仅持有当前活租约的执行者可调用。 */
    default int persist(String id, String token, VectorIndexManifest manifest, Clock clock) {
        if (!uuid(id) || !uuid(token) || manifest == null || clock == null)
            throw new BusinessException(ResultCode.PARAM_ERROR, "索引清单请求、租约或时钟不合法");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new BusinessException(ResultCode.SERVICE_BUSY, "完整索引清单必须在调用方独立短事务内保存");
        long started = clock.millis();
        Stored job = lock(id, token, started);
        if (!matches(job, manifest)) throw conflict();
        List<QdrantVectorClient.Stored> previous = rows(id);
        if (job.manifestSha256() != null) {
            if (!job.manifestSha256().equals(manifest.sha256()) || !previous.equals(manifest.expected())) throw conflict();
            long checked = clock.millis();
            if (checked < started || checked >= job.deadlineMs() || checked >= job.leaseUntilMs()) throw conflict();
            return 0;
        }
        // 不接管旧的部分清单，也不清空重建；未知写入须先核对原任务。
        if (!previous.isEmpty() || insertRows(id, manifest.expected()) != manifest.expected().size()) throw conflict();
        if (!rows(id).equals(manifest.expected())) throw conflict();
        long finished = clock.millis();
        if (finished < started || seal(id, token, manifest.sha256(), manifest.expected().size(), finished) != 1) throw conflict();
        return 1;
    }

    /** 以下SQL仅供内部事务编排；不能直接作为Controller或资源授权入口。 */
    @Select("SELECT * FROM rag_vector_job WHERE " + KnowledgeVectorJobMapper.LIVE_LEASE + " FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Stored lock(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    // 当前读避开MySQL可重复读旧快照；多读一项用于发现超出1000的损坏记录，绝不静默接受截断。
    @Select("SELECT chunk_id,content_sha256,vector_sha256 FROM rag_vector_manifest_entry "
            + "WHERE job_id=#{id} ORDER BY chunk_id LIMIT 1001 FOR UPDATE")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<QdrantVectorClient.Stored> rows(String id);

    @Insert("""
            <script>
            INSERT INTO rag_vector_manifest_entry(job_id,chunk_id,content_sha256,vector_sha256) VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{id},#{row.chunkId},#{row.contentSha256},#{row.vectorSha256})
            </foreach>
            </script>
            """)
    @Options(timeout = 2)
    int insertRows(@Param("id") String id, @Param("rows") List<QdrantVectorClient.Stored> rows);

    @Update("""
            UPDATE rag_vector_job SET manifest_sha256=#{manifest}
            WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}
              AND created_ms<=#{now} AND deadline_ms>#{now} AND lease_until_ms>#{now}
              AND manifest_sha256 IS NULL AND expected_chunks=#{count}
              AND expected_chunks=(SELECT COUNT(*) FROM rag_vector_manifest_entry WHERE job_id=#{id})
            """)
    @Options(timeout = 2)
    int seal(@Param("id") String id, @Param("token") String token, @Param("manifest") String manifest,
            @Param("count") int count, @Param("now") long now);

    private static boolean matches(Stored job, VectorIndexManifest manifest) {
        if (job == null) return false;
        var scope = manifest.scope();
        var index = manifest.index();
        return job.enterpriseId() == scope.enterpriseId() && job.spaceId() == scope.spaceId()
                && job.documentId() == scope.documentId() && job.versionId() == scope.versionId()
                && job.generation().equals(scope.generation()) && job.expectedRevision() == manifest.documentRevision()
                && job.sourceSha256().equals(manifest.sourceSha256()) && job.expectedChunks() == manifest.expected().size()
                && job.collectionName().equals(index.collection()) && job.modelName().equals(index.model())
                && job.modelRevision().equals(index.revision()) && job.dimensions() == index.dimensions();
    }

    private static boolean uuid(String value) {
        try { return value != null && Objects.equals(UUID.fromString(value).toString(), value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "索引任务、租约或完整清单已变化，未确认保存；请先核对原任务");
    }
}
