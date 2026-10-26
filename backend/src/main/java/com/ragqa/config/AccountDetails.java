package com.ragqa.config;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.entity.SysUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/** 登录层专用身份；密码由Spring在认证后擦除，业务层只取得稳定主键和认证版本。 */
public final class AccountDetails extends User {
    private final AuthenticatedUserPrincipal identity;

    public AccountDetails(SysUser user) {
        super(user.getUsername(), user.getPasswordHash(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.identity = new AuthenticatedUserPrincipal(user.getId(), user.getAuthVersion());
    }

    public AuthenticatedUserPrincipal getIdentity() {
        return identity;
    }

    @Override
    public String toString() {
        return "AccountDetails(userId=" + identity.userId() + ")";
    }
}
