package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.entity.SysEnterpriseMember;
import com.ragqa.service.EnterpriseMemberService.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 授权查询始终在同一SQL绑定企业、账号及有效状态，不先加载其他企业成员。 */
@Mapper
public interface EnterpriseDirectoryMapper extends BaseMapper<SysEnterpriseMember> {

    /** 绑定登录账号及认证版本，只列有效企业与有效成员；额外一条用于检测容量边界。 */
    @Select("""
            SELECT e.id AS enterpriseId, e.name AS enterpriseName, m.role
            FROM sys_enterprise_member m
            JOIN sys_enterprise e ON e.id = m.enterprise_id AND e.status = 0
            JOIN sys_user u ON u.id = m.user_id AND u.status = 0 AND u.deleted = 0
            WHERE m.user_id = #{userId} AND u.auth_version = #{authVersion}
              AND m.status = 0 AND m.role IN ('EMPLOYEE', 'ADMIN', 'FINANCE')
            ORDER BY e.id LIMIT 101
            """)
    List<Workspace> listActiveWorkspaces(@Param("userId") long userId, @Param("authVersion") long authVersion);

    String ACTIVE_LOOKUP = """
            SELECT e.id AS enterpriseId, m.id AS memberId, m.user_id AS userId,
                   m.department_id AS departmentId, m.role,
                   e.permission_version AS enterprisePermissionVersion,
                   m.permission_version AS memberPermissionVersion, u.auth_version AS userAuthVersion
            FROM sys_enterprise_member m
            JOIN sys_enterprise e ON e.id = m.enterprise_id AND e.status = 0
            JOIN sys_user u ON u.id = m.user_id AND u.status = 0 AND u.deleted = 0
            WHERE m.enterprise_id = #{enterpriseId} AND m.user_id = #{userId}
              AND u.auth_version = #{authVersion}
              AND m.status = 0 AND m.role IN ('EMPLOYEE', 'ADMIN', 'FINANCE')
            """;

    @Select(ACTIVE_LOOKUP)
    EnterpriseMemberContext findActiveMember(@Param("userId") long userId,
            @Param("enterpriseId") long enterpriseId, @Param("authVersion") long authVersion);

    /** 管理写入先加锁；与企业、账号、成员状态变更串行，不能仅使用请求开始时的快照。 */
    @Select(ACTIVE_LOOKUP + " FOR UPDATE")
    EnterpriseMemberContext findActiveMemberForUpdate(@Param("userId") long userId,
            @Param("enterpriseId") long enterpriseId, @Param("authVersion") long authVersion);
}
