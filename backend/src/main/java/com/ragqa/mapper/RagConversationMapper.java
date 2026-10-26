package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.RagConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答会话数据访问层。
 */
@Mapper
public interface RagConversationMapper extends BaseMapper<RagConversation> {
}
