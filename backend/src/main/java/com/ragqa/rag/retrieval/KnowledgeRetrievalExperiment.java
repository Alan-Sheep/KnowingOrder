package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources.KnowledgeSpace;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 独立合成实验：原始检索、受控改写、授权补检及融合预览，不生成答案。
 * 不自动装配为生产入口；调用方须提供可信成员、合成问题及已有父预算。
 * 每次调用最多一次改写、两次检索，不重试；同步检查不能替代底层阻塞超时或并发准入。
 */
public final class KnowledgeRetrievalExperiment {
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final KnowledgeRetriever retriever;
    private final QueryRewriter rewriter;

    public KnowledgeRetrievalExperiment(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeRetriever retriever, QueryRewriter rewriter) {
        this.members = Objects.requireNonNull(members);
        this.resources = Objects.requireNonNull(resources);
        this.retriever = Objects.requireNonNull(retriever);
        this.rewriter = Objects.requireNonNull(rewriter);
    }

    /** 状态仅表示改写是否产生候选；预览不能作为充分证据、语义安全或正式答案的判定。 */
    public record Report(QueryRewriter.Outcome rewrite, KnowledgeEvidenceFusion.Preview preview) { }

    /**
     * 原问题始终保留；只有CANDIDATE启动补检。所有外部失败由原组件处理，本层不吞授权/存储异常。
     * 返回前复核两路全部唯一片段，包括重叠或容量排除项，防止截断掩盖版本变化。
     */
    public Report run(EnterpriseMemberContext member, Long spaceId, String question, TaskDeadline parent) {
        validate(spaceId, question);
        if (member == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        Objects.requireNonNull(parent, "任务时限不能为空");
        KnowledgeSpace initial = authorize(member, spaceId, parent);
        List<Evidence> original = retriever.retrieve(member, spaceId, question, parent);
        checkActive(parent);
        // 先校验并固定基线，坏快照不继续消耗可选的模型调用。
        original = KnowledgeEvidenceFusion.fuse(original, List.of()).baseline();
        reauthorize(member, spaceId, initial.permissionVersion(), parent);
        QueryRewriter.Outcome rewrite = rewriter.rewrite(member, spaceId, question, parent);
        reauthorize(member, spaceId, initial.permissionVersion(), parent);
        List<Evidence> supplemental = List.of();
        if (rewrite.status() == QueryRewriter.Status.CANDIDATE) {
            supplemental = retriever.retrieve(member, spaceId, rewrite.alternativeQuery(), parent);
            checkActive(parent);
            supplemental = KnowledgeEvidenceFusion.fuse(supplemental, List.of()).baseline();
        }
        checkActive(parent);
        KnowledgeEvidenceFusion.Preview preview = KnowledgeEvidenceFusion.fuse(original, supplemental);
        reauthorize(member, spaceId, initial.permissionVersion(), parent);
        // fuse已拒绝同主键不同快照；distinct只减少完全相同证据的重复读取，最多复核12段。
        List<Evidence> all = Stream.concat(original.stream(), supplemental.stream()).distinct().toList();
        for (Evidence previous : all) {
            checkActive(parent);
            Evidence current = retriever.requireEvidence(member, spaceId, previous.chunkId());
            checkActive(parent);
            if (!previous.equals(current)) throw changed();
        }
        reauthorize(member, spaceId, initial.permissionVersion(), parent);
        return new Report(rewrite, preview);
    }

    private KnowledgeSpace authorize(EnterpriseMemberContext member, Long spaceId, TaskDeadline parent) {
        checkActive(parent);
        members.requireCurrent(member);
        checkActive(parent);
        KnowledgeSpace space = resources.requireSpace(member, spaceId);
        checkActive(parent);
        if (space == null || !spaceId.equals(space.id()) || space.permissionVersion() <= 0) throw changed();
        return space;
    }

    private void reauthorize(EnterpriseMemberContext member, Long spaceId, long version, TaskDeadline parent) {
        if (authorize(member, spaceId, parent).permissionVersion() != version) throw changed();
    }

    private static BusinessException changed() {
        return new BusinessException(ResultCode.CONFLICT, "授权或资料快照已变化，请重新运行实验；本次预览不再交付");
    }

    private static void checkActive(TaskDeadline parent) {
        if (Thread.currentThread().isInterrupted())
            throw new BusinessException(ResultCode.BIZ_ERROR, "本次实验已取消");
        if (parent.remaining().isZero())
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "本次实验已超时，已停止后续处理");
    }

    private static void validate(Long spaceId, String question) {
        if (spaceId == null || spaceId <= 0 || question == null || question.length() > 1200
                || question.codePoints().noneMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                || question.codePoints().anyMatch(cp -> {
                    if (cp == '\n' || cp == '\r' || cp == '\t') return false;
                    int type = Character.getType(cp);
                    return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                            || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
                })) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请填写有效空间编号和1至1200字符的问题，避免非法控制字符");
        }
    }
}
