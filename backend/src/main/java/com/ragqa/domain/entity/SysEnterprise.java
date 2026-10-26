package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 企业状态独立于账号状态；停用后不得继续访问其私有资源。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_enterprise")
public class SysEnterprise extends BaseEntity {
    public static final int ACTIVE = 0;
    public static final int DISABLED = 1;

    /** 企业名称，不作为授权依据。 */
    private String name;
    /** 状态：0启用，1停用；未知状态拒绝访问。 */
    private Integer status;
    /** 权限变更版本，从1开始。 */
    private Long permissionVersion;
}
