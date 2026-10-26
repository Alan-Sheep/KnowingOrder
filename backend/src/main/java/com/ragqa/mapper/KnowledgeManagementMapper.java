package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 所有知识写入先锁空间；锁与授权变更使用同一事务，SQL参数全部绑定。 */
@Mapper
public interface KnowledgeManagementMapper {
    String MAINTAIN = """
            s.enterprise_id = m.enterprise_id AND s.status = 0 AND EXISTS (
              SELECT 1 FROM rag_knowledge_grant g WHERE g.enterprise_id = s.enterprise_id
                AND g.knowledge_space_id = s.id AND g.status = 0 AND g.permission = 'MAINTAIN'
                AND ((g.member_id = m.id AND g.department_id IS NULL)
                  OR (g.member_id IS NULL AND g.department_id = m.department_id AND EXISTS (
                    SELECT 1 FROM sys_department dep WHERE dep.id = m.department_id
                      AND dep.enterprise_id = m.enterprise_id AND dep.status = 0))))
            """;

    @Select("SELECT s.id, s.name, s.permission_version AS permissionVersion FROM rag_knowledge_space s "
            + EnterpriseResourceMapper.ACTIVE_MEMBER + " WHERE s.id = #{spaceId} AND " + MAINTAIN + " FOR UPDATE")
    EnterpriseResources.KnowledgeSpace lockMaintain(@Param("scope") EnterpriseMemberContext scope,
            @Param("spaceId") long spaceId);

    @Update("UPDATE rag_knowledge_space SET name = #{name}, permission_version = permission_version + 1 "
            + "WHERE id = #{spaceId} AND enterprise_id = #{scope.enterpriseId} AND permission_version = #{version}")
    int rename(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("version") long version, @Param("name") String name);

    @Update("UPDATE rag_knowledge_space SET permission_version = permission_version + 1 "
            + "WHERE id = #{spaceId} AND enterprise_id = #{scope.enterpriseId} AND permission_version = #{version}")
    int advance(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("version") long version);

    @Select("SELECT g.id, g.member_id AS memberId, g.department_id AS departmentId, g.permission, g.status "
            + "FROM rag_knowledge_grant g WHERE g.enterprise_id = #{scope.enterpriseId} "
            + "AND g.knowledge_space_id = #{spaceId} ORDER BY g.id LIMIT 500")
    List<Grant> grants(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId);

    @Select("SELECT id FROM sys_enterprise_member WHERE id = #{targetId} "
            + "AND enterprise_id = #{scope.enterpriseId} AND status = 0 FOR UPDATE")
    Long lockMember(@Param("scope") EnterpriseMemberContext scope, @Param("targetId") long targetId);

    @Select("SELECT id FROM sys_department WHERE id = #{targetId} "
            + "AND enterprise_id = #{scope.enterpriseId} AND status = 0 FOR UPDATE")
    Long lockDepartment(@Param("scope") EnterpriseMemberContext scope, @Param("targetId") long targetId);

    @Select("""
            SELECT t.id FROM sys_enterprise_member t JOIN sys_user account ON account.id=t.user_id
              AND account.status=0 AND account.deleted=0
            WHERE t.id=#{targetId} AND t.enterprise_id=#{scope.enterpriseId} AND t.status=0 AND EXISTS(
              SELECT 1 FROM rag_knowledge_grant g WHERE g.enterprise_id=t.enterprise_id
                AND g.knowledge_space_id=#{spaceId} AND g.status=0 AND g.permission='MAINTAIN'
                AND ((g.member_id=t.id AND g.department_id IS NULL) OR (g.member_id IS NULL
                  AND g.department_id=t.department_id AND EXISTS(SELECT 1 FROM sys_department dep
                    WHERE dep.id=t.department_id AND dep.enterprise_id=t.enterprise_id AND dep.status=0))))
            """)
    Long maintainerMember(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("targetId") long targetId);

    @Select("""
            SELECT id, member_id AS memberId, department_id AS departmentId, permission, status
            FROM rag_knowledge_grant WHERE enterprise_id = #{scope.enterpriseId} AND knowledge_space_id = #{spaceId}
              AND ((member_id = #{memberId} AND department_id IS NULL)
                OR (department_id = #{departmentId} AND member_id IS NULL))
            """)
    Grant grant(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("memberId") Long memberId, @Param("departmentId") Long departmentId);

    @Insert("""
            INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,department_id,permission,status)
            VALUES(#{scope.enterpriseId},#{spaceId},#{memberId},#{departmentId},#{permission},0)
            """)
    int insertGrant(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("memberId") Long memberId, @Param("departmentId") Long departmentId,
            @Param("permission") String permission);

    @Update("UPDATE rag_knowledge_grant SET permission = #{permission}, status = #{status} "
            + "WHERE enterprise_id = #{scope.enterpriseId} AND knowledge_space_id = #{spaceId} AND id = #{id}")
    int updateGrant(@Param("scope") EnterpriseMemberContext scope, @Param("spaceId") long spaceId,
            @Param("id") long id, @Param("permission") String permission, @Param("status") int status);

    /** 不返回账号口令、个人资料；目标必须在服务层验证为同企业有效对象。 */
    record Grant(Long id, Long memberId, Long departmentId, String permission, int status) { }
}
