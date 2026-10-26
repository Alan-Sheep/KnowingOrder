package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.CreateKnowledgeSpaceRequest;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.service.KnowledgeSpaceService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 创建入口仅绑定登录身份、企业选择条件及名称，事务和权限复核由服务层负责。 */
@RestController
public class KnowledgeSpaceController {
    private final KnowledgeSpaceService spaces;

    public KnowledgeSpaceController(KnowledgeSpaceService spaces) {
        this.spaces = spaces;
    }

    @NoPayloadLog
    @PostMapping("/api/enterprises/{enterpriseId}/knowledge-spaces")
    public Result<EnterpriseResources.KnowledgeSpace> create(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @Valid @RequestBody CreateKnowledgeSpaceRequest request) {
        return Result.ok(spaces.createSpace(user, enterpriseId, request.name().textValue()));
    }
}
