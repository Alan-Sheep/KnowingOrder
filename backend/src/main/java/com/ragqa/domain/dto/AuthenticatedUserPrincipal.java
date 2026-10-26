package com.ragqa.domain.dto;

import java.security.Principal;
import java.io.Serializable;

/**
 * 认证成功后由服务端创建的不可变身份，只保存用户主键与认证版本，不保存密码、令牌或用户名。
 * 此类型本身不是认证证明，禁止从客户端JSON或自定义用户头直接构造可信身份。
 */
public record AuthenticatedUserPrincipal(long userId, long authVersion) implements Principal, Serializable {

    /** 保留原服务层调用兼容；真实登录必须绑定数据库中的正数认证版本。 */
    public AuthenticatedUserPrincipal(long userId) {
        this(userId, 0);
    }

    public AuthenticatedUserPrincipal {
        if (userId <= 0) {
            throw new IllegalArgumentException("认证用户编号必须为正整数");
        }
        if (authVersion < 0) {
            throw new IllegalArgumentException("认证版本不能为负数");
        }
    }

    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
