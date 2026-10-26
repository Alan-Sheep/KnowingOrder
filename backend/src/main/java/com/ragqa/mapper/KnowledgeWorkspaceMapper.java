package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 会话私有读取始终同时绑定企业、成员、账号及空间；后台调度只读最小受理身份。 */
@Mapper
public interface KnowledgeWorkspaceMapper {
    record Chat(String id, String title, long createdMs) { }
    record ContextQuestion(String id, String question, long createdMs) {
        @Override public String toString() { return "ContextQuestion[id=" + id + ", question=<正文不记录>]"; }
    }
    record ContextSnapshot(String contextJson, String snapshotId) {
        @Override public String toString() { return "ContextSnapshot[正文不记录]"; }
    }
    record StoredJob(String id, String chatId, long enterpriseId, long memberId, long userId, long spaceId,
            long authVersion, String question, String status, long createdMs, long deadlineMs,
            Long finishedMs, String answerJson, Integer errorCode) { }
    record Feedback(String id, String jobId, Long chunkId, String category, String commentText, String status,
            String resolution, Long resolvedBy, long revision, long createdMs, String question) { }

    String OWNER = "c.enterprise_id=#{scope.enterpriseId} AND c.member_id=#{scope.memberId} "
            + "AND c.user_id=#{scope.userId} AND c.space_id=#{spaceId}";
    // MyBatis的record构造映射按列序读取，必须与StoredJob参数顺序完全一致，禁止j.*。
    String JOB_COLUMNS = "j.id,j.chat_id,c.enterprise_id,c.member_id,c.user_id,c.space_id,j.auth_version,"
            + "j.question,j.status,j.created_ms,j.deadline_ms,j.finished_ms,j.answer_json,j.error_code";
    String FEEDBACK_QUERY = "SELECT f.id,f.job_id,f.chunk_id,f.category,f.comment_text,f.status,f.resolution,"
            + "f.resolved_by,f.revision,f.created_ms,j.question FROM rag_knowledge_feedback f "
            + "JOIN rag_knowledge_job j ON j.id=f.job_id JOIN rag_knowledge_chat c ON c.id=j.chat_id "
            + "AND c.enterprise_id=f.enterprise_id AND c.space_id=f.space_id AND c.member_id=f.member_id ";

    @Select("SELECT id FROM sys_enterprise_member WHERE id=#{scope.memberId} AND enterprise_id=#{scope.enterpriseId} FOR UPDATE")
    Long lockOwner(@Param("scope") EnterpriseMemberContext scope);

    @Select("SELECT c.id,c.title,c.created_ms FROM rag_knowledge_chat c WHERE " + OWNER
            + " AND c.deleted_ms IS NULL ORDER BY c.created_ms DESC,c.id LIMIT 50")
    List<Chat> chats(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId);

    /**
     * 本人删除会话：只打删除时刻，保留任务、快照与反馈的审计链路。
     * 条件写死 OWNER，重复删除返回 0 行由服务层当作幂等处理。
     */
    @Update("UPDATE rag_knowledge_chat c SET c.deleted_ms=#{now} WHERE " + OWNER
            + " AND c.id=#{chatId} AND c.deleted_ms IS NULL")
    int softDeleteChat(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("chatId") String chatId, @Param("now") long now);

    @Select("SELECT c.id,c.title,c.created_ms FROM rag_knowledge_chat c WHERE " + OWNER + " AND c.id=#{chatId}")
    Chat chat(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("chatId") String chatId);

    @Insert("INSERT INTO rag_knowledge_chat(id,enterprise_id,member_id,user_id,space_id,title,created_ms) "
            + "VALUES(#{id},#{scope.enterpriseId},#{scope.memberId},#{scope.userId},#{spaceId},#{title},#{now})")
    int createChat(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("id") String id, @Param("title") String title, @Param("now") long now);

    @Select("SELECT " + JOB_COLUMNS + " FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id "
            + "WHERE " + OWNER + " AND j.id=#{id}")
    StoredJob job(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("id") String id);

    @Select("SELECT " + JOB_COLUMNS + " FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id "
            + "WHERE " + OWNER + " AND c.id=#{chatId} ORDER BY j.created_ms DESC,j.id DESC LIMIT 50")
    List<StoredJob> history(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("chatId") String chatId);

    // 只读取原问题；同毫秒不推断先后，不读取旧答案/原文，也不把后发问题带入先发任务。
    @Select("SELECT j.id,j.question,j.created_ms FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id "
            + "JOIN rag_knowledge_job a ON a.id=#{requestId} AND a.chat_id=c.id WHERE " + OWNER
            + " AND j.created_ms<a.created_ms AND j.status IN ('PENDING','RUNNING','COMPLETED') "
            + "ORDER BY j.created_ms DESC,j.id DESC LIMIT 4")
    @Options(timeout = 2, useCache = false)
    List<ContextQuestion> contextQuestions(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId, @Param("requestId") String requestId);

