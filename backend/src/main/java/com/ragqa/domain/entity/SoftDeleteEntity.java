package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

/**
 * 带逻辑删除的实体基类。
 *
 * <p>适用于有 {@code deleted} 列的 {@code 表}：sys_user、rag_document、rag_conversation。</p>
 *
 * <p>{@code @TableLogic} 会让 MyBatis-Plus 在查询时自动追加 {@code AND deleted = 0}，
 * 删除时改为 {@code UPDATE ... SET deleted = 1}。全局配置（application.yml 的
 * logic-delete-field）已声明该字段，此处显式标注是为了让实体自身可读、不依赖隐式约定。</p>
 */
@Getter
@Setter
public abstract class SoftDeleteEntity extends BaseEntity {

    /** 逻辑删除标记，见 Constants.Deleted：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
