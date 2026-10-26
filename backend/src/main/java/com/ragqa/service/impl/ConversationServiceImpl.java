package com.ragqa.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.entity.RagConversation;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.RagConversationMapper;
import com.ragqa.service.ConversationService;
import org.springframework.stereotype.Service;

/** 会话归属在同一次参数化查询中限定，避免先按主键加载其他用户的数据。 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final RagConversationMapper conversationMapper;

    public ConversationServiceImpl(RagConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public RagConversation requireOwned(Long currentUserId, Long conversationId) {
        if (currentUserId == null || currentUserId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "当前用户标识无效");
        }
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "会话编号必须为正整数");
        }
        // 每次创建独立条件；@TableLogic 由 MyBatis-Plus 为本次查询追加未删除约束。
        RagConversation conversation = conversationMapper.selectOne(Wrappers.<RagConversation>lambdaQuery()
                .eq(RagConversation::getId, conversationId)
                .eq(RagConversation::getUserId, currentUserId));
        if (conversation == null) {
            // 不区分不存在与其他用户的会话，避免暴露资源是否存在。
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在或不可访问");
        }
        return conversation;
    }
}
