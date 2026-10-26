package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 不暴露无范围的主键读取；全部敏感查询在同一SQL内复核身份、范围和当前权限。 */
@Mapper
public interface EnterpriseResourceMapper {
    // 只能拼接编译期常量；所有请求数据使用绑定参数，禁止插入客户端SQL片段。
    String ACTIVE_MEMBER = """
            JOIN sys_enterprise_member m ON m.id = #{scope.memberId}
                AND m.enterprise_id = #{scope.enterpriseId} AND m.user_id = #{scope.userId}
                AND m.status = 0 AND m.role = #{scope.role}
                AND m.permission_version = #{scope.memberPermissionVersion}
            JOIN sys_enterprise e ON e.id = m.enterprise_id AND e.status = 0
                AND e.permission_version = #{scope.enterprisePermissionVersion}
            JOIN sys_user u ON u.id = m.user_id AND u.status = 0 AND u.deleted = 0
                AND u.auth_version = #{scope.userAuthVersion}
            """;

    String OWNED_CONVERSATION = """
            c.enterprise_id = m.enterprise_id AND c.member_id = m.id
            AND c.user_id = m.user_id AND c.guest_id IS NULL AND c.deleted = 0
            """;

    String SPACE_GRANT = """
            s.enterprise_id = m.enterprise_id AND s.status = 0
            AND EXISTS (
                SELECT 1 FROM rag_knowledge_grant g
                WHERE g.enterprise_id = s.enterprise_id AND g.knowledge_space_id = s.id
                  AND g.status = 0 AND g.permission IN ('READ', 'MAINTAIN')
                  AND ((g.member_id = m.id AND g.department_id IS NULL)
                    OR (g.member_id IS NULL AND g.department_id = m.department_id
                        AND EXISTS (SELECT 1 FROM sys_department d
                            WHERE d.id = g.department_id AND d.enterprise_id = m.enterprise_id
                              AND d.status = 0))))
            """;

    @Select("SELECT c.id, c.title, c.created_at AS createdAt FROM rag_conversation c "
            + ACTIVE_MEMBER + " WHERE c.id = #{id} AND " + OWNED_CONVERSATION)
    EnterpriseResources.Conversation findConversation(@Param("scope") EnterpriseMemberContext scope,
            @Param("id") long id);

    @Select("""
            SELECT x.id, x.conversation_id AS conversationId, x.role, x.content,
                   x.token_count AS tokenCount, x.created_at AS createdAt
            FROM rag_message x JOIN rag_conversation c ON c.id = x.conversation_id
            """ + ACTIVE_MEMBER + " WHERE x.id = #{id} AND " + OWNED_CONVERSATION)
    EnterpriseResources.Message findMessage(@Param("scope") EnterpriseMemberContext scope,
            @Param("id") long id);

    @Select("""
            SELECT s.id, s.name, s.permission_version AS permissionVersion
            FROM rag_knowledge_space s
            """ + ACTIVE_MEMBER + " WHERE s.id = #{id} AND " + SPACE_GRANT)
    EnterpriseResources.KnowledgeSpace findSpace(@Param("scope") EnterpriseMemberContext scope,
            @Param("id") long id);

    /** 仅分页读取当前授权空间；EXISTS避免多条授权重复计数，固定主键排序保证分页顺序。 */
    @Select("""
            SELECT s.id, s.name, s.permission_version AS permissionVersion
            FROM rag_knowledge_space s
            """ + ACTIVE_MEMBER + " WHERE " + SPACE_GRANT + " ORDER BY s.id ASC")
    IPage<EnterpriseResources.KnowledgeSpace> findSpaces(IPage<EnterpriseResources.KnowledgeSpace> page,
            @Param("scope") EnterpriseMemberContext scope);

    /** 旧入口仅保留历史元数据；新入库文档必须通过带发布版本校验的文档接口读取。 */
    @Select("""
            SELECT x.id, x.knowledge_space_id AS knowledgeSpaceId, x.title,
                   x.source_type AS sourceType, x.status, x.chunk_count AS chunkCount
            FROM rag_document x JOIN rag_knowledge_space s ON s.id = x.knowledge_space_id
                AND s.enterprise_id = x.enterprise_id
            """ + ACTIVE_MEMBER + " WHERE x.id = #{id} AND x.deleted = 0 AND x.owner_member_id IS NULL AND " + SPACE_GRANT)
    EnterpriseResources.Document findDocument(@Param("scope") EnterpriseMemberContext scope,
            @Param("id") long id);

    @Select("""
            SELECT x.id, x.conversation_id AS conversationId, x.message_id AS messageId
            FROM rag_task_reference x
            JOIN rag_conversation c ON c.id = x.conversation_id AND c.enterprise_id = x.enterprise_id
            JOIN rag_message msg ON msg.id = x.message_id AND msg.conversation_id = c.id
            """ + ACTIVE_MEMBER + " WHERE x.id = #{id} AND " + OWNED_CONVERSATION)
    EnterpriseResources.TaskReference findTask(@Param("scope") EnterpriseMemberContext scope,
            @Param("id") long id);
}
