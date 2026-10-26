package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 实体公共字段基类（无逻辑删除）。
 *
 * <p>适用于没有 {@code deleted} 列的表，如 rag_chunk、rag_message；
 * 有逻辑删除的表请继承 {@link SoftDeleteEntity}。</p>
 *
 * <p>说明：</p>
 * <ul>
 *     <li>{@code createdAt} / {@code updatedAt} 由数据库的 DEFAULT CURRENT_TIMESTAMP
 *         与 ON UPDATE CURRENT_TIMESTAMP 维护，应用层不赋值（MyBatis-Plus 默认跳过 null 字段）</li>
 *     <li>子类需加 {@code @EqualsAndHashCode(callSuper = true)}，否则 Lombok 生成的
 *         equals/hashCode 会忽略父类字段</li>
 * </ul>
 */
@Getter
@Setter
public abstract class BaseEntity {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
