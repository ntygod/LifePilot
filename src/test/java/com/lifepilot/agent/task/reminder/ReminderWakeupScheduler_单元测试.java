package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReminderWakeupScheduler 单元测试。
 *
 * <p>验证延后提醒调度器能够扫描到期主题并触发重评估。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderWakeupScheduler_单元测试 {

    private ScheduledExecutorService scheduler;
    private ReminderExecutionRepository executionRepository;
    private ProactiveReminderService proactiveReminderService;
    private AgentConfigProperties config;
    private ReminderWakeupScheduler wakeupScheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executionRepository = mock(ReminderExecutionRepository.class);
        proactiveReminderService = mock(ProactiveReminderService.class);
        config = new AgentConfigProperties();
        config.getTask().setProactiveReminderWakeupBatchSize(10);
        wakeupScheduler = new ReminderWakeupScheduler(
                scheduler,
                executionRepository,
                proactiveReminderService,
                config
        );
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void scan_存在到期主题_触发重评估() {
        when(executionRepository.findDueDeferredWakeups(any(), anyInt()))
                .thenReturn(List.of(new ReminderDeferredWakeup(
                        "decision-1",
                        "run-1",
                        "default",
                        "habit:plan",
                        "周计划整理",
                        "sig-1",
                        "HABIT_WINDOW",
                        Instant.now().minusSeconds(30)
                )));
        when(proactiveReminderService.runDeferredWakeups(any()))
                .thenReturn(new ProactiveReminderRunResult(1, 1, 1));

        wakeupScheduler.scan();

        verify(proactiveReminderService, times(1)).runDeferredWakeups(any());
    }

    @Test
    void scan_未启用主动提醒_跳过() {
        config.getTask().setProactiveReminderEnabled(false);

        wakeupScheduler.scan();

        verify(executionRepository, never()).findDueDeferredWakeups(any(), anyInt());
        verify(proactiveReminderService, never()).runDeferredWakeups(any());
    }
}
