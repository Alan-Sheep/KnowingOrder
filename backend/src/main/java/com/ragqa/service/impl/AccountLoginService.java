package com.ragqa.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragqa.config.AccountDetails;
import com.ragqa.domain.entity.SysUser;
import com.ragqa.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 仅认证账号；企业关系与文档权限仍由业务层逐次核验，不从登录角色推断。 */
@Service
public class AccountLoginService implements UserDetailsService {
    private final SysUserMapper users;

    public AccountLoginService(SysUserMapper users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 64) {
            throw unavailable();
        }
        SysUser user = users.selectOne(Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getPasswordHash, SysUser::getAuthVersion)
                .eq(SysUser::getUsername, username).eq(SysUser::getStatus, 0));
        if (user == null || user.getAuthVersion() == null || user.getAuthVersion() <= 0
                || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw unavailable();
        }
        return new AccountDetails(user);
    }

    private UsernameNotFoundException unavailable() {
        return new UsernameNotFoundException("账号或密码错误，或账号不可用");
    }
}
