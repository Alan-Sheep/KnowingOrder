package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.config.WebChatProperties;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.domain.dto.ChatAnswer.Basis;
import com.ragqa.domain.dto.ChatAnswer.Part;
import com.ragqa.domain.dto.ChatAnswer.PartStatus;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.domain.dto.SearchResult;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.AnswerComposer;
import com.ragqa.rag.chat.AnswerComposer.Grade;
import com.ragqa.rag.chat.ChatBudget;
import com.ragqa.rag.chat.QuestionPlanner;
import com.ragqa.rag.chat.QuestionPlanner.Item;
import com.ragqa.rag.retrieval.EvidenceSelector;
import com.ragqa.rag.retrieval.EvidenceSelector.Entry;
import com.ragqa.rag.retrieval.TavilyClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地联网问答闭环：依赖调度、有界并行、最多一轮补查和共享时限。
 * 本类不持久化消息或后台任务，不能用于承诺断线恢复与跨请求幂等。
 */
@Slf4j
@Service
public class WebChatService implements AutoCloseable {

    private final QuestionPlanner planner;
    private final TavilyClient search;
    private final EvidenceSelector selector;
    private final AnswerComposer composer;
    private final WebChatProperties properties;
    private final Semaphore requests;
    private final ThreadPoolExecutor workers;

    public WebChatService(QuestionPlanner planner, TavilyClient search, EvidenceSelector selector,
            AnswerComposer composer, WebChatProperties properties) {
        this.planner = planner;
        this.search = search;
        this.selector = selector;
        this.composer = composer;
        this.properties = properties;
        this.requests = new Semaphore(properties.getMaxConcurrentRequests());
        AtomicInteger threadIds = new AtomicInteger();
        int size = properties.getMaxConcurrentRequests() * 2;
        this.workers = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "web-chat-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    public ChatAnswer answer(String question) {
        return answer(new ChatRequest(question, null, null));
    }

