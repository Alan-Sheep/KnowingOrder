package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseResources;

/** 知识空间创建用例；成员身份由服务端复核，不接受客户端指定创建者或维护权限。 */
public interface KnowledgeSpaceService {

    /** 创建空间并明确授予创建者维护权；任一步失败均不得留下部分写入。 */
    EnterpriseResources.KnowledgeSpace createSpace(AuthenticatedUserPrincipal principal, Long enterpriseId,
            String name);
}
