package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.service.KnowledgeIngestJobService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 本人入库任务API：每次都核维护权，JSON统一Result；不暴露租约或原件键。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/knowledge-spaces/{space}/documents/{document}/versions/{version}/ingest-jobs")
public class KnowledgeIngestJobController {
    private final KnowledgeIngestJobService service;
    public KnowledgeIngestJobController(KnowledgeIngestJobService service) { this.service = service; }

    @NoPayloadLog @PostMapping
    public Result<KnowledgeIngestJobService.Job> submit(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @RequestBody KnowledgeIngestJobService.Submit request) {
        return Result.ok(service.submit(user, enterprise, space, document, version, request));
    }
    @NoPayloadLog @GetMapping
    public Result<List<KnowledgeIngestJobService.Job>> history(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document, @PathVariable long version) {
        return Result.ok(service.history(user, enterprise, space, document, version));
    }
    @NoPayloadLog @GetMapping("/{id}")
    public Result<KnowledgeIngestJobService.Job> read(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @PathVariable String id) {
        return Result.ok(service.read(user, enterprise, space, document, version, id));
    }
    @NoPayloadLog @PostMapping("/{id}/cancel")
    public Result<KnowledgeIngestJobService.Job> cancel(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable long space, @PathVariable long document,
            @PathVariable long version, @PathVariable String id) {
        return Result.ok(service.cancel(user, enterprise, space, document, version, id));
    }
}
