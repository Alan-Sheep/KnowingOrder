package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.service.AccountPasswordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 沿用私有Session及CSRF；密码不进入响应、日志或记录对象的字符串表示。 */
@RestController
public class AccountPasswordController {
    private final AccountPasswordService service;
    private final com.fasterxml.jackson.databind.ObjectReader reader;
    public AccountPasswordController(AccountPasswordService service, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.service = service;
        reader = json.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }
    @NoPayloadLog
    @PostMapping(value = "/api/auth/password", consumes = "application/json")
    public Result<Void> change(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal identity,
            @RequestBody String body, HttpServletRequest request) {
        com.fasterxml.jackson.databind.JsonNode input;
        try { input = reader.readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid(); }
        if (input == null || !input.isObject() || input.size() != 2 || !input.path("previousPassword").isTextual()
                || !input.path("newPassword").isTextual()) throw invalid();
        service.change(identity, input.path("previousPassword").textValue(), input.path("newPassword").textValue());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Result.ok();
    }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "改密请求须仅含原口令与新口令两个字符串字段"); }
}
