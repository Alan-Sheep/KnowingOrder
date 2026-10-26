package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeManagementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 空间维护权限与账号管理权限分离；修改串行化并绑定页面读取的权限版本。 */
@Service
public class KnowledgeManagementService {
    private final EnterpriseMemberService members;
    private final KnowledgeManagementMapper mapper;

    public KnowledgeManagementService(EnterpriseMemberService members, KnowledgeManagementMapper mapper) {
        this.members = members;
        this.mapper = mapper;
    }

    @Transactional(timeout = 10)
    public EnterpriseResources.KnowledgeSpace rename(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long expectedVersion, String name) {
        var scope = members.requireMember(user, enterpriseId);
        if (expectedVersion == null) throw invalid("请提供空间权限版本");
        var current = lock(scope, spaceId, expectedVersion);
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 128) throw invalid("空间名称须为1至128个字符");
        if (mapper.rename(scope, spaceId, current.permissionVersion(), normalized) != 1) throw conflict();
        return new EnterpriseResources.KnowledgeSpace(spaceId, normalized, current.permissionVersion() + 1);
    }

    @Transactional(timeout = 10)
    public List<KnowledgeManagementMapper.Grant> grants(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId) {
        var scope = members.requireMember(user, enterpriseId);
        lock(scope, spaceId, null);
        return mapper.grants(scope, spaceId);
    }

    @Transactional(timeout = 10)
    public EnterpriseResources.KnowledgeSpace changeGrant(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long expectedVersion, String targetType, Long targetId, String permission, boolean revoke) {
        var scope = members.requireMember(user, enterpriseId);
        if (expectedVersion == null) throw invalid("请提供空间权限版本");
        var current = lock(scope, spaceId, expectedVersion);
        if (targetId == null || targetId <= 0 || !Set.of("MEMBER", "DEPARTMENT").contains(Objects.toString(targetType, "")))
            throw invalid("授权目标须为有效成员或直属部门编号");
        if (!revoke && !Set.of("READ", "MAINTAIN").contains(Objects.toString(permission, "")))
            throw invalid("权限须为READ或MAINTAIN");
        Long member = "MEMBER".equals(targetType) ? targetId : null;
        Long department = member == null ? targetId : null;
        // 不允许通过此入口剥夺本次操作者正在使用的维护途径，避免自锁后无法治理空间。
        if ((Objects.equals(member, scope.memberId()) || (department != null && Objects.equals(department, scope.departmentId())))
                && (revoke || !"MAINTAIN".equals(permission))) throw invalid("不能降级或撤销自己当前使用的维护授权");
        Long target = member != null ? mapper.lockMember(scope, targetId) : mapper.lockDepartment(scope, targetId);
        if (target == null) throw unavailable();
        var old = mapper.grant(scope, spaceId, member, department);
        int changed;
        if (revoke) {
            if (old == null) throw unavailable();
            changed = mapper.updateGrant(scope, spaceId, old.id(), old.permission(), 1);
        } else if (old == null) {
            if (mapper.grants(scope, spaceId).size() >= 500) throw invalid("单空间最多500条明确授权，请整理后再添加");
            changed = mapper.insertGrant(scope, spaceId, member, department, permission);
        } else {
            changed = mapper.updateGrant(scope, spaceId, old.id(), permission, 0);
        }
        if (changed != 1 || mapper.advance(scope, spaceId, current.permissionVersion()) != 1) throw conflict();
        return new EnterpriseResources.KnowledgeSpace(spaceId, current.name(), current.permissionVersion() + 1);
    }

    private EnterpriseResources.KnowledgeSpace lock(EnterpriseMemberContext scope, Long spaceId, Long version) {
        if (spaceId == null || spaceId <= 0 || (version != null && version <= 0)) throw invalid("编号或版本不合法");
        var current = mapper.lockMaintain(scope, spaceId);
        if (current == null) throw unavailable();
        if (current.permissionVersion() == Long.MAX_VALUE || (version != null && version != current.permissionVersion()))
            throw conflict();
        return current;
    }

    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
    private BusinessException unavailable() { return new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可维护"); }
    private BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "空间已发生变化，请重新读取后再操作"); }
}
