package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.PageResult;
import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsApplicationMapper;
import com.ragqa.mapper.AffairsSettlementMapper;
import com.ragqa.service.AffairsSettlementService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 财务协同入口：金额以分为单位；收款人必须显式传入，不从申请推断。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/affairs")
public class AffairsSettlementController {

    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;

    private final AffairsSettlementService service;

    public AffairsSettlementController(AffairsSettlementService service) {
        this.service = service;
    }

    public record SettlementRequest(String applicationId, JsonNode payeeMemberId, String note) { }

    public record RefundRequest(String kind, JsonNode amountCents, String reason) { }

    @NoPayloadLog
    @PostMapping("/applications/{id}/manager-approve")
    public Result<AffairsApplicationMapper.Detail> approve(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id) {
        return Result.ok(service.approve(user, enterprise, id));
    }

    @NoPayloadLog
    @GetMapping("/finance/applications")
    public Result<PageResult<AffairsSettlementMapper.FinanceApplication>> financeApplications(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.financeApplications(user, enterprise, status, pageNum, pageSize));
    }

    @NoPayloadLog
    @GetMapping("/settlements")
    public Result<PageResult<AffairsSettlementMapper.Queue>> settlements(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.settlements(user, enterprise, status, pageNum, pageSize));
    }

    @NoPayloadLog
    @PostMapping("/settlements")
    public Result<AffairsSettlementMapper.SettlementView> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestBody SettlementRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.create(user, enterprise, request.applicationId(),
                positive(request.payeeMemberId()), request.note()));
    }

    @NoPayloadLog
    @PostMapping("/settlements/{id}/approve")
    public Result<AffairsSettlementMapper.SettlementView> approveSettlement(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long id) {
        return Result.ok(service.approveSettlement(user, enterprise, id));
    }

    @NoPayloadLog
    @PostMapping("/settlements/{id}/settle")
    public Result<AffairsSettlementMapper.SettlementView> settle(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long id) {
        return Result.ok(service.settleSettlement(user, enterprise, id));
    }

    @NoPayloadLog
    @PostMapping("/applications/{id}/refund")
    public Result<AffairsSettlementService.RefundResult> refund(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id, @RequestBody RefundRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.refund(user, enterprise, id, full(request.kind()),
                amount(request.amountCents()), request.reason()));
    }

    private boolean full(String kind) {
        if (kind == null) throw invalid();
        String clean = kind.strip();
        if ("FULL".equals(clean)) return true;
        if ("PARTIAL".equals(clean)) return false;
        throw invalid();
    }

    private long positive(JsonNode value) {
        return parsed(value, 1L, Long.MAX_VALUE - 1);
    }

    private long amount(JsonNode value) {
        return parsed(value, 1L, MAX_AMOUNT_CENTS);
    }

    private long parsed(JsonNode value, long min, long max) {
        long number;
        if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
            number = value.longValue();
        } else if (value != null && value.isTextual() && value.textValue().matches("[0-9]{1,19}")) {
            try {
                number = Long.parseLong(value.textValue());
            } catch (NumberFormatException ex) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        if (number < min || number > max) throw invalid();
        return number;
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "结算参数类型不正确");
    }
}
