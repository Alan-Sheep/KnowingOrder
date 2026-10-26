package com.ragqa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Answer;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.mapper.KnowledgeWorkspaceMapper;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.*;
import com.ragqa.rag.chat.TaskDeadline;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** 数据库存受理及终态；本节点两执行槽，无自动重放RUNNING，防止未知调用重复计费。 */
@Service
public class KnowledgeWorkspaceService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeWorkspaceService.class);
    private final KnowledgeWorkspaceMapper mapper;
    private final KnowledgeManagementMapper management;
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final KnowledgeAnswerService answers;
    private final KnowledgeFollowUpService followUps;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ConcurrentMap<String, FutureTask<Void>> running = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> daemon(r, "knowledge-job"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "knowledge-dispatch"));
    private volatile boolean ready;
    private boolean outageLogged;

    public record Submit(String chatId, String requestId, String question) { }
    public record Job(String id, String chatId, String question, String status, long createdMs,
            long deadlineMs, Long finishedMs, Answer answer, String message) { }
    public record FeedbackRequest(String id, String jobId, Long chunkId, String category, String comment) { }
    public record Resolution(long revision, String status, String resolution) { }

    public KnowledgeWorkspaceService(KnowledgeWorkspaceMapper mapper, KnowledgeManagementMapper management,
            EnterpriseMemberService members, EnterpriseResourceService resources, KnowledgeAnswerService answers,
            ObjectMapper json, PlatformTransactionManager manager, KnowledgeFollowUpService followUps) {
        this.mapper = mapper;
        this.management = management;
        this.members = members;
        this.resources = resources;
        this.answers = answers;
        this.followUps = followUps;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @PostConstruct public void start() { scheduler.scheduleWithFixedDelay(this::dispatch, 1, 1, TimeUnit.SECONDS); }
    public boolean available() { return ready; }

    /** 可在测试中显式触发；数据库不可用时不停止调度线程，也不输出连接地址/异常正文。 */
    public void dispatch() {
        try {
            long now = System.currentTimeMillis();
            mapper.expire(now);
            ready = true;
            outageLogged = false;
            for (StoredJob job : mapper.pending(now)) {
                if (running.size() >= 2) break;
                FutureTask<Void> task = new FutureTask<>(() -> { execute(job); return null; });
                if (running.putIfAbsent(job.id(), task) != null) continue;
                try { workers.execute(task); }
                catch (RejectedExecutionException ex) { running.remove(job.id(), task); }
            }
        } catch (DataAccessException ex) {
            ready = false;
            if (!outageLogged) LOG.warn("knowledge_workspace_unavailable: 请核对数据库连接和20261019_01迁移，不自动调用模型");
            outageLogged = true;
        } catch (RuntimeException ex) {
            if (!outageLogged) LOG.error("knowledge_dispatch_failed: type={}", ex.getClass().getSimpleName());
            outageLogged = true;
        }
    }

    public List<Chat> chats(AuthenticatedUserPrincipal user, long enterprise, long space) {
        return mapper.chats(scope(user, enterprise, space), space);
    }

    /**
     * 本人删除自己的会话。
     *
     * <p>采用软删除：只写 deleted_ms，任务、上下文快照与反馈全部保留，
     * 因此「回答可回溯」和已有反馈记录都不会因为一次删除而失效。
     * 重复删除或删除不属于本人的会话时不会改动任何行，按幂等成功返回，
     * 这样既不会因为重复点击报错，也不会泄露他人会话是否存在。</p>
     */
    public void deleteChat(AuthenticatedUserPrincipal user, long enterprise, long space, String chatId) {
        uuid(chatId);
        var scope = scope(user, enterprise, space);
        tx.execute(status -> {
            members.requireCurrent(scope);
            resources.requireSpace(scope, space);
            mapper.softDeleteChat(scope, space, chatId, System.currentTimeMillis());
            return null;
        });
    }

    public Job submit(AuthenticatedUserPrincipal user, long enterprise, long space, Submit request) {
        if (request == null) throw invalid();
        uuid(request.chatId()); uuid(request.requestId());
        String question = text(request.question(), 1200);
        var scope = scope(user, enterprise, space);
        try {
            return tx.execute(status -> {
                mapper.lockOwner(scope);
                members.requireCurrent(scope);
                resources.requireSpace(scope, space);
                StoredJob old = mapper.job(scope, space, request.requestId());
                if (old != null) {
                    if (!old.chatId().equals(request.chatId()) || !old.question().equals(question)) throw conflict();
                    return view(user, old);
                }
                mapper.expire(System.currentTimeMillis());
                if (mapper.activeForOwner(scope) >= 2 || mapper.activeTotal() >= 64)
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "在途任务已达上限，请等待已有任务结束");
                if (mapper.chat(scope, space, request.chatId()) == null) {
                    int end = Math.min(80, question.length());
                    if (end < question.length() && Character.isHighSurrogate(question.charAt(end - 1))) end--;
                    mapper.createChat(scope, space, request.chatId(), question.substring(0, end), System.currentTimeMillis());
                }
                long now = System.currentTimeMillis();
                mapper.createJob(request.requestId(), request.chatId(), scope.userAuthVersion(), question, now, now + 30_000);
                if (followUps.enabled()) {
                    long remaining = now + 30_000 - System.currentTimeMillis();
                    if (remaining <= 0) throw new BusinessException(ResultCode.PROCESS_TIMEOUT);
                    followUps.capture(user, enterprise, space, request.requestId(), TaskDeadline.start(Duration.ofMillis(remaining)));
                }
                return view(user, mapper.job(scope, space, request.requestId()));
            });
        } catch (DuplicateKeyException ex) {
            // 全局UUID冲突不能返回他人的结果；客户端保持原请求编号核对或创建新的会话。
            throw conflict();
        }
    }

    public List<Job> history(AuthenticatedUserPrincipal user, long enterprise, long space, String chatId) {
        uuid(chatId);
        var scope = scope(user, enterprise, space);
        if (mapper.chat(scope, space, chatId) == null) throw missing();
        mapper.expire(System.currentTimeMillis());
        return mapper.history(scope, space, chatId).stream().map(job -> view(user, job)).toList();
    }

    public Job job(AuthenticatedUserPrincipal user, long enterprise, long space, String id) {
        uuid(id);
        var scope = scope(user, enterprise, space);
        mapper.expire(System.currentTimeMillis());
        StoredJob job = mapper.job(scope, space, id);
        if (job == null) throw missing();
        return view(user, job);
    }

    public Job cancel(AuthenticatedUserPrincipal user, long enterprise, long space, String id) {
        var job = job(user, enterprise, space, id);
        mapper.cancel(id, System.currentTimeMillis());
        FutureTask<Void> task = running.remove(id);
        if (task != null) task.cancel(true);
        return job(user, enterprise, space, job.id());
    }

    private void execute(StoredJob job) {
        MDC.put("request_id", job.id());
        try {
            if (mapper.claim(job.id(), System.currentTimeMillis()) != 1) return;
            var user = new AuthenticatedUserPrincipal(job.userId(), job.authVersion());
            var scope = scope(user, job.enterpriseId(), job.spaceId());
            if (scope.memberId() != job.memberId()) throw missing();
            long remaining = job.deadlineMs() - System.currentTimeMillis();
            if (remaining <= 0) return;
            LOG.info("knowledge_job_started: job_id={}, remaining_ms={}", job.id(), remaining);
            TaskDeadline deadline = TaskDeadline.start(Duration.ofMillis(remaining));
            Answer answer;
            if (followUps.enabled()) {
                var context = followUps.resolve(user, job.enterpriseId(), job.spaceId(), job.id(), job.question(), deadline);
                answer = answers.answerWithin(user, job.enterpriseId(), job.spaceId(), job.question(), deadline, context);
            } else answer = answers.answerWithin(user, job.enterpriseId(), job.spaceId(), job.question(), deadline);
            // 不存原始供应商响应；仅保存已通过引用契约的应用结果，CAS不覆盖取消/超时终态。
            int saved = mapper.finish(job.id(), "COMPLETED", json.writeValueAsString(answer), null, System.currentTimeMillis());
            LOG.info("knowledge_job_finished: job_id={}, status=COMPLETED, applied={}", job.id(), saved == 1);
        } catch (BusinessException ex) {
            mapper.finish(job.id(), "FAILED", null, ex.getCode(), System.currentTimeMillis());
            LOG.info("knowledge_job_finished: job_id={}, status=FAILED, code={}", job.id(), ex.getCode());
        } catch (Exception ex) {
            LOG.error("knowledge_job_failed: job_id={}, type={}", job.id(), ex.getClass().getSimpleName());
            try { mapper.finish(job.id(), "FAILED", null, 500, System.currentTimeMillis()); }
            catch (DataAccessException unavailable) { LOG.error("knowledge_job_result_unknown: 无法持久化终态，截止后回收，不自动重放"); }
        } finally {
            running.remove(job.id());
            MDC.remove("request_id");
        }
    }

    private Job view(AuthenticatedUserPrincipal user, StoredJob job) {
        Answer answer = null;
        String message = switch (job.status()) {
            case "PENDING" -> "问题已受理，等待有界执行；刷新或断网后查询原任务即可。";
            case "RUNNING" -> "正在检索和核对依据，可继续提交新问题；不会无限重试。";
            case "COMPLETED" -> "处理完成。历史引用每次读取仍需核验当前权限与资料。";
            case "CANCELLED" -> "任务已取消，不会继续保存回答；第三方已发起的调用可能仍计费。";
            case "TIMED_OUT" -> "任务超过首次受理后30秒，已结束；不会自动重调模型。";
            default -> "处理失败，请核对服务或联系管理员；本次不会自动重试。";
        };
        if ("COMPLETED".equals(job.status()) && job.answerJson() != null) {
            try {
                Answer stored = json.readValue(job.answerJson(), Answer.class);
                boolean readable = true;
                for (var evidence : stored.evidence()) {
                    try {
                        if (!evidence.equals(answers.evidence(user, job.enterpriseId(), job.spaceId(), evidence.chunkId()))) readable = false;
                    } catch (BusinessException ex) {
                        if (ex.getCode() == 404 || ex.getCode() == 409) readable = false;
                        else throw ex;
                    }
                }
                if (readable) answer = stored;
                else message = "引用资料已撤回、更新或不可访问，历史回答暂不展示，请重新提问。";
            } catch (JsonProcessingException ex) {
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "历史回答格式无法读取，请联系管理员");
            }
        }
        return new Job(job.id(), job.chatId(), job.question(), job.status(), job.createdMs(), job.deadlineMs(),
                job.finishedMs(), answer, message);
    }

    public Feedback feedback(AuthenticatedUserPrincipal user, long enterprise, long space, FeedbackRequest request) {
        if (request == null) throw invalid();
        uuid(request.id()); uuid(request.jobId());
        String comment = text(request.comment(), 1000);
        if (!Set.of("INCORRECT", "MISSING", "OUTDATED", "OTHER").contains(Objects.toString(request.category(), ""))) throw invalid();
        var scope = scope(user, enterprise, space);
        return tx.execute(status -> {
            mapper.lockOwner(scope);
            Job job = job(user, enterprise, space, request.jobId());
            if (!"COMPLETED".equals(job.status())) throw conflict();
            if (request.chunkId() != null && (job.answer() == null || job.answer().evidence().stream()
                    .noneMatch(item -> item.chunkId().equals(request.chunkId())))) throw invalid();
            Feedback old = mapper.ownFeedback(scope, space, request.id());
            if (old != null) {
                if (!old.jobId().equals(request.jobId()) || !Objects.equals(old.chunkId(), request.chunkId())
                        || !old.category().equals(request.category()) || !old.commentText().equals(comment)) throw conflict();
                return old;
            }
            mapper.feedback(scope, space, request.id(), request.jobId(), request.chunkId(), request.category(), comment, System.currentTimeMillis());
            return mapper.ownFeedback(scope, space, request.id());
        });
    }

    public List<Feedback> feedbacks(AuthenticatedUserPrincipal user, long enterprise, long space) {
        return tx.execute(status -> {
            var scope = scope(user, enterprise, space);
            if (management.lockMaintain(scope, space) == null) throw missing();
            return mapper.feedbacks(scope, space);
        });
    }

    public void resolve(AuthenticatedUserPrincipal user, long enterprise, long space, String id, Resolution request) {
        uuid(id);
        if (request == null || request.revision() <= 0
                || !Set.of("RESOLVED", "DISMISSED").contains(Objects.toString(request.status(), ""))) throw invalid();
        String resolution = text(request.resolution(), 1000);
        tx.executeWithoutResult(status -> {
            var scope = scope(user, enterprise, space);
            if (management.lockMaintain(scope, space) == null) throw missing();
            if (mapper.resolve(scope, space, id, request.revision(), request.status(), resolution) != 1) throw conflict();
        });
    }

    private EnterpriseMemberContext scope(AuthenticatedUserPrincipal user, long enterprise, long space) {
        if (space <= 0) throw invalid();
        var member = members.requireMember(user, enterprise);
        resources.requireSpace(member, space);
        return member;
    }
    private static void uuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid(); }
        catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(cp ->
                (Character.isISOControl(cp) && cp != '\n' && cp != '\t' && cp != '\r')
                        || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE)) throw invalid();
        return value.strip();
    }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "请求编号或文本格式不正确"); }
    private static BusinessException missing() { return new BusinessException(ResultCode.NOT_FOUND, "资源不存在或不可访问"); }
    private static BusinessException conflict() { return new BusinessException(ResultCode.CONFLICT, "请求编号已使用或状态已变化，请查询原记录"); }

    @PreDestroy public void close() { scheduler.shutdownNow(); running.values().forEach(task -> task.cancel(true)); workers.shutdownNow(); }
}
