package com.ragqa.service.impl;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.domain.entity.RagKnowledgeSpace;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.KnowledgeSpaceMapper;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.KnowledgeSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 空间和创建者授权在同一事务内提交；只执行本地数据库操作，不持锁调用外部服务。 */
@Service
public class KnowledgeSpaceServiceImpl implements KnowledgeSpaceService {
    private final EnterpriseMemberService members;
    private final EnterpriseDirectoryMapper directory;
    private final KnowledgeSpaceMapper spaces;

    public KnowledgeSpaceServiceImpl(EnterpriseMemberService members, EnterpriseDirectoryMapper directory,
            KnowledgeSpaceMapper spaces) {
        this.members = members;
        this.directory = directory;
        this.spaces = spaces;
    }

    @Override
    @Transactional
    public EnterpriseResources.KnowledgeSpace createSpace(AuthenticatedUserPrincipal principal, Long enterpriseId,
            String name) {
        EnterpriseMemberContext scope = members.requireMember(principal, enterpriseId);
        if (!"ADMIN".equals(scope.role())) {
            throw unavailable();
        }
        String normalizedName = name == null ? "" : name.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 128) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "空间名称去除首尾空白后须为1至128个字符");
        }

        // 锁定后比较完整快照，拒绝成员初检与写入之间发生的撤权、停用或归属变化。
        EnterpriseMemberContext locked = directory.findActiveMemberForUpdate(scope.userId(), scope.enterpriseId(),
                scope.userAuthVersion());
        if (!scope.equals(locked)) {
            throw unavailable();
        }
        var space = new RagKnowledgeSpace();
        space.setEnterpriseId(scope.enterpriseId());
        space.setName(normalizedName);
        if (spaces.insertSpace(scope, space) != 1) {
            throw unavailable();
        }
        if (space.getId() == null || space.getId() <= 0) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
        // 只使用本事务新建的主键，不允许以创建接口给既有空间自授权。
        if (spaces.insertCreatorGrant(scope, space.getId()) != 1) {
            throw unavailable();
        }
        // SQL固定写入版本1；不捕获并吞掉运行时异常，让事务代理整体回滚。
        return new EnterpriseResources.KnowledgeSpace(space.getId(), normalizedName, 1L);
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.NOT_FOUND, "知识空间创建入口不存在或不可访问");
    }
}
