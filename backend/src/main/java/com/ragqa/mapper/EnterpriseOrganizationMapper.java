package com.ragqa.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 组织管理使用固定SQL；调用方必须在同一事务锁定并复核企业管理员。 */
@Mapper
public interface EnterpriseOrganizationMapper {
    record Department(Long id, String name, int status) { }
    record Member(Long id, Long userId, String username, String displayName, Long departmentId, String role,
            int status, int accountStatus, long permissionVersion) { }
    @Data class NewDepartment { private Long id; private long enterpriseId; private String name; }

    @Select("SELECT COUNT(*) FROM sys_department WHERE enterprise_id=#{enterprise}")
    long departmentCount(@Param("enterprise") long enterprise);
    @Select("SELECT id,name,status FROM sys_department WHERE enterprise_id=#{enterprise} ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<Department> departments(@Param("enterprise") long enterprise, @Param("size") long size, @Param("offset") long offset);
    @Select("SELECT id,name,status FROM sys_department WHERE enterprise_id=#{enterprise} AND id=#{id} FOR UPDATE")
    Department department(@Param("enterprise") long enterprise, @Param("id") long id);
    @Select("SELECT COUNT(*) FROM sys_department WHERE enterprise_id=#{enterprise} AND name=#{name} AND id != #{except}")
    long sameName(@Param("enterprise") long enterprise, @Param("name") String name, @Param("except") long except);
    @Insert("INSERT INTO sys_department(enterprise_id,name,status) VALUES(#{enterpriseId},#{name},0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDepartment(NewDepartment department);
    @Update("UPDATE sys_department SET name=#{name},status=#{status} WHERE enterprise_id=#{enterprise} AND id=#{id}")
    int updateDepartment(@Param("enterprise") long enterprise, @Param("id") long id,
            @Param("name") String name, @Param("status") int status);
    @Select("SELECT id FROM sys_enterprise_member WHERE enterprise_id=#{enterprise} AND department_id=#{department} LIMIT 1 FOR UPDATE")
    Long departmentMember(@Param("enterprise") long enterprise, @Param("department") long department);
    @Select("SELECT id FROM rag_knowledge_grant WHERE enterprise_id=#{enterprise} AND department_id=#{department} AND status=0 LIMIT 1 FOR UPDATE")
    Long departmentGrant(@Param("enterprise") long enterprise, @Param("department") long department);
    @Update("UPDATE sys_enterprise SET permission_version=permission_version+1 WHERE id=#{enterprise} AND permission_version=#{version} AND status=0")
    int advanceEnterprise(@Param("enterprise") long enterprise, @Param("version") long version);

    String MEMBER_COLUMNS = "SELECT m.id,m.user_id AS userId,u.username,"
            + "COALESCE(NULLIF(TRIM(u.nickname),''),u.username) AS displayName,"
            + "m.department_id AS departmentId,m.role,m.status,"
            + "CASE WHEN u.deleted=1 THEN 1 ELSE u.status END AS accountStatus,m.permission_version AS permissionVersion FROM sys_enterprise_member m "
            + "JOIN sys_user u ON u.id=m.user_id ";
    @Select("SELECT COUNT(*) FROM sys_enterprise_member m JOIN sys_user u ON u.id=m.user_id WHERE m.enterprise_id=#{enterprise}")
    long memberCount(@Param("enterprise") long enterprise);
    @Select(MEMBER_COLUMNS + "WHERE m.enterprise_id=#{enterprise} ORDER BY m.id LIMIT #{size} OFFSET #{offset}")
    List<Member> members(@Param("enterprise") long enterprise, @Param("size") long size, @Param("offset") long offset);
    @Select(MEMBER_COLUMNS + "WHERE m.enterprise_id=#{enterprise} AND m.id=#{id} FOR UPDATE")
    Member member(@Param("enterprise") long enterprise, @Param("id") long id);
    @Update("""
            UPDATE sys_enterprise_member SET department_id=#{department},status=#{status},permission_version=permission_version+1
            WHERE enterprise_id=#{enterprise} AND id=#{id} AND role='EMPLOYEE' AND permission_version=#{version}
            """)
    int updateMember(@Param("enterprise") long enterprise, @Param("id") long id, @Param("department") Long department,
            @Param("status") int status, @Param("version") long version);
}
