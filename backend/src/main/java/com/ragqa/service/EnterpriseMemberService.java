package com.ragqa.service;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import java.security.Principal;
import java.util.List;

/** 企业编号只是选择条件；成员及权限信息始终由服务端查询。 */
public interface EnterpriseMemberService {
    /** 仅供当前账号选择工作区，不能代替后续每次资源授权检查。 */
    record Workspace(Long enterpriseId, String enterpriseName, String role) { }

    List<Workspace> listWorkspaces(Principal principal);

    EnterpriseMemberContext requireMember(Principal principal, Long enterpriseId);

    /** 异步执行或读取敏感资源前重查，拒绝停用、离职及过期权限快照。 */
    EnterpriseMemberContext requireCurrent(EnterpriseMemberContext context);
}
