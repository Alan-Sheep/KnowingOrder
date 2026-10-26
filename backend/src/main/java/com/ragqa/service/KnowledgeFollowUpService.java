package com.ragqa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeWorkspaceMapper;
import com.ragqa.rag.chat.FollowUpContract;
import com.ragqa.rag.chat.FollowUpResolver;
import com.ragqa.rag.chat.TaskDeadline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static com.ragqa.rag.chat.FollowUpResolver.Status.*;
import static com.ragqa.rag.chat.FollowUpResolver.active;
import static com.ragqa.rag.chat.FollowUpResolver.fallback;

/** 受理时快照与合成空间追问编排；开关关闭时不访问新增表，也不改变已有问答。 */
@Service
public class KnowledgeFollowUpService {
    private final KnowledgeWorkspaceMapper mapper;
    private final KnowledgeConversationContextService contexts;
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final KnowledgeAnswerService answers;
    private final FollowUpResolver resolver;
    private final ObjectMapper json;
    private final boolean enabled;

    public KnowledgeFollowUpService(KnowledgeWorkspaceMapper mapper, KnowledgeConversationContextService contexts,
            EnterpriseMemberService members, EnterpriseResourceService resources, KnowledgeAnswerService answers,
            FollowUpResolver resolver, ObjectMapper json,
            @Value("${knowingorder.knowledge.follow-up-enabled:false}") boolean enabled) {
        this.mapper = mapper; this.contexts = contexts; this.members = members; this.resources = resources;
        this.answers = answers; this.resolver = resolver; this.json = json; this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** 必须位于受理任务的同一事务中；失败回滚任务和新会话，不在重复POST时重建快照。 */
    public void capture(AuthenticatedUserPrincipal user, long enterprise, long space, String requestId, TaskDeadline deadline) {
        if (!enabled) return;
        active(deadline);
        var context = contexts.read(user, enterprise, space, requestId, deadline);
        var member = members.requireMember(user, enterprise);
        resources.requireSpace(member, space);
        try {
            if (mapper.saveContext(member, space, requestId, json.writeValueAsString(context), FollowUpContract.snapshotId(context)) != 1)
                throw changed();
        } catch (JsonProcessingException invalid) {
            throw changed();
        }
        active(deadline);
    }

    public FollowUpResolver.Outcome resolve(AuthenticatedUserPrincipal user, long enterprise, long space,
            String requestId, String question, TaskDeadline deadline) {
        if (!enabled) return fallback(DISABLED);
        active(deadline);
        var member = members.requireMember(user, enterprise);
        resources.requireSpace(member, space);
        var job = mapper.job(member, space, requestId);
        if (job == null || !Objects.equals(job.question(), question)) throw changed();
        var stored = mapper.context(member, space, requestId);
        if (stored == null) return fallback(NO_SNAPSHOT);
        KnowledgeConversationContextService.Context context;
        try {
            if (stored.contextJson().length() > 32000) throw changed();
            com.fasterxml.jackson.databind.JsonNode tree = json.readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(stored.contextJson());
            context = json.treeToValue(tree, KnowledgeConversationContextService.Context.class);
        } catch (JsonProcessingException invalid) {
            throw changed();
        }
        if (context == null || !job.id().equals(context.requestId()) || !job.chatId().equals(context.chatId())
                || !question.equals(context.question()) || !stored.snapshotId().equals(FollowUpContract.snapshotId(context))) throw changed();
        active(deadline);
        // 真实企业空间继续禁止历史原文外发，不因开启功能而获得模型许可。
        FollowUpResolver.Outcome result;
        if (context.previous().isEmpty()) result = fallback(STANDALONE);
        else if (!"SYNTHETIC_MODEL".equals(answers.mode(user, enterprise, space))) result = fallback(NOT_PERMITTED);
        else {
            members.requireCurrent(member); resources.requireSpace(member, space); active(deadline);
            result = resolver.resolve(context, deadline);
        }
        active(deadline);
        members.requireCurrent(member); resources.requireSpace(member, space);
        if (!stored.equals(mapper.context(member, space, requestId))) throw changed();
        active(deadline);
        return result;
    }

    private static BusinessException changed() {
        return new BusinessException(ResultCode.CONFLICT, "任务上下文缺失、已变化或不可访问，请核对原任务");
    }
}
