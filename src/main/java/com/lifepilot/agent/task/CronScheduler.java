package com.lifepilot.agent.task;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cron 精确定时调度器。
 *
 * <p>任务创建时立即注册精确定时器，不经过任何扫描。
 * 触发后递归注册下一次，实现持续调度。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final CronTaskRepository repository;
    private final AgentOrchestrator agentOrchestrator;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;

    public CronScheduler(ScheduledExecutorService scheduler,
                         CronTaskRepository repository,
                         AgentOrchestrator agentOrchestrator,
                         NotificationService notificationService,
                         NotificationProperties notificationProperties) {
        this.scheduler = scheduler;
        this.repository = repository;
        this.agentOrchestrator = agentOrchestrator;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 注册 cron 任务的精确定时器。
     * 仅调度 active 状态的任务。
     *
     * @param task 任务条目
     */
    public void schedule(CronTaskEntry task) {
        if (!"active".equals(task.status())) return;

        CronExpression cron = CronExpression.parse(task.schedule());
        scheduleNext(task.id(), cron, task.instruction(), task.name());
        log.info("Cron 任务已注册: taskId={}, name={}, schedule={}", task.id(), task.name(), task.schedule());
    }

    /**
     * 取消指定任务的定时器。
     *
     * @param taskId 任务 ID
     */
    public void cancel(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("Cron 任务已取消: taskId={}", taskId);
        }
    }

    /**
     * 系统启动时恢复所有 active 任务的定时器。
     */
    public void restoreAll() {
        var tasks = repository.findByStatus("active");
        tasks.forEach(this::schedule);
        log.info("Cron 任务恢复完成: count={}", tasks.size());
    }

    /**
     * 递归调度：执行完当前触发后，自动注册下一次。
     */
    private void scheduleNext(String taskId, CronExpression cron, String instruction, String name) {
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) return;

        long delayMs = Duration.between(LocalDateTime.now(), next).toMillis();
        if (delayMs < 0) delayMs = 0;

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            // 执行前重新从 DB 确认任务仍然 active
            var current = repository.findById(taskId);
            if (current.isEmpty() || !"active".equals(current.get().status())) return;

            // 执行任务
            executeTask(current.get());

            // 注册下一次触发
            scheduleNext(taskId, cron, instruction, name);
        }, delayMs, TimeUnit.MILLISECONDS);

        // 替换旧的 future（如果有）
        ScheduledFuture<?> old = scheduledTasks.put(taskId, future);
        if (old != null) old.cancel(false);
    }

    /**
     * 执行 Cron 任务：构造 prompt → 调用 AgentOrchestrator → 写入日志 → 通知。
     */
    void executeTask(CronTaskEntry task) {
        Instant start = Instant.now();
        String status = "success";
        String summary = null;
        int tokensUsed = 0;

        try {
            // 构造 AgentRequest — 有绑定 Skill 时 prepend 加载指令
            String instruction = task.instruction();
            if (task.skillIds() != null && !task.skillIds().isBlank()) {
                instruction = "首先加载技能指南: file.read(skill=\"%s\"), 然后执行以下任务:\n%s"
                        .formatted(task.skillIds(), instruction);
            }
            String prompt = "[定时任务: %s]\n%s".formatted(task.name(), instruction);
            String sessionId = "cron:" + task.id();
            var request = new AgentRequest(prompt, sessionId, InteractionSource.cron(sessionId));

            // 调用 AgentOrchestrator
            AgentResponse response = agentOrchestrator.run(request);

            summary = truncate(response.content(), 500);
            tokensUsed = response.tokensUsed();

            // TASK_SILENT 协议
            boolean silent = isSilentResponse(response.content(), "TASK_SILENT");

            // 通知用户
            if (!silent && response.content() != null && !response.content().isBlank()
                    && response.terminationReason() == null) {
                notificationService.send(new NotificationRequest(
                        notificationProperties.getDefaultUserId(),
                        new ResponseContent.TextContent("【%s】\n%s".formatted(task.name(), response.content())),
                        null, "cron_task",
                        Map.of("taskId", task.id(), "taskName", task.name())
                ));
            }

            if (response.terminationReason() != null) {
                status = "failed";
                log.warn("Cron 任务执行失败: taskId={}, name={}, reason={}",
                        task.id(), task.name(), response.terminationReason());
            }
        } catch (Exception e) {
            status = "failed";
            summary = e.getMessage();
            log.error("Cron 任务执行异常: taskId={}, name={}", task.id(), task.name(), e);
        } finally {
            // 写入执行日志
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            repository.saveLog(new CronTaskLog(
                    UUID.randomUUID().toString(), task.id(),
                    Instant.now().toString(), status, durationMs, tokensUsed, summary,
                    Instant.now().toString()
            ));
        }
    }

    /**
     * 判断回复是否为静默响应。
     * token 仅在回复的开头或结尾出现时被识别，中间出现不处理。
     *
     * @param content 回复内容
     * @param token   静默标记
     * @return 是否静默
     */
    static boolean isSilentResponse(@Nullable String content, String token) {
        if (content == null || content.isBlank()) return true;
        String trimmed = content.strip();
        return trimmed.startsWith(token) || trimmed.endsWith(token);
    }

    /** 截断字符串到指定长度。 */
    private static String truncate(@Nullable String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
