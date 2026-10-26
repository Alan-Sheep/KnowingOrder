package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.PageResult;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 企业只读资源入口；当前用户来自安全上下文，不接受用户/成员编号请求头。 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}")
public class EnterpriseResourceController {
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;

    public EnterpriseResourceController(EnterpriseMemberService members, EnterpriseResourceService resources) {
        this.members = members;
        this.resources = resources;
    }

    @NoPayloadLog
    @GetMapping("/conversations/{id}")
    public Result<EnterpriseResources.Conversation> conversation(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long id) {
        return Result.ok(resources.requireConversation(members.requireMember(user, enterpriseId), id));
    }

    @NoPayloadLog
    @GetMapping("/messages/{id}")
    public Result<EnterpriseResources.Message> message(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long id) {
        return Result.ok(resources.requireMessage(members.requireMember(user, enterpriseId), id));
    }

    @NoPayloadLog
    @GetMapping("/knowledge-spaces")
    public Result<PageResult<EnterpriseResources.KnowledgeSpace>> spaces(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId,
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "20") Long pageSize) {
        return Result.ok(resources.listSpaces(members.requireMember(user, enterpriseId), pageNum, pageSize));
    }

    @NoPayloadLog
    @GetMapping("/knowledge-spaces/{id}")
    public Result<EnterpriseResources.KnowledgeSpace> space(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long id) {
        return Result.ok(resources.requireSpace(members.requireMember(user, enterpriseId), id));
    }

    @NoPayloadLog
    @GetMapping("/documents/{id}")
    public Result<EnterpriseResources.Document> document(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long id) {
        return Result.ok(resources.requireDocument(members.requireMember(user, enterpriseId), id));
    }

    @NoPayloadLog
    @GetMapping("/tasks/{id}/reference")
    public Result<EnterpriseResources.TaskReference> taskReference(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long id) {
        return Result.ok(resources.requireTask(members.requireMember(user, enterpriseId), id));
    }
}
