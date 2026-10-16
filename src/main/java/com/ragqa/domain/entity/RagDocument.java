package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档，对应表 rag_document。
 */
@Data
@TableName("rag_document")
public class RagDocument {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 逻辑删除标记，见 Constants.Deleted */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
