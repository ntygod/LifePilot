package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ProactiveReminderRunResult;
import com.lifepilot.agent.task.reminder.ProactiveReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * HeartbeatRunner 单元测试。
 *
 * <p>验证心跳唤醒逻辑、活跃时段判断和主动提醒服务调用。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class HeartbeatRunner_单元测试 {

    private HeartbeatRunner heartbeatRunner;
    private ProactiveReminderService proactiveReminderService;
    private AgentConfigProperties config;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        proactiveReminderService = mock(ProactiveReminderService.class);
        scheduler = Executors.newScheduledThreadPool(1);
        config = new AgentConfigProperties();
    }

    @Test
    void isWithinActiveHours_未配置_全天活跃() {
        config.getTask().setActiveHoursStart(null);
        config.getTask().setActiveHoursEnd(null);
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveReminderService);

        assertThat(heartbeatRunner.isWithinActiveHours()).isTrue();
    }

    @Test
    void beat_主动提醒启用_调用主动提醒服务() {
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveReminderService);
        when(proactiveReminderService.runOnce()).thenReturn(new ProactiveReminderRunResult(3, 2, 1));

        heartbeatRunner.beat();

        verify(proactiveReminderService, times(1)).runOnce();
    }

    @Test
    void beat_主动提醒关闭_不调用主动提醒服务() {
        config.getTask().setProactiveReminderEnabled(false);
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveReminderService);

        heartbeatRunner.beat();

        verify(proactiveReminderService, never()).runOnce();
    }

    @Test
    void beat_不在活跃时段_跳过主动提醒() {
        LocalTime now = LocalTime.now();
        config.getTask().setActiveHoursStart(now.plusMinutes(2).withSecond(0).withNano(0).toString());
        config.getTask().setActiveHoursEnd(now.plusMinutes(3).withSecond(0).withNano(0).toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveReminderService);

        heartbeatRunner.beat();

        verify(proactiveReminderService, never()).runOnce();
    }

    @Test
    void beat_主动提醒异常_不抛出() {
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveReminderService);
        when(proactiveReminderService.runOnce()).thenThrow(new RuntimeException("主动提醒异常"));

        assertThatCode(() -> heartbeatRunner.beat()).doesNotThrowAnyException();
    }
}
