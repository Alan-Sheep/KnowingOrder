package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.Chat;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.Feedback;
import com.ragqa.service.KnowledgeWorkspaceService;
import com.ragqa.service.KnowledgeWorkspaceService.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 企业会话、任务和反馈共用可信Session及CSRF；不接受客户端身份或模型外发许可。 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}/workspace")
public class KnowledgeWorkspaceController {
    private final KnowledgeWorkspaceService service;
    public KnowledgeWorkspaceController(KnowledgeWorkspaceService service) { this.service = service; }

    @NoPayloadLog
    @GetMapping("/capabilities")
    public Result<Map<String, Boolean>> capabilities(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId) {
        // 即使迁移尚未完成，也先验证当前身份/空间，不能用此入口探测其他企业。
        service.chats(user, enterpriseId, spaceId);
        return Result.ok(Map.of("persistentTasks", service.available()));
    }
    @NoPayloadLog
    @GetMapping("/chats")
    public Result<List<Chat>> chats(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId) {
        return Result.ok(service.chats(user, enterpriseId, spaceId));
    }
    @NoPayloadLog
    @GetMapping("/chats/{chatId}")
    public Result<List<Job>> history(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @PathVariable String chatId) {
        return Result.ok(service.history(user, enterpriseId, spaceId, chatId));
    }
    /** 删除本人会话：软删除，任务与反馈留作审计；重复删除幂等成功。 */
    @NoPayloadLog
    @DeleteMapping("/chats/{chatId}")
    public Result<Void> deleteChat(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @PathVariable String chatId) {
        service.deleteChat(user, enterpriseId, spaceId, chatId);
        return Result.ok(null);
    }
    @NoPayloadLog
    @PostMapping("/jobs")
    public Result<Job> submit(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @RequestBody Submit request) {
        return Result.ok(service.submit(user, enterpriseId, spaceId, request));
    }
    @NoPayloadLog
    @GetMapping("/jobs/{id}")
    public Result<Job> job(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @PathVariable String id) {
        return Result.ok(service.job(user, enterpriseId, spaceId, id));
    }
    @NoPayloadLog
    @PostMapping("/jobs/{id}/cancel")
    public Result<Job> cancel(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @PathVariable String id) {
        return Result.ok(service.cancel(user, enterpriseId, spaceId, id));
    }
    @NoPayloadLog
    @PostMapping("/feedback")
    public Result<Feedback> feedback(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @RequestBody FeedbackRequest request) {
        return Result.ok(service.feedback(user, enterpriseId, spaceId, request));
    }
    @NoPayloadLog
    @GetMapping("/feedback")
    public Result<List<Feedback>> feedbacks(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId) {
        return Result.ok(service.feedbacks(user, enterpriseId, spaceId));
    }
    @NoPayloadLog
    @PostMapping("/feedback/{id}/resolve")
    public Result<Void> resolve(@AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterpriseId, @PathVariable long spaceId, @PathVariable String id, @RequestBody Resolution request) {
        service.resolve(user, enterpriseId, spaceId, id, request);
        return Result.ok();
    }
}
