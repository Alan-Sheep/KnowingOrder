package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 同一账号可在不同企业有独立成员关系；离职或停用不删除关系后重新开户。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_enterprise_member")
public class SysEnterpriseMember extends BaseEntity {
    public static final int ACTIVE = 0;
    public static final int DISABLED = 1;
    public static final int LEFT = 2;

    /** 逻辑关联sys_enterprise.id。 */
    private Long enterpriseId;
    /** 逻辑关联sys_user.id，同企业账号唯一。 */
    private Long userId;
    /** 当前直属部门；不隐式继承上级部门权限。 */
    private Long departmentId;
    /** EMPLOYEE或ADMIN；管理员不自动拥有私有资源读取权。 */
    private String role;
    /** 0有效，1停用，2离职。 */
    private Integer status;
    /** 成员授权变更版本，从1开始。 */
    private Long permissionVersion;
}
