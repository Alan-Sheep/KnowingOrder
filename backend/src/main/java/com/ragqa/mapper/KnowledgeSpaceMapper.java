package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.entity.RagKnowledgeSpace;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/** 空间创建专用写入；不暴露无企业范围的通用增删改查，读取复用资源授权Mapper。 */
@Mapper
public interface KnowledgeSpaceMapper {
    // 只拼接编译期SQL常量；上下文仍须由服务端核验，事务加锁由服务层负责。
    String ACTIVE_ADMIN = """
            SELECT 1 FROM sys_enterprise_member m
            JOIN sys_enterprise e ON e.id = m.enterprise_id AND e.status = 0
                AND e.permission_version = #{scope.enterprisePermissionVersion}
            JOIN sys_user u ON u.id = m.user_id AND u.status = 0 AND u.deleted = 0
                AND u.auth_version = #{scope.userAuthVersion}
            WHERE m.id = #{scope.memberId} AND m.enterprise_id = #{scope.enterpriseId}
              AND m.user_id = #{scope.userId} AND m.status = 0
              AND m.role = 'ADMIN' AND m.role = #{scope.role}
              AND m.permission_version = #{scope.memberPermissionVersion}
            """;

    /** 返回1并回填主键才表示写入成功；身份失效或企业不匹配返回0，不能继续写授权。 */
    @Insert("""
            INSERT INTO rag_knowledge_space (enterprise_id, name, status, permission_version)
            SELECT target.id, #{space.name}, 0, 1 FROM sys_enterprise target
            WHERE target.id = #{space.enterpriseId} AND target.id = #{scope.enterpriseId}
              AND EXISTS (
            """ + ACTIVE_ADMIN + ")")
    @Options(useGeneratedKeys = true, keyProperty = "space.id", keyColumn = "id")
    int insertSpace(@Param("scope") EnterpriseMemberContext scope,
            @Param("space") RagKnowledgeSpace space);

    /**
     * 仅供创建服务以同一事务新建的空间编号授予创建者维护权，不作为已有空间的自授权入口。
     * 重复授权交给唯一约束拒绝；不覆盖已有授权，不代表创建请求具有幂等性。
     */
    @Insert("""
            INSERT INTO rag_knowledge_grant
                (enterprise_id, knowledge_space_id, member_id, department_id, permission, status)
            SELECT s.enterprise_id, s.id, #{scope.memberId}, NULL, 'MAINTAIN', 0
            FROM rag_knowledge_space s
            WHERE s.id = #{spaceId} AND s.enterprise_id = #{scope.enterpriseId} AND s.status = 0
              AND EXISTS (
            """ + ACTIVE_ADMIN + ")")
    int insertCreatorGrant(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId);
}
