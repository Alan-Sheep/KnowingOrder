package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.EnterpriseOrganizationMapper;
import com.ragqa.mapper.EnterpriseOrganizationMapper.Department;
import com.ragqa.mapper.EnterpriseOrganizationMapper.Member;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Objects;

/** 平级组织治理：管理员身份不产生私有知识读取权，不提供管理员角色变更或删除。 */
@Service
public class EnterpriseOrganizationService {
    public record Departments(long enterprisePermissionVersion, PageResult<Department> page) { }
    public record Members(PageResult<Member> page) { }
    public record DepartmentResult(long enterprisePermissionVersion, Department department) { }
    private final EnterpriseMemberService members;
    private final EnterpriseDirectoryMapper directory;
    private final EnterpriseOrganizationMapper data;
    private final TransactionTemplate tx;

    public EnterpriseOrganizationService(EnterpriseMemberService members, EnterpriseDirectoryMapper directory,
            EnterpriseOrganizationMapper data, PlatformTransactionManager manager) {
        this.members = members; this.directory = directory; this.data = data;
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    public Departments departments(AuthenticatedUserPrincipal user, long enterprise, long page, long size) {
        long offset = offset(page, size);
        return tx.execute(s -> {
            var scope = admin(user, enterprise);
            return new Departments(scope.enterprisePermissionVersion(), page(data.departments(enterprise, size, offset),
                    data.departmentCount(enterprise), page, size));
        });
    }
    public Members members(AuthenticatedUserPrincipal user, long enterprise, long page, long size) {
        long offset = offset(page, size);
        return tx.execute(s -> {
            admin(user, enterprise);
            return new Members(page(data.members(enterprise, size, offset), data.memberCount(enterprise), page, size));
        });
    }
    public DepartmentResult create(AuthenticatedUserPrincipal user, long enterprise, long expected, String name) {
        String clean = name(name);
        return tx.execute(s -> {
            var scope = admin(user, enterprise); expected(scope, expected);
            unique(enterprise, clean, 0);
            var row = new EnterpriseOrganizationMapper.NewDepartment(); row.setEnterpriseId(enterprise); row.setName(clean);
            if (data.insertDepartment(row) != 1) throw conflict();
            advance(scope);
            return new DepartmentResult(expected + 1, new Department(row.getId(), clean, 0));
        });
    }
    public DepartmentResult change(AuthenticatedUserPrincipal user, long enterprise, long id, long expected, String name, int status) {
        positive(id); String clean = name(name); if (status < 0 || status > 1) throw invalid();
        return tx.execute(s -> {
            var scope = admin(user, enterprise); expected(scope, expected);
            var old = data.department(enterprise, id); if (old == null) throw missing();
            if (old.name().equals(clean) && old.status() == status) return new DepartmentResult(expected, old);
            unique(enterprise, clean, id);
            // 不隐式移出员工或撤销其他空间授权；避免重新启用时旧授权意外复活。
            if (status == 1 && old.status() == 0 && (data.departmentMember(enterprise, id) != null || data.departmentGrant(enterprise, id) != null))
                throw new BusinessException(ResultCode.CONFLICT, "请先移出部门全部成员并撤销部门知识授权，再停用");
            if (data.updateDepartment(enterprise, id, clean, status) != 1) throw conflict();
            advance(scope); return new DepartmentResult(expected + 1, new Department(id, clean, status));
        });
    }
    public Member changeMember(AuthenticatedUserPrincipal user, long enterprise, long id, long expected, Long department, int status) {
        positive(id); positive(expected); if (department != null) positive(department);
        if (status < 0 || status > 2) throw invalid();
        return tx.execute(s -> {
            admin(user, enterprise);
            var old = data.member(enterprise, id);
            if (old == null || !"EMPLOYEE".equals(old.role())) throw missing();
            if (old.permissionVersion() != expected) throw conflict();
            if (department != null) {
                var target = data.department(enterprise, department);
                if (target == null || target.status() != 0) throw missing();
            }
            if (Objects.equals(old.departmentId(), department) && old.status() == status) return old;
            if (data.updateMember(enterprise, id, department, status, expected) != 1) throw conflict();
            return data.member(enterprise, id);
        });
    }
    private EnterpriseMemberContext admin(AuthenticatedUserPrincipal user, long enterprise) {
        var scope = members.requireMember(user, enterprise);
        if (!"ADMIN".equals(scope.role()) || !scope.equals(directory.findActiveMemberForUpdate(scope.userId(), enterprise, scope.userAuthVersion())))
            throw missing();
        return scope;
    }
    private void advance(EnterpriseMemberContext scope) {
        if (data.advanceEnterprise(scope.enterpriseId(), scope.enterprisePermissionVersion()) != 1) throw conflict();
    }
    private void expected(EnterpriseMemberContext scope, long value) {
        positive(value); if (value != scope.enterprisePermissionVersion()) throw conflict();
    }
    private void unique(long enterprise, String name, long except) {
        if (data.sameName(enterprise, name, except) != 0) throw new BusinessException(ResultCode.CONFLICT, "部门名称已存在，请刷新核对");
    }
    private String name(String value) {
        if (value == null) throw invalid();
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 128 || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) throw invalid();
        return clean;
    }
    private long offset(long page, long size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid();
        return (page - 1) * size;
    }
    private <T> PageResult<T> page(List<T> rows, long total, long number, long size) {
        var result = new Page<T>(number, size, total); result.setRecords(rows); return PageResult.of(result);
    }
    private void positive(long value) { if (value <= 0 || value == Long.MAX_VALUE) throw invalid(); }
    private BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "组织管理参数不正确"); }
    private BusinessException missing() { return new BusinessException(ResultCode.NOT_FOUND, "组织或成员不存在或不可管理"); }
    private BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "组织信息已变化，请刷新后重新确认"); }
}
