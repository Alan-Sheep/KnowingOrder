package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户，对应表 sys_user。公共字段 id / createdAt / updatedAt / deleted 见 {@link SoftDeleteEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends SoftDeleteEntity {

    /** 登录用户名（唯一） */
    private String username;

    /** 密码散列值，禁止序列化返回前端 */
    @JsonIgnore
    private String passwordHash;

    /** 用户昵称 */
    private String nickname;

    /** 邮箱地址 */
    private String email;

    /** 账号状态，见 Constants.UserStatus */
    private Integer status;
}
