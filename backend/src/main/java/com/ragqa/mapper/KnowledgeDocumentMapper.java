package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeDocuments;
import com.ragqa.domain.entity.RagDocument;
import com.ragqa.rag.ingest.Utf8TextParser;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 版本/片段必须同时限定企业、空间与逻辑文档；不向业务暴露无范围BaseMapper。 */
@Mapper
public interface KnowledgeDocumentMapper {
    String DOCUMENT_COLUMNS = "d.id,d.knowledge_space_id AS knowledgeSpaceId,d.title,d.owner_member_id AS ownerMemberId,"
            + "d.published_version_id AS publishedVersionId,d.revision";
    // 只读查询补充负责人中文姓名；昵称为空才回退登录账号。写入锁定查询不联表，避免无关行被 FOR UPDATE 一并锁定。
    String OWNER_NAME = ",COALESCE(NULLIF(TRIM(ou.nickname),''),ou.username) AS ownerMemberName";
    String OWNER_JOIN = " LEFT JOIN sys_enterprise_member om ON om.id = d.owner_member_id AND om.enterprise_id = d.enterprise_id"
            + " LEFT JOIN sys_user ou ON ou.id = om.user_id ";
    String LIVE = """
            v.id = d.published_version_id AND v.publication_status = 'PUBLISHED' AND v.parse_status = 'READY'
            AND (v.valid_from_ms IS NULL OR v.valid_from_ms <= #{now})
            AND (v.valid_until_ms IS NULL OR v.valid_until_ms > #{now})
            """;
    String VERSION_ACCESS = """
            FROM rag_document_version v JOIN rag_document d ON d.id = v.document_id
              AND d.enterprise_id = v.enterprise_id AND d.knowledge_space_id = v.knowledge_space_id
            JOIN rag_knowledge_space s ON s.id = d.knowledge_space_id AND s.enterprise_id = d.enterprise_id
            """ + EnterpriseResourceMapper.ACTIVE_MEMBER + " WHERE d.id = #{documentId} AND s.id = #{spaceId} "
            + "AND d.deleted = 0 AND v.publication_status <> 'DELETED' AND " + EnterpriseResourceMapper.SPACE_GRANT
            + " AND ((" + KnowledgeManagementMapper.MAINTAIN + ") OR (" + LIVE + "))";

    @Select("SELECT " + DOCUMENT_COLUMNS + ",NULL AS ownerMemberName FROM rag_document d WHERE d.id = #{documentId} "
            + "AND d.knowledge_space_id = #{spaceId} AND d.enterprise_id = #{scope.enterpriseId} AND d.deleted = 0 FOR UPDATE")
    KnowledgeDocuments.Document lockDocument(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId, @Param("documentId") long documentId);

    @Select("SELECT " + DOCUMENT_COLUMNS + OWNER_NAME + " FROM rag_document d " + OWNER_JOIN
            + " JOIN rag_knowledge_space s "
            + "ON s.id=d.knowledge_space_id AND s.enterprise_id=d.enterprise_id " + EnterpriseResourceMapper.ACTIVE_MEMBER
            + " WHERE d.id=#{documentId} AND s.id=#{spaceId} AND d.deleted=0 AND d.owner_member_id IS NOT NULL AND "
            + EnterpriseResourceMapper.SPACE_GRANT + " AND ((" + KnowledgeManagementMapper.MAINTAIN
            + ") OR EXISTS(SELECT 1 FROM rag_document_version v WHERE v.document_id=d.id "
            + "AND v.enterprise_id=d.enterprise_id AND v.knowledge_space_id=s.id AND " + LIVE + "))")
    KnowledgeDocuments.Document document(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId, @Param("documentId") long documentId, @Param("now") long now);

    @Select("SELECT COUNT(*) FROM rag_chunk WHERE document_id=#{documentId} AND version_id=#{versionId}")
    int chunkCount(@Param("documentId") long documentId, @Param("versionId") long versionId);

