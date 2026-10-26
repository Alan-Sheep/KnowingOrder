package com.ragqa.service;

import com.ragqa.domain.entity.RagConversation;

/** 会话业务入口；归属读取供后续消息、历史和任务业务复用。 */
public interface ConversationService {

    /**
     * 读取当前用户拥有且未删除的会话。
     *
     * <p>当前用户编号必须来自服务端认证结果，不能使用请求正文中的用户编号。
     * 本方法只负责读取归属，不代替登录校验，也不能作为后续写操作的原子性保证。</p>
     *
     * @param currentUserId 已认证的当前用户编号
     * @param conversationId 目标会话编号
     * @return 归属匹配且未删除的会话
     * @throws com.ragqa.exception.BusinessException 编号无效，或会话不存在、已删除、不可访问
     */
    RagConversation requireOwned(Long currentUserId, Long conversationId);
}
