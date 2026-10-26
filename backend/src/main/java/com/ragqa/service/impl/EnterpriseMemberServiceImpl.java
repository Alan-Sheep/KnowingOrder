package com.ragqa.service.impl;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.service.CurrentUserService;
import com.ragqa.service.EnterpriseMemberService;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.List;

/** 每次从数据库复核，首版不缓存成员状态，不保存请求线程中的可变成员信息。 */
@Service
public class EnterpriseMemberServiceImpl implements EnterpriseMemberService {
    private final CurrentUserService users;
    private final EnterpriseDirectoryMapper directory;

    public EnterpriseMemberServiceImpl(CurrentUserService users, EnterpriseDirectoryMapper directory) {
        this.users = users;
        this.directory = directory;
    }

    @Override
    public List<Workspace> listWorkspaces(Principal principal) {
        long userId = users.requireUserId(principal);
        if (!(principal instanceof AuthenticatedUserPrincipal authenticated) || authenticated.authVersion() <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        List<Workspace> result = directory.listActiveWorkspaces(userId, authenticated.authVersion());
        // 查询后复核账号，不能把查询期间的撤销登录升级成有效身份。
        users.requireUserId(principal);
        if (result.size() > 100) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "企业归属数量超出工作区展示上限，请联系管理员");
        }
        return List.copyOf(result);
    }

    @Override
    public EnterpriseMemberContext requireMember(Principal principal, Long enterpriseId) {
        Long userId = users.requireUserId(principal);
        if (!(principal instanceof AuthenticatedUserPrincipal authenticated) || authenticated.authVersion() <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (enterpriseId == null || enterpriseId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "企业编号必须为正整数");
        }
        // 固定原认证版本，避免账号复核与成员查询之间撤销登录后被“升级”为新的有效版本。
        EnterpriseMemberContext result = directory.findActiveMember(userId, enterpriseId, authenticated.authVersion());
        if (result == null || result.enterpriseId() != enterpriseId || result.userId() != userId
                || result.userAuthVersion() != authenticated.authVersion()) {
            throw unavailable();
        }
        return result;
    }

    @Override
    public EnterpriseMemberContext requireCurrent(EnterpriseMemberContext context) {
        if (context == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        EnterpriseMemberContext current = requireMember(new AuthenticatedUserPrincipal(context.userId(), context.userAuthVersion()),
                context.enterpriseId());
        if (!current.equals(context)) {
            throw unavailable();
        }
        return current;
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.NOT_FOUND, "企业不存在或不可访问");
    }
}
