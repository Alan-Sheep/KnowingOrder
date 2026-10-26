package com.ragqa.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅内部任务仓储；HTTP入口必须先核身份、维护授权及任务归属，不直接暴露此Mapper。 */
@Mapper
public interface KnowledgeIngestJobMapper {
    record Stored(String id, long enterpriseId, long spaceId, long documentId, long versionId,
            long memberId, long userId, long authVersion, long expectedRevision, String action,
            String status, int attempts, String leaseToken, Long leaseUntilMs, long createdMs,
            long deadlineMs, Long finishedMs, String failureCode) { }

    @Select("SELECT id FROM rag_ingest_gate WHERE id=1 FOR UPDATE") Integer lockGate();
    @Select("SELECT * FROM rag_ingest_job WHERE id=#{id}") Stored find(String id);
    @Select("SELECT COUNT(*) FROM rag_ingest_job WHERE status IN ('QUEUED','RUNNING')") int activeTotal();
    @Select("SELECT COUNT(*) FROM rag_ingest_job WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND status IN ('QUEUED','RUNNING')")
    int activeOwner(@Param("enterprise") long enterprise, @Param("member") long member);
    @Select("SELECT COUNT(*) FROM rag_ingest_job WHERE document_id=#{document} AND version_id=#{version} AND status IN ('QUEUED','RUNNING')")
    int activeVersion(@Param("document") long document, @Param("version") long version);
    @Select("SELECT * FROM rag_ingest_job WHERE enterprise_id=#{enterprise} AND space_id=#{space} AND member_id=#{member} "
            + "AND document_id=#{document} AND version_id=#{version} ORDER BY created_ms DESC,id DESC LIMIT 10")
    List<Stored> history(@Param("enterprise") long enterprise, @Param("space") long space, @Param("member") long member,
            @Param("document") long document, @Param("version") long version);

    @Insert("""
            INSERT INTO rag_ingest_job(id,enterprise_id,space_id,document_id,version_id,member_id,user_id,
              auth_version,expected_revision,action,created_ms,deadline_ms)
            VALUES(#{id},#{enterpriseId},#{spaceId},#{documentId},#{versionId},#{memberId},#{userId},
              #{authVersion},#{expectedRevision},#{action},#{createdMs},#{deadlineMs})
            """) int insert(Stored job);

    @Select("""
            SELECT * FROM rag_ingest_job WHERE deadline_ms>#{now} AND attempts<2 AND
              (status='QUEUED' OR (status='RUNNING' AND lease_until_ms<=#{now}))
            ORDER BY created_ms,id LIMIT 16
            """) List<Stored> pending(long now);

    @Update("""
            UPDATE rag_ingest_job SET status='RUNNING',attempts=attempts+1,lease_token=#{token},
              lease_until_ms=LEAST(deadline_ms,#{until}) WHERE id=#{id} AND deadline_ms>#{now} AND attempts<2
              AND (status='QUEUED' OR (status='RUNNING' AND lease_until_ms<=#{now}))
            """) int claim(@Param("id") String id, @Param("token") String token, @Param("now") long now, @Param("until") long until);

    @Select("""
            SELECT COUNT(*) FROM rag_ingest_job WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}
              AND deadline_ms>#{now} AND lease_until_ms>#{now}
            """) int owns(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("""
            UPDATE rag_ingest_job SET status=#{status},failure_code=#{failure},finished_ms=#{now}
            WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND deadline_ms>#{now} AND lease_until_ms>#{now}
            """) int finish(@Param("id") String id, @Param("token") String token, @Param("status") String status,
                    @Param("failure") String failure, @Param("now") long now);

    @Update("UPDATE rag_ingest_job SET status='CANCELLED',finished_ms=#{now} WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int cancel(@Param("id") String id, @Param("now") long now);
    @Update("UPDATE rag_ingest_job SET status='TIMED_OUT',failure_code='TOTAL_TIMEOUT',finished_ms=#{now} "
            + "WHERE status IN ('QUEUED','RUNNING') AND deadline_ms<=#{now}") int expire(long now);
    @Update("UPDATE rag_ingest_job SET status='FAILED',failure_code='RECOVERY_EXHAUSTED',finished_ms=#{now} "
            + "WHERE status='RUNNING' AND attempts>=2 AND lease_until_ms<=#{now}") int exhaust(long now);
}
