package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeWorkspaceMapper;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.ContextQuestion;
import com.ragqa.rag.chat.TaskDeadline;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 多轮话题线索的私有读取入口；原问题是不可信输入，不能当作制度依据或外发许可。 */
@Service
public class KnowledgeConversationContextService {
    private final KnowledgeWorkspaceMapper mapper;
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;

    public record Context(String requestId, String chatId, String question, List<ContextQuestion> previous) {
        public Context { previous = List.copyOf(previous); }
        @Override public String toString() {
            return "Context[requestId=" + requestId + ", previousCount=" + previous.size() + ", 正文不记录]";
        }
    }

    public KnowledgeConversationContextService(KnowledgeWorkspaceMapper mapper, EnterpriseMemberService members,
            EnterpriseResourceService resources) {
        this.mapper = mapper;
        this.members = members;
        this.resources = resources;
    }

    /** 复用父预算；按受理时刻限定历史范围，不依赖后台完成顺序，不增加HTTP入口。 */
    public Context read(AuthenticatedUserPrincipal user, Long enterpriseId, long spaceId, String requestId,
            TaskDeadline deadline) {
        try {
            if (spaceId <= 0 || requestId == null || !UUID.fromString(requestId).toString().equals(requestId))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "空间或任务编号格式不正确");
        }
        deadline.timeoutFor(Duration.ofSeconds(2));
        var member = members.requireMember(user, enterpriseId);
        resources.requireSpace(member, spaceId);
        deadline.timeoutFor(Duration.ofSeconds(2));
        var current = mapper.job(member, spaceId, requestId);
        if (current == null) throw new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问");
        deadline.timeoutFor(Duration.ofSeconds(2));
        var candidates = mapper.contextQuestions(member, spaceId, requestId);
        var previous = new ArrayList<ContextQuestion>();
        int length = 0;
        for (var candidate : candidates) {
            // 保留完整问题，预算不足停止；不截断否定、金额、对象等约束，也不跳回更旧话题。
            if (previous.size() == 4 || length + candidate.question().length() > 2400) break;
            previous.add(candidate);
            length += candidate.question().length();
        }
        Collections.reverse(previous);
        deadline.timeoutFor(Duration.ofSeconds(2));
        members.requireCurrent(member);
        resources.requireSpace(member, spaceId);
        deadline.timeoutFor(Duration.ofSeconds(2));
        return new Context(current.id(), current.chatId(), current.question(), previous);
    }
}
