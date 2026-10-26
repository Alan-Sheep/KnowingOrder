package com.ragqa.domain.dto;

import java.util.Set;

/** 服务端核验后的最小上下文，不是认证证明；敏感操作仍须复核，不接受客户端绑定。 */
public record EnterpriseMemberContext(long enterpriseId, long memberId, long userId,
        Long departmentId, String role, long enterprisePermissionVersion, long memberPermissionVersion,
        long userAuthVersion) {

    public EnterpriseMemberContext {
        if (enterpriseId <= 0 || memberId <= 0 || userId <= 0
                || (departmentId != null && departmentId <= 0)
                || role == null || !Set.of("EMPLOYEE", "ADMIN", "FINANCE").contains(role)
                || enterprisePermissionVersion <= 0 || memberPermissionVersion <= 0 || userAuthVersion <= 0) {
            throw new IllegalArgumentException("企业成员上下文无效");
        }
    }
}
