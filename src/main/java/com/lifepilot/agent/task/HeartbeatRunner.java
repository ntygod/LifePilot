package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.ProactiveEngine;
import com.lifepilot.agent.task.reminder.ProactiveReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.LocalTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 心跳唤醒运行器。
 *
 * <p>仅负责按固定频率唤醒主动提醒引擎，并执行活跃时段控制。
 * 不再直接读取 HEARTBEAT.md，也不再通过 Agent 运行巡检 checklist。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class HeartbeatRunner {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatRunner.class);

    private final ScheduledExecutorService scheduler;
    private final AgentConfigProperties config;
    @Nullable
    private final ProactiveReminderService proactiveReminderService;
    @Nullable
    private final ProactiveEngine proactiveEngine;

    public HeartbeatRunner(ScheduledExecutorService scheduler,
                           AgentConfigProperties config) {
        this(scheduler, config, null, null);
    }

    public HeartbeatRunner(ScheduledExecutorService scheduler,
                           AgentConfigProperties config,
                           @Nullable ProactiveReminderService proactiveReminderService) {
        this(scheduler, config, proactiveReminderService, null);
    }

    public HeartbeatRunner(ScheduledExecutorService scheduler,
                           AgentConfigProperties config,
                           @Nullable ProactiveReminderService proactiveReminderService,
                           @Nullable ProactiveEngine proactiveEngine) {
        this.scheduler = scheduler;
        this.config = config;
        this.proactiveReminderService = proactiveReminderService;
        this.proactiveEngine = proactiveEngine;
    }

    /**
     * 启动心跳。在 ApplicationReadyEvent 时调用。
     */
    public void start() {
        long intervalMs = config.getTask().getHeartbeatIntervalSeconds() * 1000L;
        scheduler.scheduleAtFixedRate(this::beat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("心跳唤醒已启动: interval={}s",
                config.getTask().getHeartbeatIntervalSeconds());
    }

    /**
     * 单次心跳。
     */
    void beat() {
        // 活跃时段检查
        if (!isWithinActiveHours()) {
            log.debug("心跳跳过: 当前不在活跃时段");
            return;
        }

        // 优先使用新引擎，无引擎时回退到旧服务
        if (proactiveEngine != null) {
            runProactiveEngineIfEnabled();
        } else {
            runProactiveReminderIfEnabled();
        }
    }

    private void runProactiveEngineIfEnabled() {
        if (!config.getTask().isProactiveReminderEnabled()) {
            log.debug("心跳跳过: 主动引擎未启用");
            return;
        }
        try {
            var level = proactiveEngine.heartbeat();
            log.debug("主动引擎心跳完成: level={}", level);
        } catch (Exception e) {
            log.warn("主动引擎心跳异常: {}", e.getMessage());
        }
    }

    private void runProactiveReminderIfEnabled() {
        if (!config.getTask().isProactiveReminderEnabled() || proactiveReminderService == null) {
            log.debug("心跳跳过: 主动提醒未启用或服务不可用");
            return;
        }
        try {
            var result = proactiveReminderService.runOnce();
            log.debug("主动提醒评估完成: topics={}, decisions={}, sent={}",
                    result.topicsCollected(), result.decisionsEvaluated(), result.remindersSent());
        } catch (Exception e) {
            log.warn("主动提醒执行异常: {}", e.getMessage());
        }
    }

    /**
     * 判断当前是否在活跃时段内。支持跨午夜配置。
     *
     * @return 是否在活跃时段
     */
    boolean isWithinActiveHours() {
        var task = config.getTask();
        if (task.getActiveHoursStart() == null || task.getActiveHoursEnd() == null) {
            return true; // 未配置则全天活跃
        }
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(task.getActiveHoursStart());
        LocalTime end = LocalTime.parse(task.getActiveHoursEnd());

        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        } else {
            // 跨午夜：如 22:00 - 08:00
            return !now.isBefore(start) || now.isBefore(end);
        }
    }

}
