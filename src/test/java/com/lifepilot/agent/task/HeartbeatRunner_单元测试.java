package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.DetectionLevel;
import com.lifepilot.agent.task.proactive.ProactiveEngine;
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
 * @author zsg
 * @since 2026-03-20
 */
class HeartbeatRunner_单元测试 {

    private HeartbeatRunner heartbeatRunner;
    private ProactiveEngine proactiveEngine;
    private AgentConfigProperties config;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        proactiveEngine = mock(ProactiveEngine.class);
        scheduler = Executors.newScheduledThreadPool(1);
        config = new AgentConfigProperties();
    }

    @Test
    void isWithinActiveHours_未配置_全天活跃() {
        config.getTask().setActiveHoursStart(null);
        config.getTask().setActiveHoursEnd(null);
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveEngine);

        assertThat(heartbeatRunner.isWithinActiveHours()).isTrue();
    }

    @Test
    void beat_引擎启用_调用心跳() {
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveEngine);
        when(proactiveEngine.heartbeat()).thenReturn(DetectionLevel.FAST);

        heartbeatRunner.beat();

        verify(proactiveEngine, times(1)).heartbeat();
    }

    @Test
    void beat_引擎关闭_不调用心跳() {
        config.getTask().setProactiveReminderEnabled(false);
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveEngine);

        heartbeatRunner.beat();

        verify(proactiveEngine, never()).heartbeat();
    }

    @Test
    void beat_引擎为null_不崩溃() {
        heartbeatRunner = new HeartbeatRunner(scheduler, config, null);

        assertThatCode(() -> heartbeatRunner.beat()).doesNotThrowAnyException();
    }

    @Test
    void beat_不在活跃时段_跳过() {
        LocalTime now = LocalTime.now();
        config.getTask().setActiveHoursStart(now.plusMinutes(2).withSecond(0).withNano(0).toString());
        config.getTask().setActiveHoursEnd(now.plusMinutes(3).withSecond(0).withNano(0).toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveEngine);

        heartbeatRunner.beat();

        verify(proactiveEngine, never()).heartbeat();
    }

    @Test
    void beat_引擎异常_不抛出() {
        heartbeatRunner = new HeartbeatRunner(scheduler, config, proactiveEngine);
        when(proactiveEngine.heartbeat()).thenThrow(new RuntimeException("模拟异常"));

        assertThatCode(() -> heartbeatRunner.beat()).doesNotThrowAnyException();
    }
}
