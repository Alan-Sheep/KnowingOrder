package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 系统用户数据访问层。继承 BaseMapper 提供单表 CRUD，复杂 SQL 后续按需在此追加。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    /** 此Mapper在dev已关闭DEBUG，口令散列和SQL绑定值不得进入日志。 */
    @Select("SELECT password_hash FROM sys_user WHERE id=#{id} AND auth_version=#{version} AND status=0 AND deleted=0")
    String currentPasswordHash(@Param("id") long id, @Param("version") long version);

    @Update("UPDATE sys_user SET password_hash=#{hash},auth_version=auth_version+1 "
            + "WHERE id=#{id} AND auth_version=#{version} AND auth_version<9223372036854775807 AND status=0 AND deleted=0")
    int changePassword(@Param("id") long id, @Param("version") long version, @Param("hash") String hash);
}
