package com.ragqa.rag.chat;

import com.ragqa.common.ResultCode;
import com.ragqa.config.WebChatProperties;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.exception.BusinessException;

import java.util.concurrent.atomic.AtomicInteger;

/** 多个子问题共享同一时限、调用账本和时间快照；补查不会获得新额度。 */
public final class ChatBudget {

    private final TaskDeadline deadline;
    private final RequestTimeContext timeContext;
    private final String answerLanguage;
    private final int modelLimit;
    private final int searchLimit;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger searchCalls = new AtomicInteger();

    public ChatBudget(WebChatProperties properties) {
        // 兼容不携带问题的旧预算调用，只取请求结构中的默认时间偏好。
        this(properties, new ChatRequest(null, null, null));
    }

    public ChatBudget(WebChatProperties properties, ChatRequest request) {
        deadline = TaskDeadline.start(properties.getTotalTimeout());
        timeContext = RequestTimeContext.from(request, deadline);
        if (!java.util.Set.of("zh-CN", "en", "fr", "ja", "de", "es").contains(request.answerLanguage())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "回答语言不在支持范围内");
        }
        answerLanguage = request.answerLanguage();
        modelLimit = properties.getMaxModelCalls();
        searchLimit = properties.getMaxSearchCalls();
    }

    public TaskDeadline deadline() {
        return deadline;
    }

    /** 并发分支及补查复用同一实例，不在读取时重新取日期或续期。 */
    public RequestTimeContext timeContext() {
        return timeContext;
    }

    public String answerLanguage() {
        return answerLanguage;
    }

    public void beforeModel() {
        active();
        take(modelCalls, modelLimit);
    }

    public void beforeSearch() {
        active();
        take(searchCalls, searchLimit);
    }

    public void active() {
        if (Thread.currentThread().isInterrupted()) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "本次处理已取消");
        }
        deadline.timeoutFor(java.time.Duration.ofDays(1));
    }

    private static void take(AtomicInteger counter, int limit) {
        if (counter.incrementAndGet() > limit) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "本次调用额度已用尽，已停止后续调用");
        }
    }
}
