package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
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
import java.util.HashMap;
import java.util.HashSet;
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
                log.warn("{\"event\":\"knowledge_compose_failed\",\"reason\":\"{}\"}",
                        error.getMessage() == null ? "unknown" : error.getMessage());
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

    /** 跨空间回答要合并多空间检索并调用两次模型（生成+复核），预算放宽到60秒。 */
    private static final Duration CROSS_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 跨空间回答：一次问题检索当前账号全部授权空间，全局排序合并证据后统一作答。
     * 不做业务范围分类与话题关联（均为单空间特性），每问独立；最终入选证据的来源空间均须获外发许可，
     * 未入选候选不影响许可判断。单个空间检索失败不拖垮整体，全部失败才向上报错。
     */
    public CrossAnswer answerAcross(AuthenticatedUserPrincipal user, Long enterpriseId, String question) {
        return answerAcrossWithin(user, enterpriseId, question, TaskDeadline.start(CROSS_TIMEOUT));
    }

    public CrossAnswer answerAcrossWithin(AuthenticatedUserPrincipal user, Long enterpriseId, String question,
            TaskDeadline deadline) {
        if (question == null || question.isBlank() || question.length() > 1200
                || question.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new BusinessException(ResultCode.PARAM_ERROR, "请填写1至1200个字符的问题");
        deadline.timeoutFor(timeout);
        String requestId = MDC.get("request_id");
        Future<CrossAnswer> future;
        try {
            future = executor.submit(() -> {
                if (requestId != null) MDC.put("request_id", requestId);
                try { return executeAcross(user, enterpriseId, question.strip(), deadline); }
                finally { MDC.remove("request_id"); }
            });
        } catch (RejectedExecutionException error) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "回答通道已满，请稍后手动重试");
        }
        try {
            return future.get(Math.max(1, deadline.remaining().toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "本次回答超过60秒，已停止等待；不会自动重复调用模型");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SERVICE_BUSY, "回答连接已结束，请稍后核对再重试");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof BusinessException business) throw business;
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "企业知识服务暂不可用，请联系管理员核对请求编号");
        }
    }

    private CrossAnswer executeAcross(AuthenticatedUserPrincipal user, Long enterpriseId, String question,
            TaskDeadline deadline) {
        EnterpriseMemberContext member = members.requireMember(user, enterpriseId);
        List<EnterpriseResources.KnowledgeSpace> spaces = resources.listSpaces(member, 1L, 500L).getRecords();
        if (spaces.isEmpty()) throw new BusinessException(ResultCode.PARAM_ERROR, "当前账号没有可问答的授权知识空间");
        // 每个空间独立检索并记录名次；轮转合并保证多空间机会均等，总量以9段为上限：
        // 6个授权空间各取第1名后仍有余量，让强相关空间（如同一问题命中多篇指南）的第2名也能进入证据，
        // 否则真答案排在空间内第2名时永远进不了模型上下文。
        var perSpace = new ArrayList<List<Evidence>>();
        BusinessException firstFailure = null;
        int searched = 0;
        for (EnterpriseResources.KnowledgeSpace space : spaces) {
            try {
                perSpace.add(retriever.retrieve(member, space.id(), question, deadline));
                searched++;
            } catch (BusinessException error) {
                perSpace.add(List.of());
                if (firstFailure == null) firstFailure = error;
            }
        }
        if (searched == 0 && firstFailure != null) throw firstFailure;
        // 跨空间全局重排取代名次轮转：各空间内部名次无法跨空间比较，且"校园"这类语料高频词
        // 会让真正讲办理步骤的文档输给在负面语境里顺带提到关键词的文档。对全部候选用 tf-idf
        // 重算相关度（df 取自本次候选集：高频词自动降权、词频与标题命中加权），总量9段、
        // 每空间最多2条，兼顾"问题集中在一个空间"与跨空间覆盖。
        var ordered = mergeAcross(perSpace, question, 9, 2);
        var spaceByChunk = new HashMap<Long, Long>();
        for (int index = 0; index < perSpace.size(); index++)
            for (Evidence item : perSpace.get(index)) spaceByChunk.putIfAbsent(item.chunkId(), spaces.get(index).id());
        var nameBySpace = new HashMap<Long, String>();
        for (EnterpriseResources.KnowledgeSpace space : spaces) nameBySpace.put(space.id(), space.name());
        List<SpaceEvidence> evidence = ordered.stream().map(item -> {
            Long spaceId = spaceByChunk.get(item.chunkId());
            return new SpaceEvidence(spaceId, nameBySpace.get(spaceId), item);
        }).toList();
        // 外发许可按"实际命中证据的空间"判定：空空间（无发布版本、无内容）不会产生证据，
        // 不应拖垮整个回答；只要有任一未许可空间的证据参与，整体仍降级为仅原文摘录。
        Set<Long> hitSpaceIds = evidence.stream().map(SpaceEvidence::spaceId).collect(Collectors.toSet());
        String mode = !hitSpaceIds.isEmpty() && hitSpaceIds.stream()
                .allMatch(id -> syntheticSpaces.contains(member.enterpriseId() + ":" + id))
                ? "SYNTHETIC_MODEL" : "LOCAL_EVIDENCE";
        String status;
        String message;
        List<Part> parts;
        if (evidence.isEmpty()) {
            status = "NO_EVIDENCE";
            message = "已检索" + searched + "个授权空间，均未命中有效依据；这不代表公司没有相关规定，请联系知识负责人补充资料。";
            parts = List.of(new Part(1, question, status, List.of(), List.of("全部授权空间的检索都没有命中，可换用资料中的原文关键词。")));
        } else if (mode.equals("LOCAL_EVIDENCE")) {
            status = "EVIDENCE_ONLY";
            boolean partiallyAllowed = hitSpaceIds.stream()
                    .anyMatch(id -> syntheticSpaces.contains(member.enterpriseId() + ":" + id));
            message = partiallyAllowed
                    ? "已找到相关原文，仅供核对；命中结果里有未获模型外发许可的空间，本次未生成最终答案。"
                    : "已找到相关原文，仅供核对；当前环境未启用模型改写，本次仅提供原文摘录。";
            parts = List.of(new Part(1, question, status, List.of(), List.of("请阅读下方原文；检索命中不等于答案充分。")));
        } else {
            revalidateAcross(member, evidence, deadline);
            try {
                parts = composer.compose(question, ordered, deadline);
                revalidateAcross(member, evidence, deadline);
                parts = composer.review(question, parts, deadline);
                revalidateAcross(member, evidence, deadline);
                status = scopeStatus(parts);
                message = "虚构资料模型验收：已加入结论依据复核，模型复核仍可能误判，请结合引用核对；未通过的结论不展示。";
            } catch (BusinessException error) {
                log.warn("{\"event\":\"knowledge_cross_compose_failed\",\"reason\":\"{}\"}",
                        error.getMessage() == null ? "unknown" : error.getMessage());
                status = "MODEL_UNAVAILABLE";
                message = "模型调用或回答格式校验未完成，未自动重试。可先核对下方原文，不能将其当作已生成答案。";
                parts = List.of(new Part(1, question, status, List.of(), List.of("模型未能提供通过校验的回答。")));
            }
        }
        if (!evidence.isEmpty()) revalidateAcross(member, evidence, deadline);
        long elapsed = elapsed(deadline);
        log.info("{\"event\":\"knowledge_cross_answer_completed\",\"mode\":\"{}\",\"status\":\"{}\",\"spaces\":{},"
                        + "\"evidence_count\":{},\"elapsed_ms\":{}}",
                mode, status, spaces.size(), evidence.size(), elapsed);
        return new CrossAnswer(mode, status, message, parts, evidence, elapsed);
    }

    /**
     * 跨空间候选合并：tf-idf 全局重排后取前 cap 条，每空间最多 perSpaceLimit 条。
     * 得分 = Σ 词频(content + 2×标题命中) × ln(1 + 候选总数/含该词候选数)；df 在本次候选集内统计，
     * 高频词（如"校园"）自动降权。平局回退到空间内名次与空间顺序，保证结果稳定可复现。
     */
    private static List<Evidence> mergeAcross(List<List<Evidence>> perSpace, String question, int cap, int perSpaceLimit) {
        record Candidate(Evidence evidence, int spaceIndex, int rank, double score) {}
        List<Candidate> all = new ArrayList<>();
        for (int index = 0; index < perSpace.size(); index++) {
            List<Evidence> list = perSpace.get(index);
            for (int rank = 0; rank < list.size(); rank++) all.add(new Candidate(list.get(rank), index, rank, 0d));
        }
        if (all.isEmpty()) return List.of();
        List<String> terms = KeywordKnowledgeRetriever.terms(question);
        if (!terms.isEmpty()) {
            var df = new HashMap<String, Integer>();
            for (String term : terms) {
                int count = 0;
                for (Candidate candidate : all) {
                    if (candidate.evidence().content().toLowerCase(java.util.Locale.ROOT).contains(term)
                            || candidate.evidence().title().toLowerCase(java.util.Locale.ROOT).contains(term)) count++;
                }
                df.put(term, count);
            }
            var scored = new ArrayList<Candidate>(all.size());
            for (Candidate candidate : all) {
                double score = 0d;
                for (String term : terms) {
                    int idfBoost = df.get(term);
                    if (idfBoost == 0) continue;
                    double weight = Math.log(1d + (double) all.size() / idfBoost);
                    score += weight * (occurrences(candidate.evidence().content(), term)
                            + 2d * occurrences(candidate.evidence().title(), term));
                }
                scored.add(new Candidate(candidate.evidence(), candidate.spaceIndex(), candidate.rank(), score));
            }
            scored.sort((a, b) -> {
                int byScore = Double.compare(b.score(), a.score());
                if (byScore != 0) return byScore;
                int byRank = Integer.compare(a.rank(), b.rank());
                return byRank != 0 ? byRank : Integer.compare(a.spaceIndex(), b.spaceIndex());
            });
            all = scored;
        }
        var picked = new ArrayList<Evidence>();
        var perSpaceCount = new HashMap<Integer, Integer>();
        var seenChunks = new HashSet<Long>();
        for (Candidate candidate : all) {
            if (picked.size() == cap) break;
            if (perSpaceCount.merge(candidate.spaceIndex(), 1, Integer::sum) > perSpaceLimit) continue;
            if (!seenChunks.add(candidate.evidence().chunkId())) continue;
            picked.add(candidate.evidence());
        }
        return picked;
    }

    private static int occurrences(String text, String term) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        for (int index = lower.indexOf(term); index >= 0; index = lower.indexOf(term, index + term.length())) count++;
        return count;
    }

    /** 跨空间复核：每条证据按其来源空间重查比对；requireEvidence内部会再校验该空间的授权。 */
    private void revalidateAcross(EnterpriseMemberContext member, List<SpaceEvidence> evidence, TaskDeadline deadline) {
        deadline.timeoutFor(Duration.ofSeconds(2));
        members.requireCurrent(member);
        for (SpaceEvidence item : evidence) {
            deadline.timeoutFor(Duration.ofSeconds(2));
            if (!item.evidence().equals(retriever.requireEvidence(member, item.spaceId(), item.evidence().chunkId())))
                throw new BusinessException(ResultCode.CONFLICT, "资料已更新，请重新查询，旧引用不会继续返回");
        }
        deadline.timeoutFor(Duration.ofSeconds(2));
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
