package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.NewEmployeeRequest;
import com.ragqa.service.EnterpriseAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 账号管理员入口，与员工私有内容读取权限分开；不提供公开注册。 */
@RestController
public class EnterpriseAccountController {
    private final EnterpriseAccountService accounts;

    public EnterpriseAccountController(EnterpriseAccountService accounts) {
        this.accounts = accounts;
    }

    @NoPayloadLog
    @PostMapping("/api/enterprises/{enterpriseId}/employees")
    public Result<EnterpriseAccountService.CreatedEmployee> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @RequestBody NewEmployeeRequest request) {
        return Result.ok(accounts.createEmployee(user, enterpriseId, request));
    }

    public record MemberStatusRequest(@NotNull(message = "成员状态不能为空") JsonNode status) {
    }

    @NoPayloadLog
    @PostMapping("/api/enterprises/{enterpriseId}/employees/{memberId}/status")
    public Result<Void> status(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long memberId, @Valid @RequestBody MemberStatusRequest request) {
        // 保留JSON原始类型，拒绝把1.5、字符串或布尔值强制转换成合法状态。
        if (!request.status().isInt()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "成员状态必须为0、1或2整数");
        }
        accounts.setEmployeeStatus(user, enterpriseId, memberId, request.status().intValue());
        return Result.ok();
    }

    @NoPayloadLog
    @PostMapping("/api/auth/revoke-sessions")
    public Result<Void> revoke(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user) {
        accounts.revokeOwnSessions(user);
        return Result.ok();
    }
}
