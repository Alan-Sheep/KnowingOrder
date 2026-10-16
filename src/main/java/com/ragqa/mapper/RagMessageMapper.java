package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.RagMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息数据访问层。
 */
@Mapper
public interface RagMessageMapper extends BaseMapper<RagMessage> {
}
