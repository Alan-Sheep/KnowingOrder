package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档，对应表 rag_document。公共字段见 {@link SoftDeleteEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_document")
public class RagDocument extends SoftDeleteEntity {

    /** 文档标题 */
    private String title;

    /** 来源类型，见 Constants.SourceType */
    private String sourceType;

    /** 文件存储路径（上传类文档使用） */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 解析状态，见 Constants.DocumentStatus */
    private Integer status;

    /** 已分块数量（冗余统计） */
    private Integer chunkCount;

    /** 上传人ID，逻辑关联 sys_user.id */
    private Long createdBy;

    /** 企业与知识空间；旧文档为空，不根据上传人推断授权。 */
    private Long enterpriseId;

    private Long knowledgeSpaceId;
}
