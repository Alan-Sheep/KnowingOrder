package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.NewEmployeeRequest;

/** 管理员开户与员工状态管理；没有公开注册和自动关联已有同名账号的入口。 */
public interface EnterpriseAccountService {
    record CreatedEmployee(Long userId, Long memberId, Long enterpriseId) {
    }

    CreatedEmployee createEmployee(AuthenticatedUserPrincipal principal, Long enterpriseId, NewEmployeeRequest request);

    void setEmployeeStatus(AuthenticatedUserPrincipal principal, Long enterpriseId, Long memberId, int status);

    void revokeOwnSessions(AuthenticatedUserPrincipal principal);

    CreatedEmployee bootstrapFirstAdmin(String enterpriseName, NewEmployeeRequest request);
}
