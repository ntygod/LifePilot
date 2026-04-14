package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.agent.task.HeartbeatRunner;
import com.lifepilot.agent.task.reminder.ProactiveReminderService;
import com.lifepilot.agent.task.reminder.ReminderReplayEvaluationScheduler;
import com.lifepilot.agent.task.reminder.ReminderRetentionScheduler;
import com.lifepilot.agent.task.reminder.ReminderWakeupScheduler;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自主任务执行 Spring Boot 自动配置。
 *
 * <p>注册 Cron 定时调度与心跳唤醒相关 Bean。
 * 主动提醒引擎的 Bean 注册委托给 {@link ReminderAutoConfiguration}。
 * 在 {@link ApplicationReadyEvent} 时恢复所有 active 定时任务并启动心跳唤醒。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(AgentOrchestrator.class)
@Import(ReminderAutoConfiguration.class)
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
                                       NotificationService notificationService,
                                       NotificationProperties notificationProperties) {
        return new CronScheduler(
                sharedScheduler.heartbeat(),
                cronTaskRepository,
                agentOrchestrator,
                notificationService,
                notificationProperties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "heartbeat-enabled",
            havingValue = "true", matchIfMissing = true)
    public HeartbeatRunner heartbeatRunner(SharedScheduler sharedScheduler,
                                           AgentConfigProperties config,
                                           @Autowired(required = false) ProactiveReminderService proactiveReminderService,
                                           @Autowired(required = false) com.lifepilot.agent.task.proactive.ProactiveEngine proactiveEngine) {
        return new HeartbeatRunner(
                sharedScheduler.heartbeat(),
                config,
                proactiveReminderService,
                proactiveEngine
        );
    }

    /**
     * 应用就绪后恢复 Cron 定时任务并启动心跳唤醒。
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

        // 启动心跳唤醒
        if (context.containsBean("heartbeatRunner")) {
            var heartbeatRunner = context.getBean(HeartbeatRunner.class);
            heartbeatRunner.start();
            log.info("自主任务系统就绪: 心跳唤醒已启动");
        }

        if (context.containsBean("reminderWakeupScheduler")) {
            var reminderWakeupScheduler = context.getBean(ReminderWakeupScheduler.class);
            reminderWakeupScheduler.start();
            log.info("自主任务系统就绪: 主动提醒延后唤醒已启动");
        }

        if (context.containsBean("reminderReplayEvaluationScheduler")) {
            var reminderReplayEvaluationScheduler = context.getBean(ReminderReplayEvaluationScheduler.class);
            reminderReplayEvaluationScheduler.start();
            log.info("自主任务系统就绪: 主动提醒离线回放评估已启动");
        }

        if (context.containsBean("reminderRetentionScheduler")) {
            var reminderRetentionScheduler = context.getBean(ReminderRetentionScheduler.class);
            reminderRetentionScheduler.start();
            log.info("自主任务系统就绪: 主动提醒样本治理已启动");
        }
    }
}
