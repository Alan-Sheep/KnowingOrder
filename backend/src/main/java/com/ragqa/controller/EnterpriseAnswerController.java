package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeAnswers.CrossAnswer;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.service.KnowledgeAnswerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 企业级跨空间问答：不再要求选择知识空间，一次提问覆盖当前账号全部授权空间。
 * 单空间接口保持不变；会话、任务与反馈仍属单空间能力，跨空间回答为单轮独立作答。
 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/answers")
public class EnterpriseAnswerController {
    private final KnowledgeAnswerService service;
    public EnterpriseAnswerController(KnowledgeAnswerService service) { this.service = service; }

    public record Request(@NotBlank @Size(max = 1200) String question) { }

    @NoPayloadLog
    @PostMapping
    public Result<CrossAnswer> answer(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @Valid @RequestBody Request request) {
        return Result.ok(service.answerAcross(user, enterpriseId, request.question()));
    }

    /** 证据详情按来源空间取回；授权由单空间证据链路服务端校验。 */
    @NoPayloadLog
    @GetMapping("/evidence/{spaceId}/{chunkId}")
    public Result<Evidence> evidence(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long chunkId) {
        return Result.ok(service.evidence(user, enterpriseId, spaceId, chunkId));
    }
}
