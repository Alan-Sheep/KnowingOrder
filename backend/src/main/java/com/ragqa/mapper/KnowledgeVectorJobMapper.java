package com.ragqa.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 仅内部索引任务仓储，不产生授权或READY发布；后续服务必须先核身份、维护权、版本及任务归属。
 * 只抢占QUEUED，不重新执行失租RUNNING，避免未知外部写入在同代次被自动重放。
 */
@Mapper
public interface KnowledgeVectorJobMapper {
    record Stored(String id, String generation, long enterpriseId, long spaceId, long documentId, long versionId,
            long memberId, long userId, long authVersion, long expectedRevision, String requestSha256, String sourceSha256,
            String collectionName, String modelName, String modelRevision, int dimensions, int expectedChunks,
            String status, String leaseToken, Long leaseUntilMs, String manifestSha256, int verifiedChunks,
            long createdMs, long deadlineMs, Long finishedMs, String failureCode) {
        @Override public String toString() { return "VectorJob[status=" + status + "]"; }
    }

    String LIVE_LEASE = "id=#{id} AND status='RUNNING' AND lease_token=#{token} "
            + "AND created_ms<=#{now} AND deadline_ms>#{now} AND lease_until_ms>#{now}";

    @Select("SELECT id FROM rag_vector_gate WHERE id=1 FOR UPDATE")
    @Options(timeout = 2, useCache = false)
    Integer lockGate();

    @Select("SELECT * FROM rag_vector_job WHERE id=#{id}")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Stored find(String id);

    @Select("SELECT COUNT(*) FROM rag_vector_job WHERE status IN ('QUEUED','RUNNING')")
    @Options(timeout = 2, useCache = false)
    int activeTotal();

    @Select("SELECT COUNT(*) FROM rag_vector_job WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND status IN ('QUEUED','RUNNING')")
    @Options(timeout = 2, useCache = false)
    int activeOwner(@Param("enterprise") long enterprise, @Param("member") long member);

    @Select("SELECT COUNT(*) FROM rag_vector_job WHERE enterprise_id=#{enterprise} AND space_id=#{space} "
            + "AND document_id=#{document} AND version_id=#{version} AND status IN ('QUEUED','RUNNING')")
    @Options(timeout = 2, useCache = false)
    int activeVersion(@Param("enterprise") long enterprise, @Param("space") long space,
            @Param("document") long document, @Param("version") long version);

    @Insert("""
            INSERT INTO rag_vector_job(id,generation,enterprise_id,space_id,document_id,version_id,member_id,user_id,
              auth_version,expected_revision,request_sha256,source_sha256,collection_name,model_name,model_revision,
              dimensions,expected_chunks,created_ms,deadline_ms)
            VALUES(#{id},#{generation},#{enterpriseId},#{spaceId},#{documentId},#{versionId},#{memberId},#{userId},
              #{authVersion},#{expectedRevision},#{requestSha256},#{sourceSha256},#{collectionName},#{modelName},#{modelRevision},
              #{dimensions},#{expectedChunks},#{createdMs},#{deadlineMs})
            """)
    @Options(timeout = 2)
    int insert(Stored job);

    @Select("SELECT * FROM rag_vector_job WHERE status='QUEUED' AND created_ms<=#{now} AND deadline_ms>#{now} ORDER BY created_ms,id LIMIT 16")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Stored> pending(long now);

    @Update("""
            UPDATE rag_vector_job SET status='RUNNING',lease_token=#{token},lease_until_ms=LEAST(deadline_ms,#{until})
            WHERE id=#{id} AND status='QUEUED' AND created_ms<=#{now} AND deadline_ms>#{now}
              AND #{until}>#{now} AND #{token} IS NOT NULL AND LENGTH(#{token})=36
            """)
    @Options(timeout = 2)
    int claim(@Param("id") String id, @Param("token") String token, @Param("now") long now, @Param("until") long until);

    @Select("SELECT COUNT(*) FROM rag_vector_job WHERE " + LIVE_LEASE)
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int owns(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("UPDATE rag_vector_job SET manifest_sha256=#{manifest} WHERE " + LIVE_LEASE
            + " AND (manifest_sha256 IS NULL OR manifest_sha256=#{manifest}) "
            + "AND #{manifest} IS NOT NULL AND LENGTH(#{manifest})=64 AND expected_chunks=#{count}")
    @Options(timeout = 2)
    int bindManifest(@Param("id") String id, @Param("token") String token, @Param("manifest") String manifest,
            @Param("count") int count, @Param("now") long now);

    @Update("UPDATE rag_vector_job SET status='VERIFIED',verified_chunks=expected_chunks,finished_ms=#{now} WHERE "
            + LIVE_LEASE + " AND manifest_sha256=#{manifest} AND expected_chunks=#{count}")
    @Options(timeout = 2)
    int verified(@Param("id") String id, @Param("token") String token, @Param("manifest") String manifest,
            @Param("count") int count, @Param("now") long now);

    @Update("UPDATE rag_vector_job SET status='FAILED',failure_code=#{failure},finished_ms=#{now} WHERE "
            + LIVE_LEASE + " AND #{failure} IN ('ACCESS_REVOKED','REVISION_CONFLICT','MODEL_CHANGED','MANIFEST_MISMATCH',"
            + "'WRITE_UNCONFIRMED','VECTOR_UNAVAILABLE','INTERNAL_ERROR')")
    @Options(timeout = 2)
    int fail(@Param("id") String id, @Param("token") String token, @Param("failure") String failure, @Param("now") long now);

    @Update("UPDATE rag_vector_job SET status='CANCELLED',finished_ms=#{now} WHERE id=#{id} "
            + "AND status IN ('QUEUED','RUNNING') AND created_ms<=#{now} AND deadline_ms>#{now}")
    @Options(timeout = 2)
    int cancel(@Param("id") String id, @Param("now") long now);

    @Update("""
            UPDATE rag_vector_job SET status='TIMED_OUT',failure_code='TOTAL_TIMEOUT',finished_ms=#{now}
            WHERE id IN (SELECT id FROM (SELECT id FROM rag_vector_job
              WHERE status IN ('QUEUED','RUNNING') AND deadline_ms<=#{now}
              ORDER BY deadline_ms,id LIMIT 64) expired_jobs)
              AND status IN ('QUEUED','RUNNING') AND deadline_ms<=#{now}
            """)
    @Options(timeout = 2)
    int expire(long now);

    @Update("""
            UPDATE rag_vector_job SET status='FAILED',failure_code='LEASE_LOST',finished_ms=#{now}
            WHERE id IN (SELECT id FROM (SELECT id FROM rag_vector_job
              WHERE status='RUNNING' AND lease_until_ms<=#{now} AND deadline_ms>#{now}
              ORDER BY lease_until_ms,id LIMIT 64) lost_jobs)
              AND status='RUNNING' AND lease_until_ms<=#{now} AND deadline_ms>#{now}
            """)
    @Options(timeout = 2)
    int abandon(long now);
}
