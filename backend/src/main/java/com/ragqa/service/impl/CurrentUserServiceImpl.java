package com.ragqa.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragqa.common.Constants;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.entity.SysUser;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.security.Principal;

/** 账号解析不保存线程状态；每次复核状态，避免复用已禁用或已删除的账号快照。 */
@Service
public class CurrentUserServiceImpl implements CurrentUserService {

    private final SysUserMapper userMapper;

    public CurrentUserServiceImpl(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Long requireUserId(Principal principal) {
        if (!(principal instanceof AuthenticatedUserPrincipal authenticated)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        // 身份锚定不可变主键；用户名改名后也不能映射到另一个账号。
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getId)
                .eq(SysUser::getId, authenticated.userId())
                .eq(authenticated.authVersion() > 0, SysUser::getAuthVersion, authenticated.authVersion())
                .eq(SysUser::getStatus, Constants.UserStatus.NORMAL));
        if (user == null || user.getId() == null || user.getId() != authenticated.userId()) {
            // 不暴露账号是否存在、被禁用或已删除；底层故障不在这里伪装成身份失效。
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user.getId();
    }

    @Override
    public AccountIdentity identity(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getUsername, SysUser::getNickname)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getStatus, Constants.UserStatus.NORMAL));
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            // 与 requireUserId 同口径：不暴露账号是否存在、被禁用或已删除。
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String nickname = user.getNickname() == null ? "" : user.getNickname().strip();
        String displayName = nickname.isEmpty() ? user.getUsername() : nickname;
        return new AccountIdentity(user.getUsername(), displayName);
    }
}
