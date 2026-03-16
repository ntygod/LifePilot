package com.lifepilot.scheduler;

import com.lifepilot.scheduler.config.SchedulerProperties;
import com.lifepilot.scheduler.model.ScheduledTask;
import com.lifepilot.scheduler.model.TaskAction;
import com.lifepilot.scheduler.model.TaskActionCodec;
import com.lifepilot.scheduler.model.TaskStatus;
import com.lifepilot.scheduler.model.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度器核心。
 *
 * <p>封装 {@link ScheduledExecutorService}（Virtual Thread 工厂），
 * 提供一次性调度、cron 周期调度、取消、恢复和优雅关闭能力。
 * 使用 {@link ConcurrentHashMap} 维护 taskId → {@link ScheduledFuture} 映射。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final TaskActionExecutor actionExecutor;
    private final ScheduledTaskRepository repository;
    private final SchedulerProperties properties;

    public TaskScheduler(TaskActionExecutor actionExecutor,
                         ScheduledTaskRepository repository,
                         SchedulerProperties properties) {
        this.actionExecutor = actionExecutor;
        this.repository = repository;
        this.properties = properties;
        this.executor = Executors.newScheduledThreadPool(
                properties.getCorePoolSize(),
                Thread.ofVirtual().factory()
        );
        log.info("TaskScheduler 初始化完成: corePoolSize={}", properties.getCorePoolSize());
    }

    /**
     * 注册一次性任务调度。
     *
     * <p>解析 {@code task.triggerAt()} 为 {@link Instant}，计算延迟毫秒数。
     * 若触发时间已过期（delay ≤ 0），立即执行（delay = 0）。</p>
     *
     * @param task 定时任务实体
     */
    public void scheduleOnce(ScheduledTask task) {
        Instant triggerAt = Instant.parse(task.triggerAt());
        long delay = Duration.between(Instant.now(), triggerAt).toMillis();
        if (delay <= 0) {
            delay = 0;
            log.info("一次性任务触发时间已过期，立即执行: taskId={}, taskName={}", task.id(), task.name());
        }
        ScheduledFuture<?> future = executor.schedule(
                () -> executeTask(task), delay, TimeUnit.MILLISECONDS
        );
        futures.put(task.id(), future);
        log.info("一次性任务已注册调度: taskId={}, taskName={}, delayMs={}", task.id(), task.name(), delay);
    }

    /**
     * 注册周期性任务调度（基于 cron 表达式）。
     *
     * <p>使用 Spring {@link CronExpression} 解析 cron 表达式，
     * 计算下次触发时间的延迟，执行后自动重新调度下一次。</p>
     *
     * @param task 定时任务实体
     */
    public void scheduleCron(ScheduledTask task) {
        CronExpression cron = CronExpression.parse(task.cronExpr());
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) {
            log.warn("cron 表达式无下次触发时间，跳过调度: taskId={}, cronExpr={}", task.id(), task.cronExpr());
            return;
        }
        long delay = Duration.between(LocalDateTime.now(), next).toMillis();
        if (delay <= 0) {
            delay = 0;
        }
        ScheduledFuture<?> future = executor.schedule(
                () -> executeTask(task), delay, TimeUnit.MILLISECONDS
        );
        futures.put(task.id(), future);
        log.info("周期性任务已注册调度: taskId={}, taskName={}, nextTrigger={}, delayMs={}",
                task.id(), task.name(), next, delay);
    }

    /**
     * 取消指定任务的调度。
     *
     * <p>从 futures 映射表中获取 {@link ScheduledFuture}，
     * 调用 {@code cancel(false)}（不中断正在执行的任务），然后从映射表移除。</p>
     *
     * @param taskId 任务 ID
     * @return 如果找到并取消返回 true，否则返回 false
     */
    public boolean cancel(String taskId) {
        ScheduledFuture<?> future = futures.remove(taskId);
        if (future == null) {
            log.warn("取消调度失败，任务不在调度映射中: taskId={}", taskId);
            return false;
        }
        future.cancel(false);
        log.info("任务调度已取消: taskId={}", taskId);
        return true;
    }

    /**
     * 恢复所有 PENDING 状态的任务。
     *
     * <p>应用启动时调用，从数据库加载所有 PENDING 任务：
     * <ul>
     *   <li>ONCE 类型且触发时间已过期 → 立即执行（scheduleOnce 内部处理 delay=0）</li>
     *   <li>ONCE 类型且触发时间未到 → 正常注册 scheduleOnce</li>
     *   <li>CRON 类型 → 注册 scheduleCron</li>
     * </ul>
     */
    public void recoverAll() {
        List<ScheduledTask> pendingTasks = repository.findAllByStatus(TaskStatus.PENDING);
        int count = 0;
        for (ScheduledTask task : pendingTasks) {
            try {
                if (task.triggerType() == TriggerType.ONCE) {
                    scheduleOnce(task);
                } else if (task.triggerType() == TriggerType.CRON) {
                    scheduleCron(task);
                }
                count++;
            } catch (Exception e) {
                log.error("恢复任务失败: taskId={}, taskName={}, error={}",
                        task.id(), task.name(), e.getMessage(), e);
            }
        }
        log.info("任务恢复完成: 恢复数量={}, 总 PENDING 数量={}", count, pendingTasks.size());
    }

    /**
     * 优雅关闭调度器。
     *
     * <p>先调用 {@code shutdown()} 停止接受新任务，
     * 等待最多 10 秒让正在执行的任务完成，超时则强制关闭。</p>
     */
    public void shutdown() {
        log.info("TaskScheduler 开始关闭...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("TaskScheduler 等待超时，强制关闭");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("TaskScheduler 关闭被中断，强制关闭");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("TaskScheduler 已关闭");
    }

    /**
     * 任务执行回调 — 调度器触发时的核心逻辑。
     *
     * <p>执行流程：
     * <ol>
     *   <li>更新任务状态为 RUNNING</li>
     *   <li>反序列化 actionJson 为 {@link TaskAction}</li>
     *   <li>调用 {@link TaskActionExecutor#execute(ScheduledTask, TaskAction)}</li>
     *   <li>成功后：ONCE 任务更新为 COMPLETED 并移除 future；CRON 任务保持 PENDING，更新触发时间并重新调度</li>
     *   <li>异常时：更新为 FAILED 并记录错误信息，移除 future</li>
     * </ol>
     *
     * @param task 定时任务实体
     */
    private void executeTask(ScheduledTask task) {
        try {
            // 更新状态为 RUNNING
            repository.updateStatus(task.id(), TaskStatus.RUNNING, null);

            // 反序列化动作并执行
            TaskAction action = TaskActionCodec.fromJson(task.actionJson());
            actionExecutor.execute(task, action);

            // 执行成功后处理
            if (task.triggerType() == TriggerType.ONCE) {
                repository.updateStatus(task.id(), TaskStatus.COMPLETED, null);
                futures.remove(task.id());
                log.info("一次性任务执行完成: taskId={}, taskName={}", task.id(), task.name());
            } else if (task.triggerType() == TriggerType.CRON) {
                rescheduleCron(task);
                log.info("周期性任务执行完成，已重新调度: taskId={}, taskName={}", task.id(), task.name());
            }
        } catch (Exception e) {
            log.error("任务执行失败: taskId={}, taskName={}, error={}",
                    task.id(), task.name(), e.getMessage(), e);
            repository.updateStatus(task.id(), TaskStatus.FAILED, e.getMessage());
            futures.remove(task.id());
        }
    }

    /**
     * CRON 任务执行成功后重新调度下一次执行。
     *
     * <p>使用 {@link CronExpression} 计算下次触发时间，
     * 更新数据库中的 lastTriggeredAt 和 nextTriggerAt，
     * 然后注册下一次调度。</p>
     *
     * @param task 定时任务实体
     */
    private void rescheduleCron(ScheduledTask task) {
        CronExpression cron = CronExpression.parse(task.cronExpr());
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) {
            log.warn("cron 表达式无下次触发时间，任务不再调度: taskId={}", task.id());
            repository.updateStatus(task.id(), TaskStatus.COMPLETED, null);
            futures.remove(task.id());
            return;
        }

        String nextTriggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toString();
        String now = Instant.now().toString();

        // 更新数据库：保持 PENDING 状态，更新 lastTriggeredAt 和 nextTriggerAt
        repository.update(new ScheduledTask(
                task.id(), task.name(), task.triggerType(), task.triggerAt(), task.cronExpr(),
                task.actionJson(), TaskStatus.PENDING, null,
                now, nextTriggerAt,
                task.metadataJson(), task.createdAt(), Instant.now().toString()
        ));

        // 注册下一次调度
        long delay = Duration.between(LocalDateTime.now(), next).toMillis();
        if (delay <= 0) {
            delay = 0;
        }
        ScheduledFuture<?> future = executor.schedule(
                () -> executeTask(task), delay, TimeUnit.MILLISECONDS
        );
        futures.put(task.id(), future);
    }
}
