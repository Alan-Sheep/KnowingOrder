package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话，对应表 rag_conversation。一次连续问答对话的容器。
 */
@Data
@TableName("rag_conversation")
public class RagConversation {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户ID，逻辑关联 sys_user.id */
    private Long userId;

    /** 会话标题（通常取首轮问题摘要） */
    private String title;

    /** 逻辑删除标记，见 Constants.Deleted */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
