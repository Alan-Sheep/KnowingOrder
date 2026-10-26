package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 权限条件在SQL中先于返回应用；维护者同样只能用当前发布、生效的版本回答。 */
@Mapper
public interface KnowledgeRetrievalMapper {
    String COLUMNS = "c.id AS chunkId,d.id AS documentId,v.id AS versionId,v.version_no AS versionNo,"
            + "d.title,c.chunk_index AS chunkIndex,c.content,c.start_offset AS startOffset,"
            + "c.end_offset AS endOffset,c.start_line AS startLine,c.end_line AS endLine";
    String LIVE_SCOPE = """
            FROM rag_chunk c JOIN rag_document_version v ON v.id=c.version_id AND v.document_id=c.document_id
            JOIN rag_document d ON d.id=v.document_id AND d.enterprise_id=v.enterprise_id
              AND d.knowledge_space_id=v.knowledge_space_id
            JOIN rag_knowledge_space s ON s.id=d.knowledge_space_id AND s.enterprise_id=d.enterprise_id
            """ + EnterpriseResourceMapper.ACTIVE_MEMBER + " WHERE s.id=#{spaceId} AND d.deleted=0 "
            + "AND d.owner_member_id IS NOT NULL AND c.start_offset IS NOT NULL AND c.end_offset>c.start_offset "
            + "AND c.start_line>0 AND c.end_line>=c.start_line AND " + EnterpriseResourceMapper.SPACE_GRANT
            + " AND v.id=d.published_version_id AND v.publication_status='PUBLISHED' AND v.parse_status='READY' "
            + "AND (v.valid_from_ms IS NULL OR #{now} >= v.valid_from_ms) "
            + "AND (v.valid_until_ms IS NULL OR v.valid_until_ms > #{now})";

    // LOCATE把%、_及引号视为普通内容，不使用LIKE通配符或${}拼接用户输入。
    @Select("<script>SELECT " + COLUMNS + " " + LIVE_SCOPE
            + " AND (<foreach collection='terms' item='term' separator=' OR '>"
            + "LOCATE(#{term},LOWER(c.content))>0</foreach>) ORDER BY ("
            + "<foreach collection='terms' item='term' separator='+'>"
            + "CASE WHEN LOCATE(#{term},LOWER(c.content))>0 THEN 1 ELSE 0 END</foreach>"
            + ") DESC,c.id ASC LIMIT 24</script>")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Evidence> search(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("now") long now, @Param("terms") List<String> terms);

    @Select("SELECT " + COLUMNS + " " + LIVE_SCOPE + " AND c.id=#{chunkId}")
    @Options(timeout = 2, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Evidence evidence(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("now") long now, @Param("chunkId") long chunkId);
}
