package com.ragqa.mapper;

import com.ragqa.domain.entity.SysEnterprise;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 账号管理仅提供固定语句；企业和操作者权限由加锁后的成员关系限定。 */
@Mapper
public interface EnterpriseAccountMapper {
    @Select("SELECT COUNT(*) FROM sys_enterprise")
    int countEnterprises();

    @Insert("INSERT INTO sys_enterprise(id, name, status, permission_version) VALUES(#{id}, #{name}, 0, 1)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEnterprise(SysEnterprise enterprise);

    @Update("""
            UPDATE sys_enterprise_member SET status = #{status}, permission_version = permission_version + 1
            WHERE enterprise_id = #{enterpriseId} AND id = #{memberId} AND role = 'EMPLOYEE'
            """)
    int setEmployeeStatus(@Param("enterpriseId") long enterpriseId, @Param("memberId") long memberId,
            @Param("status") int status);

    @Update("""
            UPDATE sys_user SET auth_version = auth_version + 1
            WHERE id = #{userId} AND auth_version = #{authVersion} AND status = 0 AND deleted = 0
            """)
    int revokeSessions(@Param("userId") long userId, @Param("authVersion") long authVersion);
}
