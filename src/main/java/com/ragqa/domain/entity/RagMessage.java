package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话消息，对应表 rag_message。该表无 deleted 列，不做逻辑删除。
 * 公共字段 id / createdAt / updatedAt 见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_message")
public class RagMessage extends BaseEntity {

    /** 所属会话ID，逻辑关联 rag_conversation.id */
    private Long conversationId;

    /** 发言角色，见 Constants.MessageRole */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 本条消息消耗的 Token 数（预留，用于统计） */
    private Integer tokenCount;
}