    /** 完整请求入口；时间字段独立保留，不拼进原问题或伪装成检索证据。 */
    public ChatAnswer answer(ChatRequest request) {
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank() || question.length() > 1000
                || question.codePointCount(0, question.length()) > 500) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "问题不能为空，且最多为 500 个 Unicode 字符");
        }
        ChatBudget budget = new ChatBudget(properties, request);
        if (!requests.tryAcquire()) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "当前问答请求较多，请稍后重试");
        }
        String oldId = MDC.get("request_id");
        String requestId = oldId == null ? UUID.randomUUID().toString() : oldId;
        MDC.put("request_id", requestId);
        long started = System.nanoTime();
        try {
            budget.beforeModel();
            QuestionPlanner.Plan plan = planner.plan(question.strip(), budget.deadline(), budget.timeContext());
            Map<Integer, Outcome> completed = execute(plan, budget, requestId);
            List<Part> parts = plan.displayOrder().stream().map(id -> completed.get(id).part()).toList();
            long answered = parts.stream().filter(part -> part.status() == PartStatus.ANSWERED).count();
            boolean useful = parts.stream().anyMatch(part -> part.status() == PartStatus.ANSWERED
                    || part.status() == PartStatus.PARTIAL);
            ChatAnswer.Status status = answered == parts.size() ? ChatAnswer.Status.COMPLETED
                    : useful ? ChatAnswer.Status.PARTIAL : ChatAnswer.Status.NO_ANSWER;
            String message = switch (status) {
                case COMPLETED -> "本次问题已处理；一般建议与联网资料已分别标明";
                case PARTIAL -> "已保留可回答部分，请查看各问题的未完成说明";
                case NO_ANSWER -> "暂未形成可靠答案，请查看各问题的具体说明";
            };
            long millis = (System.nanoTime() - started) / 1_000_000;
            log.info("{\"event\":\"chat_completed\",\"request_id\":\"{}\",\"status\":\"{}\",\"elapsed_ms\":{}}",
                    requestId, status, millis);
            return new ChatAnswer(requestId, status, message, parts, millis);
        } finally {
            requests.release();
            if (oldId == null) {
                MDC.remove("request_id");
            } else {
                MDC.put("request_id", oldId);
            }
        }
    }

    private Map<Integer, Outcome> execute(QuestionPlanner.Plan plan, ChatBudget budget, String requestId) {
        Map<Integer, Item> pending = new LinkedHashMap<>();
        plan.executionOrder().forEach(item -> pending.put(item.id(), item));
        Map<Integer, Outcome> completed = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            if (budget.deadline().remaining().isZero()) {
                pending.values().forEach(item -> completed.put(item.id(), timeout(item, null)));
                break;
            }
            List<Item> ready = pending.values().stream()
                    .filter(item -> completed.keySet().containsAll(item.dependsOn())).limit(2).toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("已验证的计划缺少可执行节点");
            }
            Map<Item, Running> running = new LinkedHashMap<>();
            try {
                for (Item item : ready) {
                    List<Outcome> dependencies = item.dependsOn().stream().map(completed::get).toList();
                    if (dependencies.stream().anyMatch(value -> value.part().status() != PartStatus.ANSWERED)) {
                        completed.put(item.id(), failed(item, PartStatus.DEPENDENCY_FAILED,
                                "前置问题未完整解决，本题暂未执行；其他独立问题继续处理", List.of()));
                    } else {
                        AtomicReference<Outcome> checkpoint = new AtomicReference<>();
                        try {
                            Future<Outcome> future = workers.submit(() -> withRequestId(requestId,
                                    () -> process(item, dependencies, budget, checkpoint)));
                            running.put(item, new Running(future, checkpoint));
                        } catch (RejectedExecutionException exception) {
                            completed.put(item.id(), failed(item, PartStatus.FAILED,
                                    "处理资源暂时繁忙，本题未开始执行", List.of()));
                        }
                    }
                }
                for (Map.Entry<Item, Running> entry : running.entrySet()) {
                    Item item = entry.getKey();
                    Running active = entry.getValue();
                    try {
                        Outcome outcome = active.future().isDone() ? active.future().get()
                                : active.future().get(budget.deadline().remaining().toNanos(), TimeUnit.NANOSECONDS);
                        completed.put(item.id(), outcome);
                    } catch (TimeoutException exception) {
                        active.future().cancel(true);
                        completed.put(item.id(), timeout(item, active.checkpoint().get()));
                    } catch (ExecutionException exception) {
                        // 不打印任务原始异常，防止异常链夹带资料或凭据。
                        completed.put(item.id(), failed(item, PartStatus.FAILED,
                                "本题处理异常，暂未形成可靠答案", List.of()));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ResultCode.BIZ_ERROR, "本次处理已取消");
                    }
                }
            } finally {
                running.values().forEach(active -> {
                    if (!active.future().isDone()) {
                        active.future().cancel(true);
                    }
                });
            }
            ready.forEach(item -> pending.remove(item.id()));
        }
        return completed;
    }

    private Outcome process(Item item, List<Outcome> dependencies, ChatBudget budget,
            AtomicReference<Outcome> checkpoint) {
        List<Entry> evidence = List.of();
        Grade grade = Grade.general();
        String phase = "检索";
        String supplementNotice = "";
        try {
            budget.active();
            if (!item.clarification().isBlank()) {
                return failed(item, PartStatus.CLARIFICATION_NEEDED, item.clarification(), evidence);
            }
            List<SearchResult> raw = new ArrayList<>();
            dependencies.forEach(value -> value.evidence().forEach(entry -> raw.add(entry.asSearchResult())));
            if (item.needsSearch()) {
                budget.beforeSearch();
                raw.addAll(search.search(item.query(), budget.deadline()));
            }
            evidence = selector.select(raw);
            boolean web = item.needsSearch() || !evidence.isEmpty();
            if (web && evidence.isEmpty()) {
                return failed(item, PartStatus.NO_EVIDENCE, "本次未检索到可用资料，未据此生成事实答案", evidence);
            }
            if (web) {
                phase = "资料评估";
                grade = composer.gradeQuestion(item, evidence, budget);
                checkpoint.set(extract(item, grade, evidence, "以下仅为已有资料原文，尚未完成答案生成"));
                if (!grade.verdict().equals("SUFFICIENT") && !grade.followUpQuery().isBlank()
                        && !grade.followUpQuery().strip().equals(item.query().strip())) {
                    try {
                        budget.beforeSearch();
                        List<SearchResult> extra = search.search(grade.followUpQuery(), budget.deadline());
                        if (!extra.isEmpty()) {
                            List<SearchResult> merged = new ArrayList<>(raw);
                            merged.addAll(extra);
                            List<Entry> candidate = selector.select(merged);
                            Grade second = composer.gradeQuestion(item, candidate, budget);
                            // 补查后必须重新校验编号，不能沿用旧资料表的引用。
                            evidence = candidate;
                            grade = second;
                            checkpoint.set(extract(item, grade, evidence, "以下仅为已有资料原文，尚未完成答案生成"));
                        }
                    } catch (BusinessException exception) {
                        supplementNotice = "补充查询未完成，以下仅使用补查前已核实的资料。";
                    }
                }
                if (grade.facts().isEmpty()) {
                    return failed(item, PartStatus.INSUFFICIENT_EVIDENCE,
                            "查到相关资料，但不足以支持结论：" + String.join("；", grade.gaps()) + supplementNotice, evidence);
                }
            }
            phase = "答案生成";
            List<Map<String, Object>> context = dependencies.stream().map(value -> Map.<String, Object>of(
                    "question", value.part().question(), "answer",
                    EvidenceSelector.truncate(value.part().answer(), 1000))).toList();
            String answer = composer.compose(item, grade, web, context, budget);
            PartStatus status = grade.verdict().equals("SUFFICIENT") ? PartStatus.ANSWERED : PartStatus.PARTIAL;
            String message = !web ? "以下为一般性建议，未进行联网核实"
                    : status == PartStatus.ANSWERED ? "回答依据本次检索资料"
                    : "尚未确认：" + String.join("；", grade.gaps()) + supplementNotice;
            return new Outcome(new Part(item.id(), item.question(), status, web ? Basis.WEB : Basis.GENERAL,
                    answer, message, composer.sources(item.id(), evidence)), evidence);
        } catch (BusinessException exception) {
            boolean timedOut = budget.deadline().remaining().isZero() || exception.getMessage().contains("超时");
            Outcome partial = checkpoint.get();
            if (partial != null && !partial.part().answer().isBlank()) {
                return withNotice(partial, phase + (timedOut ? "超时" : "暂未完成") + "，以下仅为已验证资料的原文摘录");
            }
            log.info("{\"event\":\"chat_part_failed\",\"request_id\":\"{}\",\"question_id\":{},\"phase\":\"{}\",\"code\":{}}",
                    MDC.get("request_id"), item.id(), phase, exception.getCode());
            return failed(item, timedOut ? PartStatus.TIMED_OUT : PartStatus.FAILED,
                    phase + (timedOut ? "超时，已停止本题后续调用" : "暂未完成，请稍后重试；可先查看已获得的来源"), evidence);
        }
    }

    private Outcome extract(Item item, Grade grade, List<Entry> evidence, String message) {
        if (grade.facts().isEmpty()) {
            return failed(item, PartStatus.INSUFFICIENT_EVIDENCE, "现有资料不足以支持结论", evidence);
        }
        String answer = grade.facts().stream().map(fact -> "“" + fact.quote() + "” [q" + item.id()
                + "-s" + fact.sourceId() + "]").distinct().collect(java.util.stream.Collectors.joining("\n"));
        return new Outcome(new Part(item.id(), item.question(), PartStatus.PARTIAL, Basis.WEB,
                answer, message, composer.sources(item.id(), evidence)), evidence);
    }

    private Outcome failed(Item item, PartStatus status, String message, List<Entry> evidence) {
        return new Outcome(new Part(item.id(), item.question(), status,
                item.needsSearch() || !evidence.isEmpty() ? Basis.WEB : Basis.GENERAL,
                "", message, composer.sources(item.id(), evidence)), evidence);
    }

    private Outcome timeout(Item item, Outcome checkpoint) {
        return checkpoint != null && !checkpoint.part().answer().isBlank()
                ? withNotice(checkpoint, "本次处理已超时，已停止后续调用；以下仅为已验证资料原文")
                : failed(item, PartStatus.TIMED_OUT, "本次处理已超时，已停止本题后续调用", List.of());
    }

    private static Outcome withNotice(Outcome outcome, String message) {
        Part part = outcome.part();
        return new Outcome(new Part(part.id(), part.question(), PartStatus.PARTIAL, part.basis(),
                part.answer(), message, part.sources()), outcome.evidence());
    }

    private static Outcome withRequestId(String id, java.util.function.Supplier<Outcome> action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("request_id", id);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    @PreDestroy
    @Override
    public void close() {
        workers.shutdownNow();
    }

    private record Outcome(Part part, List<Entry> evidence) {
    }

    private record Running(Future<Outcome> future, AtomicReference<Outcome> checkpoint) {
    }
}
