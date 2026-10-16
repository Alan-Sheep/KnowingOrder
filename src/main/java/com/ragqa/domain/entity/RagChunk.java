package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分块，对应表 rag_chunk。该表无 deleted 列，不做逻辑删除。
 * 公共字段 id / createdAt / updatedAt 见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_chunk")
public class RagChunk extends BaseEntity {

    /** 所属文档ID，逻辑关联 rag_document.id */
    private Long documentId;

    /** 块在原文档中的序号（从0开始） */
    private Integer chunkIndex;

    /** 分块纯文本内容 */
    private String content;

    /** Token 数量，用于预算上下文长度 */
    private Integer tokenCount;
}
