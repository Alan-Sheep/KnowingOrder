package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块，对应表 rag_chunk。该表无 deleted 列，不做逻辑删除。
 */
@Data
@TableName("rag_chunk")
public class RagChunk {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档ID，逻辑关联 rag_document.id */
    private Long documentId;

    /** 块在原文档中的序号（从0开始） */
    private Integer chunkIndex;

    /** 分块纯文本内容 */
    private String content;

    /** Token 数量，用于预算上下文长度 */
    private Integer tokenCount;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
