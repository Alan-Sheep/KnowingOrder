package com.ragqa.rag.chat;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用可控时钟验证总预算；不把计算正确等同于真实调用已被中止。 */
class TaskDeadlineTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-16T01:00:00Z");
    private final AtomicLong ticks = new AtomicLong(100);

    @Test
    void publicEntryCreatesUsableUtcDeadline() {
        Instant before = Instant.now();
        TaskDeadline deadline = TaskDeadline.start(Duration.ofSeconds(90));
        Instant after = Instant.now();

        assertThat(deadline.receivedAt()).isBetween(before, after);
        assertThat(deadline.deadlineAt()).isEqualTo(deadline.receivedAt().plusSeconds(90));
        assertThat(deadline.remaining()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void queueSearchBackoffAndGenerationConsumeOneBudget() {
        TaskDeadline deadline = start(Duration.ofSeconds(90));
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(90));

        advance(Duration.ofSeconds(10));
        assertThat(deadline.timeoutFor(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(10));
        advance(Duration.ofSeconds(10));
        advance(Duration.ofMillis(400));
        assertThat(deadline.timeoutFor(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
        advance(Duration.ofSeconds(60));

        assertThat(deadline.remaining()).isEqualTo(Duration.ofMillis(9600));
        assertThat(deadline.timeoutFor(Duration.ofSeconds(60))).isEqualTo(Duration.ofMillis(9600));
        assertThat(deadline.receivedAt()).isEqualTo(RECEIVED);
        assertThat(deadline.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(90));
    }

    @Test
    void repeatedReadsAndSupplementProcessingDoNotRenewDeadline() {
        TaskDeadline deadline = start(Duration.ofSeconds(90));
        for (int i = 1; i <= 9; i++) {
            advance(Duration.ofSeconds(10));
            assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(90 - i * 10));
            assertThat(deadline.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(90));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {90, 91, 86_400})
    void expiredBudgetRejectsNextStepWithSafeBusinessFeedback(long seconds) {
        TaskDeadline deadline = start(Duration.ofSeconds(90));
        advance(Duration.ofSeconds(seconds));
        AtomicInteger calls = new AtomicInteger();

        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> {
            deadline.timeoutFor(Duration.ofSeconds(10));
            calls.incrementAndGet();
        }).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ResultCode.SERVICE_BUSY.getCode());
            assertThat(exception.getMessage()).isEqualTo("本次处理已超时，请缩小问题范围后重试");
            assertThat(exception.getCause()).isNull();
        });
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(longs = {-86_400, 86_400})
    void wallClockJumpsCannotExtendOrShortenRunningBudget(long wallShift) {
        AtomicReference<Instant> wall = new AtomicReference<>(RECEIVED);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return Clock.fixed(wall.get(), zone);
            }

            @Override
            public Instant instant() {
                return wall.get();
            }
        };
        TaskDeadline deadline = TaskDeadline.start(Duration.ofSeconds(90), clock, ticks::get);
        wall.set(RECEIVED.plusSeconds(wallShift));
        advance(Duration.ofSeconds(30));

        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(60));
        assertThat(deadline.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(90));
    }

    @Test
    void oneNanosecondRemainingIsNotRoundedUpToOneMillisecond() {
        TaskDeadline deadline = start(Duration.ofMillis(1));
        advance(Duration.ofNanos(999_999));

        assertThat(deadline.timeoutFor(Duration.ofSeconds(1))).isEqualTo(Duration.ofNanos(1));
        advance(Duration.ofNanos(1));
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void signedNanosecondCounterWrapDoesNotResetBudget() {
        ticks.set(Long.MAX_VALUE - 2);
        TaskDeadline deadline = start(Duration.ofNanos(10));
        advance(Duration.ofNanos(5));

        assertThat(deadline.remaining()).isEqualTo(Duration.ofNanos(5));
        advance(Duration.ofNanos(5));
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void invalidBackwardsTickerFailsClosed() {
        TaskDeadline deadline = start(Duration.ofSeconds(90));
        ticks.decrementAndGet();

        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsMissingOrNonPositiveTotalBudget(Duration budget) {
        assertThatThrownBy(() -> start(budget)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("totalBudget 必须为正时长");
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsMissingOrNonPositiveStageLimit(Duration stageLimit) {
        TaskDeadline deadline = start(Duration.ofSeconds(90));

        assertThatThrownBy(() -> deadline.timeoutFor(stageLimit)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stageLimit 必须为正时长");
    }

    static Stream<Duration> invalidDurations() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1));
    }

    @Test
    void rejectsTotalBudgetOverflowWithoutEchoingInput() {
        assertThatThrownBy(() -> start(Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("totalBudget 超出可表示的计时范围")
                .hasNoCause();
    }

    @Test
    void rejectsUnrepresentableDisplayDeadline() {
        assertThatThrownBy(() -> TaskDeadline.start(Duration.ofSeconds(1),
                Clock.fixed(Instant.MAX, ZoneOffset.UTC), ticks::get))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("totalBudget 超出可表示的计时范围");
    }

    @Test
    void hugeStageLimitStillUsesFiniteRemainingBudget() {
        TaskDeadline deadline = start(Duration.ofSeconds(90));

        assertThat(deadline.timeoutFor(Duration.ofSeconds(Long.MAX_VALUE))).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @Timeout(5)
    void concurrentSubtasksShareRemainingBudgetInsteadOfReceivingFreshBudgets() throws Exception {
        TaskDeadline deadline = start(Duration.ofSeconds(90));
        advance(Duration.ofSeconds(70));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Duration>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> deadline.timeoutFor(Duration.ofSeconds(60))));
            }
            for (Future<Duration> result : results) {
                assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(Duration.ofSeconds(20));
            }
            assertThat(deadline.deadlineAt()).isEqualTo(RECEIVED.plusSeconds(90));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TaskDeadline start(Duration budget) {
        return TaskDeadline.start(budget, Clock.fixed(RECEIVED, ZoneOffset.UTC), ticks::get);
    }

    private void advance(Duration duration) {
        ticks.addAndGet(duration.toNanos());
    }
}
