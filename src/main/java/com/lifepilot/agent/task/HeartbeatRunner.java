package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.ProactiveEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.LocalTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 心跳唤醒运行器。
 *
 * <p>仅负责按固定频率唤醒主动引擎，并执行活跃时段控制。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class HeartbeatRunner {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatRunner.class);

    private final ScheduledExecutorService scheduler;
    private final AgentConfigProperties config;
    @Nullable
    private final ProactiveEngine proactiveEngine;

    public HeartbeatRunner(ScheduledExecutorService scheduler,
                           AgentConfigProperties config,
                           @Nullable ProactiveEngine proactiveEngine) {
        this.scheduler = scheduler;
        this.config = config;
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
        if (!isWithinActiveHours()) {
            log.debug("心跳跳过: 当前不在活跃时段");
            return;
        }

        if (proactiveEngine == null) {
            log.debug("心跳跳过: 主动引擎未注册");
            return;
        }

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
