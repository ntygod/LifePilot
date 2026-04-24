package com.lifepilot.memory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * 测试用手动调度器。
 *
 * <p>不启动任何真实的调度线程；所有注册的任务都保存在内部队列，必须显式调用
 * {@link #triggerDueAt(Instant)} 才会按到期时间触发。</p>
 *
 * <p>与真实 {@link org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler} 的差异：
 * <ul>
 *   <li>不支持并发执行；所有触发均在调用线程中同步运行</li>
 *   <li>周期任务只记录首次到期时间；触发后从队列移除，不自动续期</li>
 *   <li>{@link FakeScheduledFuture#get()} 抛 {@link UnsupportedOperationException}</li>
 * </ul>
 * 配合 {@link MutableClock} 可以在场景测试里完全控制时间和调度的触发节点。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ManualTaskScheduler implements TaskScheduler {

    /**
     * 队列中待触发的任务。
     *
     * @param task        待执行的 Runnable
     * @param nextFireAt  预期触发时刻
     * @param description 人类可读描述，便于单测断言
     */
    private record PendingTask(Runnable task, Instant nextFireAt, String description) {
    }

    private final List<PendingTask> tasks = Collections.synchronizedList(new ArrayList<>());

    private final Clock clock;

    /** 默认构造器（系统时钟，主要用于简单单元测试）。 */
    public ManualTaskScheduler() {
        this(Clock.systemUTC());
    }

    /**
     * 构造可注入时钟的调度器，主要用于场景测试场景下将 {@link MutableClock} 与调度器时间源对齐。
     *
     * @param clock 任务注册时读取当前时刻的 {@link Clock}
     */
    public ManualTaskScheduler(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /**
     * 触发所有到期任务并从队列移除。
     *
     * <p>判定规则：{@code nextFireAt <= now} 的任务都会被触发（即 {@code isAfter(now)} 为 false）。</p>
     *
     * @param now 当前时刻
     * @return 被触发任务的描述列表，顺序与注册顺序一致
     */
    public List<String> triggerDueAt(Instant now) {
        var results = new ArrayList<String>();
        synchronized (tasks) {
            var iter = tasks.iterator();
            while (iter.hasNext()) {
                var pending = iter.next();
                if (!pending.nextFireAt().isAfter(now)) {
                    pending.task().run();
                    results.add(pending.description());
                    iter.remove();
                }
            }
        }
        return results;
    }

    /**
     * 返回当前队列中待触发任务数量，仅用于单测断言。
     */
    public int pendingCount() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    /**
     * 清空所有待触发任务，仅用于单测之间隔离。
     */
    public void reset() {
        synchronized (tasks) {
            tasks.clear();
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        Instant next = trigger.nextExecution(new EmptyTriggerContext());
        if (next == null) {
            return new FakeScheduledFuture<>();
        }
        tasks.add(new PendingTask(task, next, "trigger:" + trigger));
        return new FakeScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        tasks.add(new PendingTask(task, startTime, "at:" + startTime));
        return new FakeScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        tasks.add(new PendingTask(task, startTime, "fixedRate@" + startTime + ":" + period));
        return new FakeScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        Instant fireAt = clock.instant().plus(period);
        tasks.add(new PendingTask(task, fireAt, "fixedRate:" + period));
        return new FakeScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        tasks.add(new PendingTask(task, startTime, "fixedDelay@" + startTime + ":" + delay));
        return new FakeScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        Instant fireAt = clock.instant().plus(delay);
        tasks.add(new PendingTask(task, fireAt, "fixedDelay:" + delay));
        return new FakeScheduledFuture<>();
    }

    /**
     * 空的 TriggerContext：首次调度时没有上一次执行信息。
     */
    private static final class EmptyTriggerContext implements TriggerContext {
        @Override
        public Instant lastScheduledExecution() {
            return null;
        }

        @Override
        public Instant lastActualExecution() {
            return null;
        }

        @Override
        public Instant lastCompletion() {
            return null;
        }
    }

    /**
     * ScheduledFuture 的假实现：所有状态方法返回安全默认值，{@code get()} 显式抛不支持异常。
     */
    private static final class FakeScheduledFuture<T> implements ScheduledFuture<T> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() {
            throw new UnsupportedOperationException("ManualTaskScheduler 不支持 ScheduledFuture.get()");
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("ManualTaskScheduler 不支持 ScheduledFuture.get(timeout)");
        }
    }
}
