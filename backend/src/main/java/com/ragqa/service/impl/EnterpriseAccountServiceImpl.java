package com.ragqa.service.impl;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.NewEmployeeRequest;
import com.ragqa.domain.entity.SysEnterprise;
import com.ragqa.domain.entity.SysEnterpriseMember;
import com.ragqa.domain.entity.SysUser;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseAccountMapper;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.CurrentUserService;
import com.ragqa.service.EnterpriseAccountService;
import com.ragqa.service.EnterpriseMemberService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/** 账号和成员同时提交；加锁复核管理员，失败不留下孤立账号或半条企业关系。 */
@Service
public class EnterpriseAccountServiceImpl implements EnterpriseAccountService {
    private final EnterpriseMemberService members;
    private final CurrentUserService currentUser;
    private final EnterpriseDirectoryMapper directory;
    private final EnterpriseAccountMapper accounts;
    private final SysUserMapper users;
    private final PasswordEncoder passwords;

    public EnterpriseAccountServiceImpl(EnterpriseMemberService members, CurrentUserService currentUser,
            EnterpriseDirectoryMapper directory, EnterpriseAccountMapper accounts, SysUserMapper users,
            PasswordEncoder passwords) {
        this.members = members;
        this.currentUser = currentUser;
        this.directory = directory;
        this.accounts = accounts;
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public CreatedEmployee createEmployee(AuthenticatedUserPrincipal principal, Long enterpriseId,
            NewEmployeeRequest request) {
        EnterpriseMemberContext scope = members.requireMember(principal, enterpriseId);
        requireAdminLock(scope);
        return insertAccount(scope.enterpriseId(), request, "EMPLOYEE");
    }

    @Override
    @Transactional
    public void setEmployeeStatus(AuthenticatedUserPrincipal principal, Long enterpriseId, Long memberId, int status) {
        EnterpriseMemberContext scope = members.requireMember(principal, enterpriseId);
        requireAdminLock(scope);
        if (memberId == null || memberId <= 0 || status < 0 || status > 2) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "成员编号或状态不正确");
        }
        // 首版只管理员工，不允许借此接口停用/降级管理员或把自己锁在企业之外。
        if (accounts.setEmployeeStatus(scope.enterpriseId(), memberId, status) != 1) {
            throw unavailable();
        }
    }

    @Override
    @Transactional
    public void revokeOwnSessions(AuthenticatedUserPrincipal principal) {
        long userId = currentUser.requireUserId(principal);
        if (principal.authVersion() <= 0 || accounts.revokeSessions(userId, principal.authVersion()) != 1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CreatedEmployee bootstrapFirstAdmin(String enterpriseName, NewEmployeeRequest request) {
        if (enterpriseName == null || enterpriseName.isBlank() || enterpriseName.length() > 128) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "企业名称必须为1至128个字符");
        }
        // 仅供非Web初始化命令：串行化事务防止多个首建请求同时成功，不重用历史同名账号。
        if (accounts.countEnterprises() != 0) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "已有企业，禁止重复初始化管理员");
        }
        SysEnterprise enterprise = new SysEnterprise();
        // 首建保留1号；即使数据库的隔离实现不同，并发首建也受主键唯一约束保护。
        enterprise.setId(1L);
        enterprise.setName(enterpriseName.strip());
        accounts.insertEnterprise(enterprise);
        return insertAccount(enterprise.getId(), request, "ADMIN");
    }

    private void requireAdminLock(EnterpriseMemberContext scope) {
        if (!"ADMIN".equals(scope.role())) {
            throw unavailable();
        }
        EnterpriseMemberContext locked = directory.findActiveMemberForUpdate(scope.userId(), scope.enterpriseId(),
                scope.userAuthVersion());
        if (!scope.equals(locked)) {
            throw unavailable();
        }
    }

    private CreatedEmployee insertAccount(long enterpriseId, NewEmployeeRequest request, String role) {
        if (request == null || request.username() == null || !request.username().matches("[A-Za-z0-9_.@-]{3,64}")
                || request.password() == null || request.password().length() < 12
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72 || request.password().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "账号须为3至64位字母数字或_.@-；口令须至少12字符且不超过72字节");
        }
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwords.encode(request.password()));
        user.setStatus(0);
        user.setAuthVersion(1L);
        try {
            users.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "账号无法创建，请更换账号名称");
        }
        SysEnterpriseMember member = new SysEnterpriseMember();
        member.setEnterpriseId(enterpriseId);
        member.setUserId(user.getId());
        member.setRole(role);
        member.setStatus(0);
        member.setPermissionVersion(1L);
        directory.insert(member);
        return new CreatedEmployee(user.getId(), member.getId(), enterpriseId);
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.NOT_FOUND, "成员管理入口不存在或不可访问");
    }
}
