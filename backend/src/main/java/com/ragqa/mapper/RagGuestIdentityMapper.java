package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.RagGuestIdentity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访客身份数据访问层，提供基础单表读写。
 * 此映射不负责身份认证、生命周期校验、归属隔离或到期清理。
 */
@Mapper
public interface RagGuestIdentityMapper extends BaseMapper<RagGuestIdentity> {
}
