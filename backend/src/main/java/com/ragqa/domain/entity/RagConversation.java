package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 问答会话，对应表 rag_conversation。一次连续问答对话的容器。
 * 公共字段见 {@link SoftDeleteEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_conversation")
public class RagConversation extends SoftDeleteEntity {

    /** 归属用户ID，逻辑关联 sys_user.id */
    private Long userId;

    /** 企业与成员归属；旧会话为空，不能自动进入企业接口。 */
    private Long enterpriseId;

    private Long memberId;

    /** 会话标题（通常取首轮问题摘要） */
    private String title;
}
