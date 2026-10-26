package com.ragqa.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.service.KnowledgeVectorPublicationService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 索引发布维护入口；身份只取Session，资源授权和发布核验复用服务层，正文不进入请求日志。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/knowledge-spaces/{space}/documents/{document}/vector-publication")
public class KnowledgeVectorPublicationController {
    private final KnowledgeVectorPublicationService service;
    private final ObjectReader reader;

    public KnowledgeVectorPublicationController(KnowledgeVectorPublicationService service, ObjectMapper json) {
        this.service = service;
        // 仅本入口严格解析，不改变已有接口的Jackson行为；重复键和尾随JSON均拒绝。
        reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /** 修订号也以十进制字符串收发，避免浏览器对BIGINT静默舍入后提交错误的CAS条件。 */
    public record Publication(Long documentId, String jobId, String revision, String status, Long updatedMs, String message) { }

    @NoPayloadLog @GetMapping
    public Result<Publication> read(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document) {
        return Result.ok(view(service.read(user, enterprise, space, document)));
    }

    @NoPayloadLog @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Publication> publish(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document, @RequestBody String body) {
        JsonNode request = request(body, "jobId");
        return Result.ok(view(service.publish(user, enterprise, space, document,
                new KnowledgeVectorPublicationService.Publish(job(request.get("jobId"), false), revision(request)))));
    }

    @NoPayloadLog @PostMapping(value = "/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Publication> withdraw(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document, @RequestBody String body) {
        JsonNode request = request(body, "expectedJobId");
        return Result.ok(view(service.withdraw(user, enterprise, space, document,
                new KnowledgeVectorPublicationService.Withdraw(job(request.get("expectedJobId"), true), revision(request)))));
    }

    private JsonNode request(String body, String jobField) {
        try {
            JsonNode value = reader.readTree(body);
            if (value == null || !value.isObject() || value.size() != 2
                    || !value.has(jobField) || !value.has("expectedRevision")) throw invalid();
            return value;
        } catch (JsonProcessingException ex) { throw invalid(); }
    }

    private String job(JsonNode value, boolean nullable) {
        if (nullable && value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    private long revision(JsonNode request) {
        JsonNode value = request.get("expectedRevision");
        if (!value.isTextual() || !value.textValue().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            long result = Long.parseLong(value.textValue());
            if (result == Long.MAX_VALUE) throw invalid();
            return result;
        } catch (NumberFormatException ex) { throw invalid(); }
    }

    private Publication view(KnowledgeVectorPublicationService.State state) {
        return new Publication(state.documentId(), state.jobId(), Long.toString(state.revision()),
                state.status(), state.updatedMs(), state.message());
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "索引发布参数不合法：仅接受任务编号和十进制字符串预期修订号");
    }
}
