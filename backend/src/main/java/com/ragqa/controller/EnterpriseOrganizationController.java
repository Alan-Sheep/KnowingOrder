package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseOrganizationMapper.Member;
import com.ragqa.service.EnterpriseOrganizationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 管理入口统一Result、服务端核权与CSRF；JSON不将小数/布尔/字符串强制转换为状态。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/organization")
public class EnterpriseOrganizationController {
    private final EnterpriseOrganizationService service;
    public EnterpriseOrganizationController(EnterpriseOrganizationService service) { this.service = service; }
    public record DepartmentRequest(JsonNode expectedEnterpriseVersion, String name, JsonNode status) { }
    public record MemberRequest(JsonNode expectedVersion, JsonNode departmentId, JsonNode status) { }
    @NoPayloadLog @GetMapping("/departments")
    public Result<EnterpriseOrganizationService.Departments> departments(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user, @PathVariable long enterprise,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.departments(user, enterprise, pageNum, pageSize));
    }
    @NoPayloadLog @GetMapping("/members")
    public Result<EnterpriseOrganizationService.Members> members(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user, @PathVariable long enterprise,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.members(user, enterprise, pageNum, pageSize));
    }
    @NoPayloadLog @PostMapping("/departments")
    public Result<EnterpriseOrganizationService.DepartmentResult> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user, @PathVariable long enterprise,
            @RequestBody DepartmentRequest request) {
        if (request == null || request.status() != null) throw invalid();
        return Result.ok(service.create(user, enterprise, positive(request.expectedEnterpriseVersion()), request.name()));
    }
    @NoPayloadLog @PostMapping("/departments/{id}")
    public Result<EnterpriseOrganizationService.DepartmentResult> change(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user, @PathVariable long enterprise,
            @PathVariable long id, @RequestBody DepartmentRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.change(user, enterprise, id, positive(request.expectedEnterpriseVersion()), request.name(), status(request.status(), 1)));
    }
    @NoPayloadLog @PostMapping("/members/{id}")
    public Result<Member> changeMember(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long id, @RequestBody MemberRequest request) {
        if (request == null || request.departmentId() == null) throw invalid();
        JsonNode value = request.departmentId();
        Long department = null;
        if (!value.isNull()) {
            if (!value.isTextual() || !value.textValue().matches("[1-9][0-9]{0,18}")) throw invalid();
            try { department = Long.valueOf(value.textValue()); } catch (NumberFormatException ex) { throw invalid(); }
        }
        return Result.ok(service.changeMember(user, enterprise, id, positive(request.expectedVersion()), department, status(request.status(), 2)));
    }
    private long positive(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    private int status(JsonNode value, int max) {
        if (value == null || !value.isInt() || value.intValue() < 0 || value.intValue() > max) throw invalid();
        return value.intValue();
    }
    private BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "组织管理参数类型不正确"); }
}
