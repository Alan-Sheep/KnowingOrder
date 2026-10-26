package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 企业知识空间；维护权限由明确授权决定，管理员角色不自动获得读取权。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_knowledge_space")
public class RagKnowledgeSpace extends BaseEntity {
    public static final int ACTIVE = 0;
    public static final int DISABLED = 1;

    /** 所属企业，必须与服务端核验后的成员上下文一致。 */
    private Long enterpriseId;
    /** 空间名称，不作为唯一标识或授权依据。 */
    private String name;
    /** 状态：0启用，1停用；创建时由固定SQL写入启用状态。 */
    private Integer status;
    /** 权限版本，从1开始；后续授权变更须在事务内递增。 */
    private Long permissionVersion;
}
