package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.agent.task.HeartbeatRunner;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * TaskAutoConfiguration 集成测试 — 验证 Bean 注册和构造函数注入。
 *
 * <p>轻量级测试，手动模拟 Bean 注册流程，验证 TaskAutoConfiguration
 * 注册的所有 Bean 能正确构造且依赖注入链完整。</p>
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
        var config = new AgentConfigProperties();
        var sharedScheduler = mock(SharedScheduler.class);
        var scheduler = Executors.newScheduledThreadPool(1);
        org.mockito.Mockito.when(sharedScheduler.heartbeat()).thenReturn(scheduler);

        var autoConfig = new TaskAutoConfiguration();

        // 验证 CronTaskRepository Bean
        CronTaskRepository repository = autoConfig.cronTaskRepository(jdbcTemplate);
        assertThat(repository).isNotNull();

        // 验证 CronScheduler Bean
        CronScheduler cronScheduler = autoConfig.cronScheduler(
                sharedScheduler, repository, agentOrchestrator, notificationService, config);
        assertThat(cronScheduler).isNotNull();

        // 验证 HeartbeatRunner Bean
        HeartbeatRunner heartbeatRunner = autoConfig.heartbeatRunner(
                sharedScheduler, agentOrchestrator, notificationService, config);
        assertThat(heartbeatRunner).isNotNull();

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