    @Insert("INSERT INTO rag_knowledge_context(job_id,context_json,snapshot_id) SELECT j.id,#{body},#{snapshotId} "
            + "FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id WHERE " + OWNER
            + " AND j.id=#{id} AND j.status='PENDING'")
    @Options(timeout = 2)
    int saveContext(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("id") String id, @Param("body") String body, @Param("snapshotId") String snapshotId);

    @Select("SELECT x.context_json,x.snapshot_id FROM rag_knowledge_context x JOIN rag_knowledge_job j ON j.id=x.job_id "
            + "JOIN rag_knowledge_chat c ON c.id=j.chat_id WHERE " + OWNER + " AND j.id=#{id}")
    @Options(timeout = 2, useCache = false)
    ContextSnapshot context(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("id") String id);

    @Select("SELECT COUNT(*) FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id WHERE "
            + "c.enterprise_id=#{scope.enterpriseId} AND c.member_id=#{scope.memberId} AND j.status IN ('PENDING','RUNNING')")
    int activeForOwner(@Param("scope") EnterpriseMemberContext scope);

    @Select("SELECT COUNT(*) FROM rag_knowledge_job WHERE status IN ('PENDING','RUNNING')")
    int activeTotal();

    @Insert("INSERT INTO rag_knowledge_job(id,chat_id,auth_version,question,status,created_ms,deadline_ms) "
            + "VALUES(#{id},#{chatId},#{authVersion},#{question},'PENDING',#{now},#{deadline})")
    int createJob(@Param("id") String id, @Param("chatId") String chatId, @Param("authVersion") long authVersion,
            @Param("question") String question, @Param("now") long now, @Param("deadline") long deadline);

    @Select("SELECT " + JOB_COLUMNS + " FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id=j.chat_id "
            + "WHERE j.status='PENDING' AND j.deadline_ms>#{now} ORDER BY j.created_ms,j.id LIMIT 2")
    @Options(timeout = 2, useCache = false)
    List<StoredJob> pending(@Param("now") long now);

    @Update("UPDATE rag_knowledge_job SET status='RUNNING' WHERE id=#{id} AND status='PENDING' AND deadline_ms>#{now}")
    int claim(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE rag_knowledge_job SET status=#{status},answer_json=#{answer},error_code=#{code},finished_ms=#{now} "
            + "WHERE id=#{id} AND status='RUNNING' AND deadline_ms>#{now}")
    int finish(@Param("id") String id, @Param("status") String status, @Param("answer") String answer,
            @Param("code") Integer code, @Param("now") long now);

    @Update("UPDATE rag_knowledge_job SET status='TIMED_OUT',finished_ms=#{now},error_code=504 "
            + "WHERE status IN ('PENDING','RUNNING') AND deadline_ms<=#{now}")
    int expire(@Param("now") long now);

    @Update("UPDATE rag_knowledge_job SET status='CANCELLED',finished_ms=#{now},answer_json=NULL "
            + "WHERE id=#{id} AND status IN ('PENDING','RUNNING')")
    int cancel(@Param("id") String id, @Param("now") long now);

    @Select(FEEDBACK_QUERY + "WHERE f.enterprise_id=#{scope.enterpriseId} AND f.space_id=#{spaceId} "
            + "ORDER BY f.created_ms DESC,f.id LIMIT 100")
    List<Feedback> feedbacks(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId);

    @Select(FEEDBACK_QUERY + "WHERE f.id=#{id} AND f.enterprise_id=#{scope.enterpriseId} "
            + "AND f.space_id=#{spaceId} AND f.member_id=#{scope.memberId}")
    Feedback ownFeedback(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("id") String id);

    @Insert("""
            INSERT INTO rag_knowledge_feedback(id,job_id,enterprise_id,space_id,member_id,chunk_id,category,comment_text,created_ms)
            VALUES(#{id},#{jobId},#{scope.enterpriseId},#{spaceId},#{scope.memberId},#{chunkId},#{category},#{comment},#{now})
            """)
    int feedback(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("id") String id,
            @Param("jobId") String jobId, @Param("chunkId") Long chunkId, @Param("category") String category,
            @Param("comment") String comment, @Param("now") long now);

    @Update("UPDATE rag_knowledge_feedback SET status=#{status},resolution=#{resolution},resolved_by=#{scope.memberId},revision=revision+1 "
            + "WHERE id=#{id} AND enterprise_id=#{scope.enterpriseId} AND space_id=#{spaceId} "
            + "AND status='OPEN' AND revision=#{revision}")
    int resolve(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("id") String id,
            @Param("revision") long revision, @Param("status") String status, @Param("resolution") String resolution);
}
