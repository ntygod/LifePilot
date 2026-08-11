package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自主任务执行 Spring Boot 自动配置。
 *
 * <p>注册 Cron 定时调度相关 Bean，在 {@link ApplicationReadyEvent} 时恢复所有 active 定时任务。</p>
 *
 * <p>心跳唤醒与旧主动提醒引擎（ProactiveEngine / Reminder 决策栈）已移除；
 * 主动性统一由事件驱动的 InitiativeEngine 承担，不再定时轮询。
 * 用户显式设置的提醒仍走此处的 Cron 调度。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(AgentOrchestrator.class)
public class TaskAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public CronTaskRepository cronTaskRepository(JdbcTemplate jdbcTemplate) {
        return new CronTaskRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public CronScheduler cronScheduler(SharedScheduler sharedScheduler,
                                       CronTaskRepository cronTaskRepository,
                                       AgentOrchestrator agentOrchestrator,
                                       @Autowired(required = false) NotificationService notificationService,
                                       @Autowired(required = false) NotificationProperties notificationProperties) {
        return new CronScheduler(
                sharedScheduler.heartbeat(),
                cronTaskRepository,
                agentOrchestrator,
                notificationService,
                notificationProperties != null ? notificationProperties : new NotificationProperties()
        );
    }

    /**
     * 应用就绪后恢复 Cron 定时任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var context = event.getApplicationContext();

        // 恢复所有 active cron 任务
        if (context.containsBean("cronScheduler")) {
            var cronScheduler = context.getBean(CronScheduler.class);
            cronScheduler.restoreAll();
            log.info("自主任务系统就绪: Cron 定时任务已恢复");
        }
    }
}
