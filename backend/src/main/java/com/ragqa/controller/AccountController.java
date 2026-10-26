package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.service.CurrentUserService;
import com.ragqa.service.EnterpriseMemberService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 登录/退出由安全过滤器处理；此处仅提供CSRF初始化和当前账号的最小信息。 */
@RestController
@RequestMapping("/api/auth")
public class AccountController {
    private final CurrentUserService users;
    private final EnterpriseMemberService members;

    public AccountController(CurrentUserService users, EnterpriseMemberService members) {
        this.users = users;
        this.members = members;
    }

    @NoPayloadLog
    @GetMapping("/csrf")
    public Result<Map<String, String>> csrf(CsrfToken token) {
        return Result.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @NoPayloadLog
    @GetMapping("/me")
    public Result<Map<String, Object>> me(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal identity) {
        return Result.ok(Map.of("userId", users.requireUserId(identity),
                "enterprises", members.listWorkspaces(identity)));
    }
}
