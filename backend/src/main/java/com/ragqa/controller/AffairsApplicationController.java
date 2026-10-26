package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.PageResult;
import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsApplicationMapper;
import com.ragqa.service.AffairsApplicationService;
import com.ragqa.service.AffairsApplicationService.Share;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** 补贴申请入口：金额以分为单位；JSON 中的字符串、小数和布尔不被隐式转换为金额。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/affairs")
public class AffairsApplicationController {

    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AffairsApplicationService service;

    public AffairsApplicationController(AffairsApplicationService service) {
        this.service = service;
    }

    public record CreateRequest(String id, JsonNode ruleId, String platform, String externalOrderNo,
            String occurredAt, JsonNode amountCents, JsonNode participants) { }

    public record ReviseRequest(JsonNode expectedVersion, JsonNode amountCents, JsonNode participants, String reason) { }

    public record ConfirmRequest(JsonNode version, String decision, String note) { }

    @NoPayloadLog
    @PostMapping("/applications")
    public Result<AffairsApplicationService.View> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestBody CreateRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.create(user, enterprise, request.id(), positive(request.ruleId()),
                request.platform(), request.externalOrderNo(), time(request.occurredAt()),
                requiredAmount(request.amountCents()), shares(request.participants())));
    }

    @NoPayloadLog
    @GetMapping("/applications")
    public Result<PageResult<AffairsApplicationMapper.Summary>> mine(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.mine(user, enterprise, status, pageNum, pageSize));
    }

    @NoPayloadLog
    @GetMapping("/applications/{id}")
    public Result<AffairsApplicationService.View> detail(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id) {
        return Result.ok(service.detail(user, enterprise, id));
    }

    @NoPayloadLog
    @PostMapping("/applications/{id}/revise")
    public Result<AffairsApplicationService.View> revise(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id, @RequestBody ReviseRequest request) {
        if (request == null) throw invalid();
        return Result.ok(service.revise(user, enterprise, id, version(request.expectedVersion()),
                requiredAmount(request.amountCents()), shares(request.participants()), request.reason()));
    }

    @NoPayloadLog
    @PostMapping("/applications/{id}/cancel")
    public Result<AffairsApplicationService.View> cancel(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id) {
        return Result.ok(service.cancel(user, enterprise, id));
    }

    @NoPayloadLog
    @PostMapping("/applications/{id}/confirm")
    public Result<AffairsApplicationService.View> confirm(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id, @RequestBody ConfirmRequest request) {
        if (request == null) throw invalid();
        boolean accept = decision(request.decision());
        return Result.ok(service.confirm(user, enterprise, id, version(request.version()), accept, request.note()));
    }

    @NoPayloadLog
    @GetMapping("/confirmations")
    public Result<PageResult<AffairsApplicationMapper.Pending>> pendingForMe(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.pendingForMe(user, enterprise, pageNum, pageSize));
    }

    private List<Share> shares(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 20) throw invalid();
        List<Share> shares = new ArrayList<>();
        for (JsonNode node : value) {
            if (node == null || !node.isObject()) throw invalid();
            JsonNode member = node.get("memberId");
            JsonNode share = node.get("shareCents");
            if (member == null || share == null) throw invalid();
            shares.add(new Share(positive(member), shareAmount(share)));
        }
        return shares;
    }

    private long positive(JsonNode value) {
        return parsed(value, 1L, Long.MAX_VALUE - 1);
    }

    private long requiredAmount(JsonNode value) {
        return parsed(value, 1L, MAX_AMOUNT_CENTS);
    }

    /** 分摊允许 0（申请人自留），但不超过金额上限。 */
    private long shareAmount(JsonNode value) {
        return parsed(value, 0L, MAX_AMOUNT_CENTS);
    }

    /** 只接受整数或纯数字字符串；范围由调用方给定。 */
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

    private int version(JsonNode value) {
        long number = positive(value);
        if (number > Integer.MAX_VALUE) throw invalid();
        return (int) number;
    }

    private boolean decision(String value) {
        if (value == null) throw invalid();
        String clean = value.strip();
        if ("CONFIRM".equals(clean)) return true;
        if ("REJECT".equals(clean)) return false;
        throw invalid();
    }

    private LocalDateTime time(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.strip(), TIME);
        } catch (DateTimeParseException ex) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "补贴申请参数类型不正确");
    }
}
