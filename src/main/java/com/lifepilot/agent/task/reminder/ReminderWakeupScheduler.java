package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动提醒延后唤醒调度器。
 *
 * <p>定期扫描最近一条决策仍为 {@code DEFER_TO_WINDOW} 且已到期的主题，
 * 重新送回主动提醒决策引擎评估，形成真正的二次唤醒闭环。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderWakeupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderWakeupScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final ReminderExecutionRepository executionRepository;
    private final ProactiveReminderService proactiveReminderService;
    private final AgentConfigProperties config;

    public ReminderWakeupScheduler(ScheduledExecutorService scheduler,
                                   ReminderExecutionRepository executionRepository,
                                   ProactiveReminderService proactiveReminderService,
                                   AgentConfigProperties config) {
        this.scheduler = scheduler;
        this.executionRepository = executionRepository;
        this.proactiveReminderService = proactiveReminderService;
        this.config = config;
    }

    /**
     * 启动延后唤醒扫描。
     */
    public void start() {
        long intervalMs = Math.max(30L, config.getTask().getProactiveReminderWakeupScanIntervalSeconds()) * 1000L;
        long initialDelayMs = Math.min(5000L, intervalMs);
        scheduler.scheduleAtFixedRate(this::scan, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("主动提醒延后唤醒调度器已启动: interval={}s, batchSize={}",
                config.getTask().getProactiveReminderWakeupScanIntervalSeconds(),
                config.getTask().getProactiveReminderWakeupBatchSize());
    }

    /**
     * 扫描已到期的延后唤醒主题并重新评估。
     */
    public void scan() {
        if (!config.getTask().isProactiveReminderEnabled()) {
            return;
        }
        int batchSize = Math.max(1, config.getTask().getProactiveReminderWakeupBatchSize());
        try {
            List<ReminderDeferredWakeup> dueWakeups = executionRepository.findDueDeferredWakeups(Instant.now(), batchSize);
            if (dueWakeups.isEmpty()) {
                return;
            }
            var result = proactiveReminderService.runDeferredWakeups(dueWakeups);
            log.debug("主动提醒延后唤醒完成: wakeups={}, topics={}, decisions={}, sent={}",
                    dueWakeups.size(), result.topicsCollected(), result.decisionsEvaluated(), result.remindersSent());
        } catch (Exception e) {
            log.warn("主动提醒延后唤醒异常: {}", e.getMessage());
        }
    }
}
