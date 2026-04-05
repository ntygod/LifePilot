package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动提醒样本治理调度器。
 *
 * <p>周期性清理超出保留期的运行数据、反馈和回放报告，
 * 避免长期运行后样本无限膨胀。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ReminderRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderRetentionScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final ReminderExecutionRepository executionRepository;
    private final ReminderFeedbackRepository feedbackRepository;
    private final ReminderReplayReportRepository replayReportRepository;
    private final ReminderTopicAliasRepository topicAliasRepository;
    private final AgentConfigProperties config;

    public ReminderRetentionScheduler(ScheduledExecutorService scheduler,
                                      ReminderExecutionRepository executionRepository,
                                      ReminderFeedbackRepository feedbackRepository,
                                      ReminderReplayReportRepository replayReportRepository,
                                      ReminderTopicAliasRepository topicAliasRepository,
                                      AgentConfigProperties config) {
        this.scheduler = scheduler;
        this.executionRepository = executionRepository;
        this.feedbackRepository = feedbackRepository;
        this.replayReportRepository = replayReportRepository;
        this.topicAliasRepository = topicAliasRepository;
        this.config = config;
    }

    /**
     * 启动样本治理清理任务。
     */
    public void start() {
        long intervalMs = Math.max(300L, config.getTask().getProactiveReminderCleanupIntervalSeconds()) * 1000L;
        long initialDelayMs = Math.min(20000L, intervalMs);
        scheduler.scheduleAtFixedRate(this::cleanup, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("主动提醒样本治理调度器已启动: interval={}s, retentionDays={}",
                config.getTask().getProactiveReminderCleanupIntervalSeconds(),
                config.getTask().getProactiveReminderRetentionDays());
    }

    /**
     * 清理超出保留期的数据。
     */
    public void cleanup() {
        if (!config.getTask().isProactiveReminderEnabled()) {
            return;
        }
        int retentionDays = Math.max(30, config.getTask().getProactiveReminderRetentionDays());
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 24L * 3600L);
        try {
            int deletedRuns = executionRepository.deleteRunsBefore(cutoff);
            int deletedFeedback = feedbackRepository.deleteFeedbackBefore(cutoff);
            int deletedReplayReports = replayReportRepository.deleteBefore(cutoff);
            int deletedAliases = topicAliasRepository.deleteStaleAliasesBefore(cutoff);
            if (deletedRuns + deletedFeedback + deletedReplayReports + deletedAliases > 0) {
                log.info("主动提醒样本治理完成: runs={}, feedback={}, replayReports={}, aliases={}, cutoff={}",
                        deletedRuns, deletedFeedback, deletedReplayReports, deletedAliases, cutoff);
            }
        } catch (Exception e) {
            log.warn("主动提醒样本治理异常: {}", e.getMessage());
        }
    }
}
