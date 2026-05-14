package com.lifepilot.agent.task.proactive.training;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProactiveTrainingScheduler 单元测试 — 仅覆盖 run() / 开关判断；
 * 不测试 scheduler 本身的真实定时行为（避免拖慢测试）。
 *
 * @author zsg
 * @since 2026-05-09
 */
class ProactiveTrainingScheduler_单元测试 {

    @Test
    void 开关关闭时start直接返回不调度() {
        var replayService = mock(ProactiveTrainingReplayService.class);
        var library = mock(ProactiveFewShotLibrary.class);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTrainingEnabled(false);
        var np = new NotificationProperties();
        var scheduler = mock(ScheduledExecutorService.class);

        var trainer = new ProactiveTrainingScheduler(scheduler, replayService, library, cfg, np);
        trainer.start();

        verify(scheduler, never()).scheduleAtFixedRate(any(), anyInt(), anyInt(), any());
    }

    @Test
    void run触发replayService和library保存(@TempDir Path tempDir) {
        var replayService = mock(ProactiveTrainingReplayService.class);
        var library = new ProactiveFewShotLibrary(tempDir);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTrainingEnabled(true);
        cfg.setProactiveTrainingReplayIntervalDays(7);
        var np = new NotificationProperties();
        np.setDefaultUserId("test-user");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var samples = List.of(new ProactiveFewShotSample(
                    "DUE_SOON", "A", com.lifepilot.agent.task.reminder.ReminderAction.NORMAL_PUSH,
                    0.9f, "", Instant.now(), true));
            when(replayService.buildFewShotSamples(anyString(), any(Instant.class), anyInt()))
                    .thenReturn(samples);

            var trainer = new ProactiveTrainingScheduler(scheduler, replayService, library, cfg, np);
            trainer.run();

            verify(replayService, times(1)).buildFewShotSamples(any(String.class), any(Instant.class), anyInt());
            assertThat(library.getSamples("test-user", null, 10)).hasSize(1);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void 无defaultUserId时run跳过() {
        var replayService = mock(ProactiveTrainingReplayService.class);
        var library = mock(ProactiveFewShotLibrary.class);
        var cfg = new AgentConfigProperties().getTask();
        var np = new NotificationProperties();
        np.setDefaultUserId("");
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var trainer = new ProactiveTrainingScheduler(scheduler, replayService, library, cfg, np);
            trainer.run();

            verify(replayService, never()).buildFewShotSamples(anyString(), any(Instant.class), anyInt());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void 空notificationProperties时run跳过() {
        var replayService = mock(ProactiveTrainingReplayService.class);
        var library = mock(ProactiveFewShotLibrary.class);
        var cfg = new AgentConfigProperties().getTask();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            var trainer = new ProactiveTrainingScheduler(scheduler, replayService, library, cfg, null);
            trainer.run();

            verify(replayService, never()).buildFewShotSamples(anyString(), any(Instant.class), anyInt());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
