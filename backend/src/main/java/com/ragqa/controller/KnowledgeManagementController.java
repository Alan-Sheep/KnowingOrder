package com.ragqa.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.service.KnowledgeManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 知识负责人维护入口，身份只取自Session；所有写操作仍由Security校验CSRF。 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}")
public class KnowledgeManagementController {
    private final KnowledgeManagementService service;
    public KnowledgeManagementController(KnowledgeManagementService service) { this.service = service; }

    @NoPayloadLog
    @PostMapping("/rename")
    public Result<EnterpriseResources.KnowledgeSpace> rename(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @Valid @RequestBody Rename request) {
        return Result.ok(service.rename(user, enterpriseId, spaceId, request.expectedVersion(), request.name().textValue()));
    }

    @NoPayloadLog
    @GetMapping("/grants")
    public Result<List<KnowledgeManagementMapper.Grant>> grants(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId) {
        return Result.ok(service.grants(user, enterpriseId, spaceId));
    }

    @NoPayloadLog
    @PostMapping("/grants")
    public Result<EnterpriseResources.KnowledgeSpace> grant(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @Valid @RequestBody Grant request) {
        return Result.ok(service.changeGrant(user, enterpriseId, spaceId, request.expectedVersion(), request.targetType(),
                request.targetId(), request.permission(), request.revoke()));
    }

    public record Rename(@NotNull Long expectedVersion, @NotNull JsonNode name) {
        @AssertTrue(message = "空间名称必须为字符串")
        public boolean isTextName() { return name == null || name.isTextual(); }
    }
    public record Grant(@NotNull Long expectedVersion, String targetType, Long targetId, String permission, boolean revoke) { }
}
