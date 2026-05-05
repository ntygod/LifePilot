package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReminderReplayEvaluationScheduler 单元测试。
 *
 * <p>验证系统能够周期性生成内部 replay 报告，并在样本不足时跳过持久化。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
class ReminderReplayEvaluationScheduler_单元测试 {

    private ScheduledExecutorService scheduler;
    private ReminderExecutionRepository executionRepository;
    private ReminderReplayReportRepository replayReportRepository;
    private ReminderReplayService replayService;
    private NotificationProperties notificationProperties;
    private AgentConfigProperties config;
    private ReminderReplayEvaluationScheduler evaluationScheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executionRepository = mock(ReminderExecutionRepository.class);
        replayReportRepository = mock(ReminderReplayReportRepository.class);
        replayService = mock(ReminderReplayService.class);
        notificationProperties = new NotificationProperties();
        notificationProperties.setDefaultUserId("default");
        config = new AgentConfigProperties();
        config.getTask().setProactiveReminderEnabled(true);
        config.getTask().setProactiveReminderReplayEvaluationUserBatchSize(4);
        evaluationScheduler = new ReminderReplayEvaluationScheduler(
                scheduler,
                executionRepository,
                replayReportRepository,
                replayService,
                notificationProperties,
                config
        );
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void scan_存在足够样本_保存回放报告() {
        Instant since = Instant.now().minusSeconds(30L * 24 * 3600);
        when(executionRepository.findRecentActiveUserIdsSince(any(), anyInt()))
                .thenReturn(List.of("default"));
        when(replayService.replay(eq("default"), any(), eq(config.getTask().getProactiveReminderBanditMaxExamples())))
                .thenReturn(new ReminderReplayReport(
                        "default",
                        since,
                        18,
                        12,
                        10,
                        3,
                        1,
                        4,
                        0.61f,
                        0.60f,
                        0.66f,
                        java.util.Map.of("NORMAL_PUSH->SOFT_PUSH", 2),
                        List.of()
                ));

        evaluationScheduler.scan();

        verify(replayReportRepository, times(1)).save(any(ReminderReplayReportRecord.class));
    }

    @Test
    void scan_样本不足_跳过落库() {
        Instant since = Instant.now().minusSeconds(30L * 24 * 3600);
        when(executionRepository.findRecentActiveUserIdsSince(any(), anyInt()))
                .thenReturn(List.of("default"));
        when(replayService.replay(eq("default"), any(), eq(config.getTask().getProactiveReminderBanditMaxExamples())))
                .thenReturn(new ReminderReplayReport(
                        "default",
                        since,
                        6,
                        4,
                        3,
                        1,
                        0,
                        1,
                        0.58f,
                        0.57f,
                        0.59f,
                        java.util.Map.of(),
                        List.of()
                ));

        evaluationScheduler.scan();

        verify(replayReportRepository, never()).save(any(ReminderReplayReportRecord.class));
    }
}
