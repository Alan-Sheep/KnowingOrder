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
import com.ragqa.service.KnowledgeVectorJobService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 本人向量构建任务入口；仅受理、读取和取消，VERIFIED不代表索引已发布。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/knowledge-spaces/{space}/documents/{document}/versions/{version}/vector-jobs")
public class KnowledgeVectorJobController {
    private final KnowledgeVectorJobService service;
    private final ObjectReader reader;

    public KnowledgeVectorJobController(KnowledgeVectorJobService service, ObjectMapper json) {
        this.service = service;
        reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /** BIGINT编号及预期修订精确传输；计数和首次任务时刻沿用原服务契约。 */
    public record Job(String id, Long documentId, Long versionId, String expectedRevision, String status,
            int expectedChunks, int verifiedChunks, long createdMs, long deadlineMs, Long finishedMs,
            String failureCode, String message) { }

    @NoPayloadLog @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Job> submit(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @RequestBody String body) {
        JsonNode request = object(body);
        if (request.size() != 2 || !request.has("requestId") || !request.has("expectedRevision")
                || !request.get("requestId").isTextual()) throw invalid();
        return Result.ok(view(service.submit(user, enterprise, space, document, version,
                new KnowledgeVectorJobService.Submit(request.get("requestId").textValue(), revision(request.get("expectedRevision"))))));
    }

    @NoPayloadLog @GetMapping("/{id}")
    public Result<Job> read(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @PathVariable String id) {
        return Result.ok(view(service.read(user, enterprise, space, document, version, id)));
    }

    @NoPayloadLog @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Job> cancel(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @PathVariable String id, @RequestBody String body) {
        if (!object(body).isEmpty()) throw invalid();
        return Result.ok(view(service.cancel(user, enterprise, space, document, version, id)));
    }

    private JsonNode object(String body) {
        try {
            JsonNode request = reader.readTree(body);
            if (request == null || !request.isObject()) throw invalid();
            return request;
        } catch (JsonProcessingException ex) { throw invalid(); }
    }

    private long revision(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value.textValue()); }
        catch (NumberFormatException ex) { throw invalid(); }
    }

    private Job view(KnowledgeVectorJobService.Job job) {
        return new Job(job.id(), job.documentId(), job.versionId(), Long.toString(job.expectedRevision()), job.status(),
                job.expectedChunks(), job.verifiedChunks(), job.createdMs(), job.deadlineMs(), job.finishedMs(), job.failureCode(), job.message());
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "索引受理仅接受请求编号和正整数的字符串修订号；取消仅接受空JSON对象");
    }
}
