package com.ragqa.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageQuery;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseResourceMapper;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

/** 成员复核与资源联合查询双层约束；不存在和无权统一提示，不缓存授权结果。 */
@Service
public class EnterpriseResourceServiceImpl implements EnterpriseResourceService {
    private final EnterpriseMemberService members;
    private final EnterpriseResourceMapper resources;

    public EnterpriseResourceServiceImpl(EnterpriseMemberService members, EnterpriseResourceMapper resources) {
        this.members = members;
        this.resources = resources;
    }

    @Override
    public EnterpriseResources.Conversation requireConversation(EnterpriseMemberContext member, Long id) {
        return require(member, id, resources::findConversation);
    }

    @Override
    public EnterpriseResources.Message requireMessage(EnterpriseMemberContext member, Long id) {
        return require(member, id, resources::findMessage);
    }

    @Override
    public EnterpriseResources.KnowledgeSpace requireSpace(EnterpriseMemberContext member, Long id) {
        return require(member, id, resources::findSpace);
    }

    @Override
    public PageResult<EnterpriseResources.KnowledgeSpace> listSpaces(EnterpriseMemberContext member, Long pageNum,
            Long pageSize) {
        EnterpriseMemberContext current = members.requireCurrent(member);
        if (pageNum == null || pageNum <= 0 || pageSize == null || pageSize <= 0
                || pageSize > PageQuery.MAX_PAGE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "页码必须为正整数，每页条数须为1至" + PageQuery.MAX_PAGE_SIZE);
        }
        // 先除后比较，避免(pageNum - 1) * pageSize溢出后回绕到其他页或变成负数。
        if (pageNum - 1 > Long.MAX_VALUE / pageSize) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "页码过大，请减小页码或每页条数");
        }
        // 不接收外部Page或排序字段；排序仅使用授权Mapper固定的主键升序。
        Page<EnterpriseResources.KnowledgeSpace> page = new Page<>(pageNum, pageSize);
        return PageResult.of(resources.findSpaces(page, current));
    }

    @Override
    public EnterpriseResources.Document requireDocument(EnterpriseMemberContext member, Long id) {
        return require(member, id, resources::findDocument);
    }

    @Override
    public EnterpriseResources.TaskReference requireTask(EnterpriseMemberContext member, Long id) {
        return require(member, id, resources::findTask);
    }

    private <T> T require(EnterpriseMemberContext member, Long id,
            BiFunction<EnterpriseMemberContext, Long, T> query) {
        EnterpriseMemberContext current = members.requireCurrent(member);
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "资源编号必须为正整数");
        }
        T result = query.apply(current, id);
        if (result == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问");
        }
        return result;
    }
}
