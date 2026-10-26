package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.ChatModel;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 合成空间专用的独立实验入口；尚未接入问答服务，候选通过词法检查也不等于语义等价。 */
@Component
public final class QueryRewriter {
    private static final Duration STAGE_LIMIT = Duration.ofSeconds(5);
    // 初始输出保护上限，不能保证模型在此额度内生成有效内容；真实用量与价格仍待实验。
    private static final int MAX_OUTPUT_TOKENS = 1000;
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final ChatModel model;
    private final Set<String> syntheticSpaces;

    public QueryRewriter(EnterpriseMemberService members, EnterpriseResourceService resources, ChatModel model,
            @Value("${knowingorder.knowledge.synthetic-model-spaces:}") String syntheticSpaces) {
        this.members = Objects.requireNonNull(members);
        this.resources = Objects.requireNonNull(resources);
        this.model = Objects.requireNonNull(model);
        if (syntheticSpaces == null) throw new IllegalArgumentException("虚构测试许可配置不能为空");
        this.syntheticSpaces = Arrays.stream(syntheticSpaces.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).peek(value -> {
                    if (!value.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}"))
                        throw new IllegalArgumentException("虚构测试许可须使用企业编号:空间编号");
                    try { for (String id : value.split(":")) Long.parseLong(id); }
                    catch (NumberFormatException error) {
                        throw new IllegalArgumentException("虚构测试许可编号超出范围");
                    }
                }).collect(Collectors.toUnmodifiableSet());
    }

    public enum Status { NOT_PERMITTED, CANDIDATE, UNCHANGED, UNAVAILABLE }

    /** 除CANDIDATE外备选句为空；调用方始终保留原问题，且不得把候选当作事实或权限依据。 */
    public record Outcome(Status status, String alternativeQuery, String message) { }

    /**
     * member须来自服务端认证链，禁止从客户端绑定；每次调用最多尝试一次模型，不执行检索。
     * 调用前后核权。仅捕获模型/输出契约的业务失败；授权或数据库故障不转换为普通降级。
     */
    public Outcome rewrite(EnterpriseMemberContext member, Long spaceId, String originalQuestion,
            TaskDeadline parent) {
        validateQuestion(spaceId, originalQuestion);
        if (member == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        Objects.requireNonNull(parent, "任务时限不能为空");
        checkActive(parent);
        reauthorize(member, spaceId);
        checkActive(parent);
        if (!syntheticSpaces.contains(member.enterpriseId() + ":" + spaceId)) {
            return new Outcome(Status.NOT_PERMITTED, "", "当前空间未获查询改写外发许可，保留原问题。");
        }
        TaskDeadline stage = parent.limitedTo(STAGE_LIMIT);
        String alternative = null;
        try {
            alternative = QueryRewriteContract.alternativeQuery(originalQuestion,
                    model.call("rewrite", Map.of("originalQuestion", originalQuestion), MAX_OUTPUT_TOKENS, stage));
        } catch (BusinessException ignored) {
            // 包括供应商认证错误；不重试、不回灌正文、不把供应商错误作为本系统授权结果。
        }
        checkActive(parent);
        reauthorize(member, spaceId);
        checkActive(parent);
        if (alternative == null || stage.remaining().isZero()) {
            return new Outcome(Status.UNAVAILABLE, "", "语义扩展未完成，可继续按原问题检索；未自动重试。");
        }
        if (alternative.equals(originalQuestion.strip())) {
            return new Outcome(Status.UNCHANGED, "", "改写未产生新的检索表达，保留原问题。");
        }
        return new Outcome(Status.CANDIDATE, alternative, "已得到实验检索候选；词法校验通过不代表语义等价。");
    }

    private void reauthorize(EnterpriseMemberContext member, long spaceId) {
        members.requireCurrent(member);
        resources.requireSpace(member, spaceId);
    }

    private static void checkActive(TaskDeadline parent) {
        if (Thread.currentThread().isInterrupted())
            throw new BusinessException(ResultCode.BIZ_ERROR, "本次处理已取消");
        if (parent.remaining().isZero())
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "本次处理已超时，已停止查询改写");
    }

    private static void validateQuestion(Long spaceId, String question) {
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
