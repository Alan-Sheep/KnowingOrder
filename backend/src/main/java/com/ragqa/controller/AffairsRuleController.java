package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.PageResult;
import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsRuleMapper;
import com.ragqa.service.AffairsRuleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 补贴规则入口：金额一律以分为单位，JSON 不接受字符串或小数被隐式转换。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/affairs")
public class AffairsRuleController {

    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;

    private final AffairsRuleService service;

    public AffairsRuleController(AffairsRuleService service) {
        this.service = service;
    }

    public record RuleRequest(String name, String period, JsonNode memberLimitCents,
            JsonNode applicationLimitCents, JsonNode requiresManager) { }

    public record EligibilityRequest(JsonNode ruleId, JsonNode amountCents) { }

    @NoPayloadLog
    @GetMapping("/rules")
    public Result<PageResult<AffairsRuleMapper.Rule>> rules(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.rules(user, enterprise, status, pageNum, pageSize));
    }

    @NoPayloadLog
    @PostMapping("/rules")
    public Result<AffairsRuleMapper.Rule> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestBody RuleRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.create(user, enterprise, request.name(), request.period(),
                amount(request.memberLimitCents()), requiredAmount(request.applicationLimitCents()),
                flag(request.requiresManager())));
    }

    @NoPayloadLog
    @PostMapping("/rules/{id}/activate")
    public Result<AffairsRuleMapper.Rule> activate(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long id) {
        return Result.ok(service.activate(user, enterprise, id));
    }

    @NoPayloadLog
    @GetMapping("/members")
    public Result<java.util.List<AffairsRuleMapper.MemberOption>> members(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise) {
        return Result.ok(service.memberOptions(user, enterprise));
    }

    @NoPayloadLog
    @PostMapping("/eligibility")
    public Result<AffairsRuleService.Eligibility> eligibility(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestBody EligibilityRequest request) {
        if (request == null || request.ruleId() == null || request.amountCents() == null) throw invalid();
        return Result.ok(service.eligibility(user, enterprise, positive(request.ruleId()),
                requiredAmount(request.amountCents())));
    }

    private long positive(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            long parsed = value.longValue();
            if (parsed > 0) return parsed;
            throw invalid();
        }
        if (value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(value.textValue());
            } catch (NumberFormatException ex) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private long requiredAmount(JsonNode value) {
        Long parsed = amount(value);
        if (parsed == null) throw invalid();
        return parsed;
    }

    /** 分金额只接受正整数或纯数字字符串；空值仅用于“不限额度”的成员上限。 */
    private Long amount(JsonNode value) {
        if (value == null || value.isNull()) return null;
        long parsed;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            parsed = value.longValue();
        } else if (value.isTextual() && value.textValue().matches("[1-9][0-9]{0,9}")) {
            try {
                parsed = Long.parseLong(value.textValue());
            } catch (NumberFormatException ex) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        if (parsed <= 0 || parsed > MAX_AMOUNT_CENTS) throw invalid();
        return parsed;
    }

    private boolean flag(JsonNode value) {
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "补贴规则参数类型不正确");
    }
}
