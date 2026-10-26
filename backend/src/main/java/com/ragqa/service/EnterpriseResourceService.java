package com.ragqa.service;

import com.ragqa.common.PageResult;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;

/** 所有入口先取得可信成员，再调用此授权层；异步敏感阶段同样复用，不能只传资源编号。 */
public interface EnterpriseResourceService {
    EnterpriseResources.Conversation requireConversation(EnterpriseMemberContext member, Long id);

    EnterpriseResources.Message requireMessage(EnterpriseMemberContext member, Long id);

    EnterpriseResources.KnowledgeSpace requireSpace(EnterpriseMemberContext member, Long id);

    /** 分页列出当前授权空间；页码从1开始，条数为1至500，不接受客户端排序或分页对象。 */
    PageResult<EnterpriseResources.KnowledgeSpace> listSpaces(EnterpriseMemberContext member, Long pageNum,
            Long pageSize);

    EnterpriseResources.Document requireDocument(EnterpriseMemberContext member, Long id);

    EnterpriseResources.TaskReference requireTask(EnterpriseMemberContext member, Long id);
}