    @Insert("""
            INSERT INTO rag_document(title,source_type,status,chunk_count,created_by,enterprise_id,knowledge_space_id,owner_member_id)
            VALUES(#{document.title},'UPLOAD',0,0,#{scope.userId},#{scope.enterpriseId},#{spaceId},#{scope.memberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "document.id")
    int insertDocument(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("document") RagDocument document);

    @Select("""
            SELECT * FROM rag_document_version WHERE enterprise_id = #{scope.enterpriseId}
              AND knowledge_space_id = #{spaceId} AND upload_request_id = #{requestId}
            """)
    KnowledgeDocuments.StoredVersion findRequest(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId, @Param("requestId") String requestId);

    @Select("""
            SELECT COALESCE(MAX(version_no),0) FROM rag_document_version
            WHERE enterprise_id = #{scope.enterpriseId} AND knowledge_space_id = #{spaceId} AND document_id = #{documentId}
            """)
    int lastVersion(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId);

    @Insert("""
            INSERT INTO rag_document_version(enterprise_id,knowledge_space_id,document_id,version_no,upload_request_id,
              original_name,storage_key,content_sha256,request_sha256,file_size,valid_from_ms,valid_until_ms,created_by_member)
            VALUES(#{scope.enterpriseId},#{spaceId},#{version.documentId},#{version.versionNo},#{version.uploadRequestId},
              #{version.originalName},#{version.storageKey},#{version.contentSha256},#{version.requestSha256},
              #{version.fileSize},#{version.validFromMs},#{version.validUntilMs},#{scope.memberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "version.id")
    int insertVersion(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("version") KnowledgeDocuments.StoredVersion version);

    @Select("SELECT v.* " + VERSION_ACCESS + " AND v.id = #{versionId}")
    KnowledgeDocuments.StoredVersion readVersion(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId, @Param("documentId") long documentId,
            @Param("versionId") long versionId, @Param("now") long now);

    @Select("SELECT v.* " + VERSION_ACCESS + " ORDER BY v.version_no DESC")
    IPage<KnowledgeDocuments.StoredVersion> versions(IPage<KnowledgeDocuments.StoredVersion> page,
            @Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("now") long now);

    @Select("SELECT " + DOCUMENT_COLUMNS + OWNER_NAME + " FROM rag_document d " + OWNER_JOIN
            + " JOIN rag_knowledge_space s "
            + "ON s.id = d.knowledge_space_id AND s.enterprise_id = d.enterprise_id " + EnterpriseResourceMapper.ACTIVE_MEMBER
            + " WHERE s.id = #{spaceId} AND d.deleted = 0 AND d.owner_member_id IS NOT NULL AND "
            + EnterpriseResourceMapper.SPACE_GRANT + " AND ((" + KnowledgeManagementMapper.MAINTAIN
            + ") OR EXISTS(SELECT 1 FROM rag_document_version v WHERE v.document_id = d.id "
            + "AND v.enterprise_id = d.enterprise_id AND v.knowledge_space_id = s.id AND " + LIVE + ")) ORDER BY d.id")
    IPage<KnowledgeDocuments.Document> documents(IPage<KnowledgeDocuments.Document> page,
            @Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId, @Param("now") long now);

    @Select("SELECT c.id,c.chunk_index AS chunkIndex,c.content,c.start_offset AS startOffset,c.end_offset AS endOffset,"
            + "c.start_line AS startLine,c.end_line AS endLine FROM rag_chunk c JOIN rag_document_version v "
            + "ON v.id=c.version_id AND v.document_id=c.document_id JOIN rag_document d ON d.id=v.document_id "
            + "AND d.enterprise_id=v.enterprise_id AND d.knowledge_space_id=v.knowledge_space_id "
            + "JOIN rag_knowledge_space s ON s.id=d.knowledge_space_id AND s.enterprise_id=d.enterprise_id "
            + EnterpriseResourceMapper.ACTIVE_MEMBER + " WHERE v.id=#{versionId} AND d.id=#{documentId} AND s.id=#{spaceId} "
            + "AND d.deleted=0 AND v.parse_status='READY' AND v.publication_status<>'DELETED' AND "
            + EnterpriseResourceMapper.SPACE_GRANT + " AND ((" + KnowledgeManagementMapper.MAINTAIN + ") OR (" + LIVE
            + ")) ORDER BY c.chunk_index")
    IPage<KnowledgeDocuments.Chunk> chunks(IPage<KnowledgeDocuments.Chunk> page,
            @Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("versionId") long versionId, @Param("now") long now);

    @Delete("DELETE FROM rag_chunk WHERE document_id=#{documentId} AND version_id=#{versionId} "
            + "AND EXISTS(SELECT 1 FROM rag_document d WHERE d.id=#{documentId} "
            + "AND d.enterprise_id=#{scope.enterpriseId} AND d.knowledge_space_id=#{spaceId})")
    int clearChunks(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("versionId") long versionId);

    @Insert("""
            <script>INSERT INTO rag_chunk(document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
            VALUES <foreach collection="parts" item="part" separator=",">
            (#{documentId},#{versionId},#{part.index},#{part.content},#{part.start},#{part.end},#{part.startLine},#{part.endLine})
            </foreach></script>
            """)
    int insertChunks(@Param("documentId") long documentId, @Param("versionId") long versionId,
            @Param("parts") List<Utf8TextParser.Part> parts);

    @Update("""
            UPDATE rag_document_version SET parse_status=#{state},chunk_count=#{count},failure_code=#{failure}
            WHERE id=#{versionId} AND document_id=#{documentId} AND enterprise_id=#{scope.enterpriseId}
              AND knowledge_space_id=#{spaceId} AND publication_status='DRAFT'
            """)
    int parsed(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("versionId") long versionId,
            @Param("state") String state, @Param("count") int count, @Param("failure") String failure);

    @Update("UPDATE rag_document SET revision=revision+1 WHERE id=#{documentId} AND enterprise_id=#{scope.enterpriseId} "
            + "AND knowledge_space_id=#{spaceId} AND revision=#{revision} AND deleted=0")
    int advance(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("revision") long revision);

    @Update("UPDATE rag_document_version SET publication_status=#{state} WHERE id=#{versionId} AND document_id=#{documentId} "
            + "AND enterprise_id=#{scope.enterpriseId} AND knowledge_space_id=#{spaceId}")
    int publication(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("versionId") long versionId, @Param("state") String state);

    @Update("UPDATE rag_document SET published_version_id=#{versionId},revision=revision+1,status=#{status},chunk_count=#{count} "
            + "WHERE id=#{documentId} AND enterprise_id=#{scope.enterpriseId} AND knowledge_space_id=#{spaceId} AND deleted=0 AND revision=#{revision}")
    int point(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("versionId") Long versionId,
            @Param("revision") long revision, @Param("status") int status, @Param("count") int count);

    @Update("UPDATE rag_document SET owner_member_id=#{owner},revision=revision+1 WHERE id=#{documentId} "
            + "AND enterprise_id=#{scope.enterpriseId} AND knowledge_space_id=#{spaceId} AND revision=#{revision} AND deleted=0")
    int owner(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("revision") long revision, @Param("owner") long owner);

    @Update("UPDATE rag_document SET deleted=1,published_version_id=NULL,revision=revision+1 WHERE id=#{documentId} "
            + "AND enterprise_id=#{scope.enterpriseId} AND knowledge_space_id=#{spaceId} AND revision=#{revision} AND deleted=0")
    int remove(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("documentId") long documentId, @Param("revision") long revision);
}
