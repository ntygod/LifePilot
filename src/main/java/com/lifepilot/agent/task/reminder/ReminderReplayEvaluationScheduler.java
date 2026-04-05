package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动提醒离线回放评估调度器。
 *
 * <p>周期性生成系统内部 replay 评估报告，供策略调参与稳定性分析使用。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ReminderReplayEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderReplayEvaluationScheduler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScheduledExecutorService scheduler;
    private final ReminderExecutionRepository executionRepository;
    private final ReminderReplayReportRepository replayReportRepository;
    private final ReminderReplayService replayService;
    private final NotificationProperties notificationProperties;
    private final AgentConfigProperties config;

    public ReminderReplayEvaluationScheduler(ScheduledExecutorService scheduler,
                                             ReminderExecutionRepository executionRepository,
                                             ReminderReplayReportRepository replayReportRepository,
                                             ReminderReplayService replayService,
                                             NotificationProperties notificationProperties,
                                             AgentConfigProperties config) {
        this.scheduler = scheduler;
        this.executionRepository = executionRepository;
        this.replayReportRepository = replayReportRepository;
        this.replayService = replayService;
        this.notificationProperties = notificationProperties;
        this.config = config;
    }

    /**
     * 启动定时离线回放评估。
     */
    public void start() {
        if (!config.getTask().isProactiveReminderReplayEvaluationEnabled()) {
            return;
        }
        long intervalMs = Math.max(60L, config.getTask().getProactiveReminderReplayEvaluationIntervalSeconds()) * 1000L;
        long initialDelayMs = Math.min(15000L, intervalMs);
        scheduler.scheduleAtFixedRate(this::scan, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("主动提醒离线回放评估调度器已启动: interval={}s, userBatchSize={}",
                config.getTask().getProactiveReminderReplayEvaluationIntervalSeconds(),
                config.getTask().getProactiveReminderReplayEvaluationUserBatchSize());
    }

    /**
     * 扫描活跃用户并生成内部 replay 报告。
     */
    public void scan() {
        if (!config.getTask().isProactiveReminderEnabled()
                || !config.getTask().isProactiveReminderReplayEvaluationEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant since = now.minusSeconds(Math.max(1, config.getTask().getProactiveReminderBanditLookbackDays()) * 24L * 3600L);
        int minSamples = Math.max(1, config.getTask().getProactiveReminderReplayMinSamples());
        int maxUsers = Math.max(1, config.getTask().getProactiveReminderReplayEvaluationUserBatchSize());
        int maxExamples = Math.max(1, config.getTask().getProactiveReminderBanditMaxExamples());

        try {
            List<String> userIds = resolveUserIds(since, maxUsers);
            if (userIds.isEmpty()) {
                return;
            }
            for (String userId : userIds) {
                ReminderReplayReport report = replayService.replay(userId, since, maxExamples);
                if (report.sampleCount() < minSamples) {
                    continue;
                }
                replayReportRepository.save(toRecord(report, now));
                log.debug("主动提醒离线回放评估已保存: userId={}, samples={}, delta={}",
                        userId,
                        report.sampleCount(),
                        report.replayedEstimatedPushRewardMean() - report.historicalEstimatedPushRewardMean());
            }
        } catch (Exception e) {
            log.warn("主动提醒离线回放评估异常: {}", e.getMessage());
        }
    }

    private List<String> resolveUserIds(Instant since, int maxUsers) {
        Set<String> userIds = new LinkedHashSet<>(executionRepository.findRecentActiveUserIdsSince(since, maxUsers));
        String defaultUserId = notificationProperties.getDefaultUserId();
        if ((userIds.isEmpty() || userIds.size() < maxUsers)
                && defaultUserId != null
                && !defaultUserId.isBlank()) {
            userIds.add(defaultUserId);
        }
        return userIds.stream().limit(maxUsers).toList();
    }

    private ReminderReplayReportRecord toRecord(ReminderReplayReport report, Instant now) {
        float expectedDelta = report.replayedEstimatedPushRewardMean() - report.historicalEstimatedPushRewardMean();
        return new ReminderReplayReportRecord(
                UUID.randomUUID().toString(),
                report.userId(),
                report.since(),
                now,
                report.sampleCount(),
                report.historicalPushCount(),
                report.replayedPushCount(),
                report.suppressedCount(),
                report.promotedCount(),
                report.actionShiftCount(),
                report.historicalObservedRewardMean(),
                report.historicalEstimatedPushRewardMean(),
                report.replayedEstimatedPushRewardMean(),
                writeJson(report.actionShiftMatrix()),
                writeJson(Map.of(
                        "expectedDelta", expectedDelta,
                        "historicalObservedRewardMean", report.historicalObservedRewardMean(),
                        "historicalEstimatedPushRewardMean", report.historicalEstimatedPushRewardMean(),
                        "replayedEstimatedPushRewardMean", report.replayedEstimatedPushRewardMean(),
                        "sampleCount", report.sampleCount()
                )),
                now
        );
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value != null ? value : Map.of());
        } catch (Exception e) {
            log.debug("主动提醒离线回放评估序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
