package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户，对应表 sys_user。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 逻辑删除标记，见 Constants.Deleted */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updatedAt;
}
