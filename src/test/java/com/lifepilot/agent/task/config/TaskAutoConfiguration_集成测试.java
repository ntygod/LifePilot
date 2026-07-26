package com.lifepilot.agent.task.config;

import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TaskAutoConfiguration 集成测试 — 验证 Bean 注册和构造函数注入。
 *
 * <p>轻量级测试，手动模拟 Bean 注册流程，验证 TaskAutoConfiguration
 * 注册的 Cron 相关 Bean 能正确构造且依赖注入链完整。</p>
 *
 * <p>旧的心跳唤醒与提醒决策栈已移除，故不再覆盖其 Bean 构造。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class TaskAutoConfiguration_集成测试 {

    @Test
    void 所有Bean能正确构造_依赖注入链完整() {
        // 模拟依赖
        var jdbcTemplate = mock(JdbcTemplate.class);
        var agentOrchestrator = mock(AgentOrchestrator.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = new NotificationProperties();
        var sharedScheduler = mock(SharedScheduler.class);
        var scheduler = Executors.newScheduledThreadPool(1);
        when(sharedScheduler.heartbeat()).thenReturn(scheduler);

        var taskConfig = new TaskAutoConfiguration();

        CronTaskRepository repository = taskConfig.cronTaskRepository(jdbcTemplate);
        assertThat(repository).isNotNull();

        CronScheduler cronScheduler = taskConfig.cronScheduler(
                sharedScheduler, repository, agentOrchestrator, notificationService, notificationProperties);
        assertThat(cronScheduler).isNotNull();

        scheduler.shutdownNow();
    }

    @Test
    void CronTaskRepository_使用JdbcTemplate构造() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var autoConfig = new TaskAutoConfiguration();

        var repository = autoConfig.cronTaskRepository(jdbcTemplate);
        assertThat(repository).isInstanceOf(CronTaskRepository.class);
    }
}
