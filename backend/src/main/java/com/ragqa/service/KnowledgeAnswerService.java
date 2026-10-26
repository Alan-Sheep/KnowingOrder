package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.KnowledgeAnswerComposer;
import com.ragqa.rag.chat.FollowUpResolver;
import com.ragqa.rag.chat.KnowledgeScopeContract;
import com.ragqa.rag.chat.KnowledgeScopeResolver;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.HybridKnowledgeRetriever;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.rag.retrieval.KnowledgeRetriever;
import com.ragqa.rag.retrieval.ReviewedQueryExpansion;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** 单节点最小回答闭环：最多两个执行者、不排队、不自动重试；不宣称持久任务或断线恢复。 */
@Slf4j
@Service
public class KnowledgeAnswerService {
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final KnowledgeRetriever retriever;
    private final KnowledgeAnswerComposer composer;
    private final KnowledgeScopeResolver scopeResolver;
    private ReviewedQueryExpansion expansion;
    private final Set<String> syntheticSpaces;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    /** 扩展是独立的可关闭增强；生产容器必须明确装配，旧直接构造调用保持原问题基线。 */
    @Autowired
    public void configureExpansion(ReviewedQueryExpansion expansion) { this.expansion = expansion; }

    public KnowledgeAnswerService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeRetriever retriever, KnowledgeAnswerComposer composer,
            @Value("${knowingorder.knowledge.synthetic-model-spaces:}") String syntheticSpaces) {
        this(members, resources, retriever, composer, syntheticSpaces, Duration.ofSeconds(30));
    }

    @Autowired
    public KnowledgeAnswerService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeRetriever retriever, KnowledgeAnswerComposer composer,
            @Value("${knowingorder.knowledge.synthetic-model-spaces:}") String syntheticSpaces,
            KnowledgeScopeResolver scopeResolver) {
        this(members, resources, retriever, composer, syntheticSpaces, Duration.ofSeconds(30), scopeResolver);
    }

    KnowledgeAnswerService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeRetriever retriever, KnowledgeAnswerComposer composer, String syntheticSpaces, Duration timeout) {
        this(members, resources, retriever, composer, syntheticSpaces, timeout, null);
    }

    private KnowledgeAnswerService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeRetriever retriever, KnowledgeAnswerComposer composer, String syntheticSpaces, Duration timeout,
            KnowledgeScopeResolver scopeResolver) {
        this.members = members;
        this.resources = resources;
        this.retriever = retriever;
        this.composer = composer;
        this.scopeResolver = scopeResolver;
        this.timeout = timeout;
        this.syntheticSpaces = Arrays.stream(syntheticSpaces.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .peek(value -> {
                    if (!value.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}"))
                        throw new IllegalArgumentException("虚构测试许可须使用企业编号:空间编号");
                    try { for (String id : value.split(":")) Long.parseLong(id); }
                    catch (NumberFormatException error) { throw new IllegalArgumentException("虚构测试许可编号超出范围"); }
                }).collect(Collectors.toUnmodifiableSet());
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "knowledge-answer");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    public Answer answer(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId, String question) {
        return answerWithin(user, enterpriseId, spaceId, question, TaskDeadline.start(timeout));
    }

    /** 持久任务传入首次受理的剩余预算，不因后台执行或重连重新获得30秒。 */
    public Answer answerWithin(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId, String question,
            TaskDeadline deadline) {
        return answerWithin(user, enterpriseId, spaceId, question, deadline, null);
    }

    /** 仅供受控持久任务编排调用，HTTP请求不能声明或注入话题关联。 */
    public Answer answerWithin(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId, String question,
            TaskDeadline deadline, FollowUpResolver.Outcome context) {
        validate(spaceId, question);
        deadline.timeoutFor(timeout);
        String requestId = MDC.get("request_id");
        Future<Answer> future;
        try {
            future = executor.submit(() -> {
                if (requestId != null) MDC.put("request_id", requestId);
                try { return execute(user, enterpriseId, spaceId, question.strip(), deadline, context); }
                finally { MDC.remove("request_id"); }
            });
        } catch (RejectedExecutionException error) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "回答通道已满，请稍后手动重试");
        }
        try {
            return future.get(Math.max(1, deadline.remaining().toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "本次回答超过30秒，已停止等待；不会自动重复调用模型");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SERVICE_BUSY, "回答连接已结束，请稍后核对再重试");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "企业知识服务暂不可用，请联系管理员核对请求编号");
        }
    }

    private Answer execute(AuthenticatedUserPrincipal user, Long enterpriseId, long spaceId,
            String question, TaskDeadline deadline, FollowUpResolver.Outcome context) {
        EnterpriseMemberContext member = members.requireMember(user, enterpriseId);
        resources.requireSpace(member, spaceId);
        String mode = syntheticSpaces.contains(member.enterpriseId() + ":" + spaceId) ? "SYNTHETIC_MODEL" : "LOCAL_EVIDENCE";
        if (scopeResolver != null && scopeResolver.applies(member.enterpriseId()))
            return scopedAnswer(member, spaceId, question, deadline, context, mode);
        return evidenceAnswer(member, spaceId, question, deadline, context, mode, null);
    }

    private Answer scopedAnswer(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline,
            FollowUpResolver.Outcome context, String mode) {
        List<KnowledgeScopeContract.Part> classified;
        String unavailable = "业务范围识别暂不可用，本次未判断是否属于学校业务，也未生成制度结论。";
        if (!mode.equals("SYNTHETIC_MODEL")) {
            revalidate(member, spaceId, List.of(), deadline);
            return scopeOnly(mode, question, "SCOPE_UNAVAILABLE", unavailable
                    + " 当前空间未获模型许可，问题未外发。", deadline);
        }
        String antecedent = antecedent(context, mode);
        try {
            classified = scopeResolver.resolve(member, spaceId, question, antecedent, deadline).parts();
        } catch (BusinessException error) {
            revalidate(member, spaceId, List.of(), deadline);
            return scopeOnly(mode, question, "SCOPE_UNAVAILABLE", unavailable + " 未自动重试，可稍后手动重试。", deadline);
        }
        // 分类结果不是授权凭据；识别期间撤权后，不继续检索或生成。
        revalidate(member, spaceId, List.of(), deadline);
        List<String> inside = classified.stream()
                .filter(part -> part.membership() == KnowledgeScopeContract.Membership.IN_SCOPE)
                .map(KnowledgeScopeContract.Part::question).toList();
        Answer answered = inside.isEmpty() ? null
                : evidenceAnswer(member, spaceId, String.join("", inside), deadline, context, mode, inside);
        var parts = new ArrayList<Part>();
        int index = 0;
        for (KnowledgeScopeContract.Part part : classified) {
            if (part.membership() == KnowledgeScopeContract.Membership.IN_SCOPE) {
                Part generated = answered.parts().size() == inside.size() ? answered.parts().get(index)
                        : answered.parts().get(0);
                parts.add(new Part(parts.size() + 1, part.question(), generated.status(), generated.paragraphs(), generated.gaps()));
                index++;
            } else {
                boolean outside = part.membership() == KnowledgeScopeContract.Membership.OUT_OF_SCOPE;
                parts.add(new Part(parts.size() + 1, part.question(), outside ? "OUT_OF_SCOPE" : "SCOPE_UNKNOWN", List.of(),
                        List.of(outside ? "此问题不属于当前学校业务范围。可自行切换到公开联网问答；不会自动发送问题或内部资料。"
                                : "暂不能确定业务归属，本次不作制度判断。可补充具体校内业务对象。")));
            }
        }
        List<Evidence> evidence = answered == null ? List.of() : answered.evidence();
        revalidate(member, spaceId, evidence, deadline);
        String status = scopeStatus(parts);
        log.info("knowledge_scope_completed: status={}, part_count={}, evidence_count={}, elapsed_ms={}",
                status, parts.size(), evidence.size(), elapsed(deadline));
        return new Answer(mode, status, "已按学校业务范围逐项处理；范围归属不等于证据充分。"
                + (answered == null ? "" : " " + answered.message()), List.copyOf(parts), evidence, elapsed(deadline));
    }

    private static Answer scopeOnly(String mode, String question, String status, String message, TaskDeadline deadline) {
        log.info("knowledge_scope_completed: status={}, part_count=1, evidence_count=0, elapsed_ms={}", status, elapsed(deadline));
        return new Answer(mode, status, message,
                List.of(new Part(1, question, status, List.of(), List.of(message))), List.of(), elapsed(deadline));
    }

    /** 混合分流但没有任何已生成答案时，不能用“部分可回答”掩盖未知、故障或无依据。 */
    private static String scopeStatus(List<Part> parts) {
        if (parts.stream().allMatch(part -> part.status().equals(parts.get(0).status()))) return parts.get(0).status();
        if (parts.stream().anyMatch(part -> part.status().equals("CONFLICT"))) return "CONFLICT";
        if (parts.stream().anyMatch(part -> Set.of("ANSWERED", "PARTIAL").contains(part.status()))) return "PARTIAL";
        for (String status : List.of("SCOPE_UNAVAILABLE", "MODEL_UNAVAILABLE", "SCOPE_UNKNOWN", "EVIDENCE_ONLY", "NO_EVIDENCE"))
            if (parts.stream().anyMatch(part -> part.status().equals(status))) return status;
        return "OUT_OF_SCOPE";
    }

    private static String antecedent(FollowUpResolver.Outcome context, String mode) {
        return context != null && context.status() == FollowUpResolver.Status.FOLLOW_UP
                && context.antecedent() != null && mode.equals("SYNTHETIC_MODEL") ? context.antecedent().question() : null;
    }

    private Answer evidenceAnswer(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline,
            FollowUpResolver.Outcome context, String mode, List<String> fixedQuestions) {
        List<Evidence> evidence = retriever.retrieve(member, spaceId, question, deadline);
        // 即使内部调用方误传关联，真实企业空间也不继承未经许可的历史线索。
        String antecedent = antecedent(context, mode);
        if (antecedent != null) {
            var selected = new LinkedHashMap<Long, Evidence>();
            for (Evidence item : evidence) selected.put(item.chunkId(), item);
            // 原问题优先，不改写；话题原问题只提供第二路授权检索候选，合计最多6段。
            for (Evidence item : retriever.retrieve(member, spaceId, antecedent, deadline)) {
                Evidence old = selected.get(item.chunkId());
                if (old != null && !old.equals(item)) throw new BusinessException(ResultCode.CONFLICT, "检索期间资料发生变化");
                if (selected.size() < 6) selected.putIfAbsent(item.chunkId(), item);
            }
            evidence = List.copyOf(selected.values());
        }
        if (mode.equals("SYNTHETIC_MODEL") && expansion != null
                && (context == null || (context.status() != FollowUpResolver.Status.UNRESOLVED
                    && context.status() != FollowUpResolver.Status.UNAVAILABLE)))
            evidence = expansion.expand(member, spaceId, question, evidence, deadline);
        String status;
        String message;
        List<Part> parts;
        if (evidence.isEmpty()) {
            status = "NO_EVIDENCE";
            message = "未检索到可用的已发布资料；这不代表公司没有相关规定，请联系知识负责人补充或换用原文关键词。";
            parts = List.of(new Part(1, question, status, List.of(), List.of("当前授权空间的检索没有命中有效依据。")));
        } else if (context != null && (context.status() == FollowUpResolver.Status.UNRESOLVED
                || context.status() == FollowUpResolver.Status.UNAVAILABLE)) {
            status = "EVIDENCE_ONLY";
            message = "上下文未能可靠解析，保留当前问题命中的原文；本次未生成确定答案。";
            parts = List.of(new Part(1, question, status, List.of(), List.of("可补充问题中的业务对象，无需取消其他在途问题。")));
        } else if (mode.equals("LOCAL_EVIDENCE")) {
            status = "EVIDENCE_ONLY";
            message = "已找到相关原文，仅供核对；此空间未获模型外发许可，尚未生成或判定最终答案。";
            parts = List.of(new Part(1, question, status, List.of(), List.of("请阅读下方原文；检索命中不等于答案充分。")));
        } else {
            revalidate(member, spaceId, evidence, deadline);
            try {
                parts = fixedQuestions != null ? composer.composeFixed(fixedQuestions, evidence, deadline, antecedent)
                        : antecedent == null ? composer.compose(question, evidence, deadline)
                        : composer.compose(question, evidence, deadline, antecedent);
                revalidate(member, spaceId, evidence, deadline);
                parts = composer.review(question, parts, deadline);
                status = scopeStatus(parts);
                message = "虚构资料模型验收：已加入结论依据复核，模型复核仍可能误判，请结合引用核对；未通过的结论不展示。";
            } catch (BusinessException error) {
                status = "MODEL_UNAVAILABLE";
                message = "模型调用或回答格式校验未完成，未自动重试。可先核对下方原文，不能将其当作已生成答案。";
                parts = List.of(new Part(1, question, status, List.of(), List.of("模型未能提供通过校验的回答。")));
            }
        }
        revalidate(member, spaceId, evidence, deadline);
        if (context != null) message = context.message() + " " + message;
        long elapsed = elapsed(deadline);
        log.info("{\"event\":\"knowledge_answer_completed\",\"mode\":\"{}\",\"status\":\"{}\",\"evidence_count\":{},\"elapsed_ms\":{}}",
                mode, status, evidence.size(), elapsed);
        return new Answer(mode, status, message, parts, evidence, elapsed);
    }

    private static long elapsed(TaskDeadline deadline) {
        return Math.max(0, Duration.between(deadline.receivedAt(), deadline.deadlineAt()).minus(deadline.remaining()).toMillis());
    }

    private void revalidate(EnterpriseMemberContext member, long spaceId, List<Evidence> evidence, TaskDeadline deadline) {
        deadline.timeoutFor(Duration.ofSeconds(2));
        members.requireCurrent(member);
        resources.requireSpace(member, spaceId);
        for (Evidence previous : evidence) {
            deadline.timeoutFor(Duration.ofSeconds(2));
            if (!previous.equals(retriever.requireEvidence(member, spaceId, previous.chunkId())))
                throw new BusinessException(ResultCode.CONFLICT, "资料已更新，请重新查询，旧引用不会继续返回");
        }
        deadline.timeoutFor(Duration.ofSeconds(2));
    }

    public Evidence evidence(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId, Long chunkId) {
        if (spaceId == null || spaceId <= 0 || chunkId == null || chunkId <= 0)
            throw new BusinessException(ResultCode.PARAM_ERROR, "资源编号必须为正整数");
        return retriever.requireEvidence(members.requireMember(user, enterpriseId), spaceId, chunkId);
    }

    /** 在提交问题之前显示实际数据边界；客户端只能读取，不能开启外发。 */
    public String mode(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId) {
        EnterpriseMemberContext member = members.requireMember(user, enterpriseId);
        resources.requireSpace(member, spaceId);
        return syntheticSpaces.contains(member.enterpriseId() + ":" + spaceId) ? "SYNTHETIC_MODEL" : "LOCAL_EVIDENCE";
    }

    /**
     * 只读已装配的检索策略，与模型外发许可分开；不探测依赖健康或空间索引就绪。
     * 读取实际目标类型而非重读开关，兼容Spring代理且避免配置变化后误报执行策略。
     * 当前仅支持两种明确实现；新增适配器须显式定义标识，不默认冒充关键词检索。
     */
    public String retrievalMode(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId) {
        if (spaceId == null || spaceId <= 0)
            throw new BusinessException(ResultCode.PARAM_ERROR, "空间编号必须为正整数");
        EnterpriseMemberContext member = members.requireMember(user, enterpriseId);
        resources.requireSpace(member, spaceId);
        Class<?> implementation = AopUtils.getTargetClass(retriever);
        if (HybridKnowledgeRetriever.class.isAssignableFrom(implementation)) return "HYBRID";
        if (KeywordKnowledgeRetriever.class.isAssignableFrom(implementation)) return "KEYWORD";
        throw new BusinessException(ResultCode.SERVICE_BUSY, "当前检索方式无法确认，请联系管理员核对请求编号");
    }

    private static void validate(Long spaceId, String question) {
        if (spaceId == null || spaceId <= 0 || question == null || question.isBlank() || question.length() > 1200
                || question.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new BusinessException(ResultCode.PARAM_ERROR, "请填写有效空间编号和1至1200字符的问题");
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
