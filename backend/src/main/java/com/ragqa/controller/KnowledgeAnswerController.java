package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.service.KnowledgeAnswerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Session身份与CSRF沿用企业私有接口；不接受客户端提供成员、权限或外发许可。 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}/answers")
public class KnowledgeAnswerController {
    private final KnowledgeAnswerService service;
    public KnowledgeAnswerController(KnowledgeAnswerService service) { this.service = service; }

    public record Request(@NotBlank @Size(max = 1200) String question) { }

    @NoPayloadLog
    @GetMapping("/mode")
    public Result<String> mode(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId) {
        return Result.ok(service.mode(user, enterpriseId, spaceId));
    }

    /** 返回KEYWORD/HYBRID，只说明服务端策略，不表示外发许可或索引可用。 */
    @NoPayloadLog
    @GetMapping("/retrieval-mode")
    public Result<String> retrievalMode(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId) {
        return Result.ok(service.retrievalMode(user, enterpriseId, spaceId));
    }

    @NoPayloadLog
    @PostMapping
    public Result<Answer> answer(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @Valid @RequestBody Request request) {
        return Result.ok(service.answer(user, enterpriseId, spaceId, request.question()));
    }

    @NoPayloadLog
    @GetMapping("/evidence/{chunkId}")
    public Result<Evidence> evidence(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long chunkId) {
        return Result.ok(service.evidence(user, enterpriseId, spaceId, chunkId));
    }
}
