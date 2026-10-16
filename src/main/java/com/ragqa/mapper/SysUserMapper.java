package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户数据访问层。继承 BaseMapper 提供单表 CRUD，复杂 SQL 后续按需在此追加。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
