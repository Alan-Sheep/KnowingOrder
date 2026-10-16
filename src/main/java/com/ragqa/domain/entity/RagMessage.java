package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息，对应表 rag_message。该表无 deleted 列，不做逻辑删除。
 */
@Data
@TableName("rag_message")
public class RagMessage {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话ID，逻辑关联 rag_conversation.id */
    private Long conversationId;

    /** 发言角色，见 Constants.MessageRole */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 本条消息消耗的 Token 数（预留，用于统计） */
    private Integer tokenCount;

    /** 发送时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
