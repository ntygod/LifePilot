package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReminderRetentionScheduler 单元测试。
 *
 * <p>验证主动提醒样本治理能够按保留期清理运行数据和别名映射。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
class ReminderRetentionScheduler_单元测试 {

    private ScheduledExecutorService scheduler;
    private ReminderExecutionRepository executionRepository;
    private ReminderFeedbackRepository feedbackRepository;
    private ReminderReplayReportRepository replayReportRepository;
    private ReminderTopicAliasRepository topicAliasRepository;
    private AgentConfigProperties config;
    private ReminderRetentionScheduler retentionScheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executionRepository = mock(ReminderExecutionRepository.class);
        feedbackRepository = mock(ReminderFeedbackRepository.class);
        replayReportRepository = mock(ReminderReplayReportRepository.class);
        topicAliasRepository = mock(ReminderTopicAliasRepository.class);
        config = new AgentConfigProperties();
        config.getTask().setProactiveReminderRetentionDays(90);
        retentionScheduler = new ReminderRetentionScheduler(
                scheduler,
                executionRepository,
                feedbackRepository,
                replayReportRepository,
                topicAliasRepository,
                config
        );
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void cleanup_启用时执行样本治理() {
        when(executionRepository.deleteRunsBefore(any())).thenReturn(2);
        when(feedbackRepository.deleteFeedbackBefore(any())).thenReturn(1);
        when(replayReportRepository.deleteBefore(any())).thenReturn(1);
        when(topicAliasRepository.deleteStaleAliasesBefore(any())).thenReturn(3);

        retentionScheduler.cleanup();

        verify(executionRepository, times(1)).deleteRunsBefore(any(Instant.class));
        verify(feedbackRepository, times(1)).deleteFeedbackBefore(any(Instant.class));
        verify(replayReportRepository, times(1)).deleteBefore(any(Instant.class));
        verify(topicAliasRepository, times(1)).deleteStaleAliasesBefore(any(Instant.class));
    }

    @Test
    void cleanup_主动提醒关闭时跳过执行() {
        config.getTask().setProactiveReminderEnabled(false);

        retentionScheduler.cleanup();

        verify(executionRepository, never()).deleteRunsBefore(any());
        verify(feedbackRepository, never()).deleteFeedbackBefore(any());
        verify(replayReportRepository, never()).deleteBefore(any());
        verify(topicAliasRepository, never()).deleteStaleAliasesBefore(any());
    }
}
