package com.ragqa.rag.chat;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 单次任务共享的总时限预算，在请求入口创建，排队及后续阶段复用同一实例。
 *
 * <p>UTC 时间用于快照展示；运行耗时使用单调时钟，不受系统时间校准影响。
 * 本类只计算预算，不创建定时器、不取消调用，也不负责重启后的任务恢复。
 * 后续编排须在真正发送请求或开始等待前重新取预算，并设置实际超时与取消机制。</p>
 */
public final class TaskDeadline {

    private final Instant receivedAt;
    private final Instant deadlineAt;
    private final long startedNanos;
    private final long budgetNanos;
    private final LongSupplier ticker;

    private TaskDeadline(Instant receivedAt, Instant deadlineAt, long startedNanos,
            long budgetNanos, LongSupplier ticker) {
        this.receivedAt = receivedAt;
        this.deadlineAt = deadlineAt;
        this.startedNanos = startedNanos;
        this.budgetNanos = budgetNanos;
        this.ticker = ticker;
    }

    /** 从调用入口开始计时；预算由调用方传入，不在此固化生产阈值。 */
    public static TaskDeadline start(Duration totalBudget) {
        return start(totalBudget, Clock.systemUTC(), System::nanoTime);
    }

    /** 包内注入时钟，便于故障测试推进时间，无需真实等待或修改系统时钟。 */
    static TaskDeadline start(Duration totalBudget, Clock clock, LongSupplier ticker) {
        requirePositive(totalBudget, "totalBudget");
        Objects.requireNonNull(clock, "clock 不能为空");
        Objects.requireNonNull(ticker, "ticker 不能为空");
        try {
            long nanos = totalBudget.toNanos();
            long started = ticker.getAsLong();
            Instant received = clock.instant();
            return new TaskDeadline(received, received.plus(totalBudget), started, nanos, ticker);
        } catch (ArithmeticException | DateTimeException exception) {
            throw new IllegalArgumentException("totalBudget 超出可表示的计时范围");
        }
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    /** 返回非负剩余预算；读取不会续期。纳秒计数跨过有符号边界仍按差值计算。 */
    public Duration remaining() {
        long elapsed = ticker.getAsLong() - startedNanos;
        // 负差值意味着计时源异常或已超过可表示区间，保守地视为预算耗尽。
        if (elapsed < 0 || elapsed >= budgetNanos) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(budgetNanos - elapsed);
    }

    /**
     * 从当前阶段入口收紧截止时间，仍沿用父任务的接收时刻、单调时钟和计时起点。
     * 阶段实例创建一次后须复用；模板处理、发送及正文等待均消耗同一份阶段余额。
     * 本方法不修改父任务、不预留独占配额；HTTP调用仍须绑定返回实例的remaining。
     */
    public TaskDeadline limitedTo(Duration stageLimit) {
        requirePositive(stageLimit, "stageLimit");
        long elapsed = ticker.getAsLong() - startedNanos;
        if (elapsed < 0 || elapsed >= budgetNanos) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "本次处理已超时，请缩小问题范围后重试");
        }
        long available = budgetNanos - elapsed;
        // 先比较再转纳秒；阶段上限再大也不能溢出或延长父任务。
        long allowed = stageLimit.compareTo(Duration.ofNanos(available)) < 0 ? stageLimit.toNanos() : available;
        long cappedBudget = elapsed + allowed;
        return new TaskDeadline(receivedAt, receivedAt.plusNanos(cappedBudget), startedNanos, cappedBudget, ticker);
    }

    /**
     * 取阶段上限与剩余预算的较小值；预算已耗尽时禁止发起后续步骤。
     * 返回纳秒精度，接入毫秒精度客户端时不能向上取整而扩大剩余预算。
     */
    public Duration timeoutFor(Duration stageLimit) {
        requirePositive(stageLimit, "stageLimit");
        Duration remaining = remaining();
        if (remaining.isZero()) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "本次处理已超时，请缩小问题范围后重试");
        }
        return stageLimit.compareTo(remaining) < 0 ? stageLimit : remaining;
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " 必须为正时长");
        }
    }
}
