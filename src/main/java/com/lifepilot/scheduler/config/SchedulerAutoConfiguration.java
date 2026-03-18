package com.lifepilot.scheduler.config;

import com.lifepilot.config.threadpool.ThreadPoolRegistry;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.scheduler.ScheduledTaskRepository;
import com.lifepilot.scheduler.ScheduledTaskService;
import com.lifepilot.scheduler.ScheduledTaskSkillProvider;
import com.lifepilot.scheduler.TaskActionExecutor;
import com.lifepilot.scheduler.TaskScheduler;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 调度器模块自动配置。
 *
 * <p>注册调度器模块所有核心 Bean：ScheduledTaskRepository、TaskActionExecutor、
 * TaskScheduler、ScheduledTaskService。
 * 通过 {@code lifepilot.scheduler.enabled} 控制总开关，默认启用。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
@AutoConfiguration
@EnableConfigurationProperties(SchedulerProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SchedulerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulerAutoConfiguration.class);

    private final SchedulerProperties properties;
    private final ApplicationContext applicationContext;

    public SchedulerAutoConfiguration(SchedulerProperties properties,
                                      ApplicationContext applicationContext) {
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledTaskRepository scheduledTaskRepository(JdbcTemplate jdbcTemplate) {
        log.info("调度器模块: 注册 ScheduledTaskRepository");
        return new ScheduledTaskRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskActionExecutor taskActionExecutor(NotificationService notificationService,
                                                  DynamicToolRegistry dynamicToolRegistry,
                                                  ApplicationContext appContext) {
        log.info("调度器模块: 注册 TaskActionExecutor");
        return new TaskActionExecutor(notificationService, dynamicToolRegistry, appContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskScheduler taskScheduler(TaskActionExecutor taskActionExecutor,
                                       ScheduledTaskRepository scheduledTaskRepository,
                                       SchedulerProperties schedulerProperties,
                                       ThreadPoolRegistry threadPoolRegistry) {
        log.info("调度器模块: 注册 TaskScheduler");
        return new TaskScheduler(taskActionExecutor, scheduledTaskRepository, schedulerProperties, threadPoolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledTaskService scheduledTaskService(ScheduledTaskRepository scheduledTaskRepository,
                                                      TaskScheduler taskScheduler) {
        log.info("调度器模块: 注册 ScheduledTaskService");
        return new ScheduledTaskService(scheduledTaskRepository, taskScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledTaskSkillProvider scheduledTaskSkillProvider(ScheduledTaskService scheduledTaskService,
                                                                  PromptRegistry promptRegistry) {
        log.info("调度器模块: 注册 ScheduledTaskSkillProvider");
        return new ScheduledTaskSkillProvider(scheduledTaskService, promptRegistry);
    }

    /**
     * 应用启动完成后，若配置了 recoveryOnStartup=true，自动恢复所有 PENDING 任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isRecoveryOnStartup()) {
            log.info("调度器模块: 应用启动完成，开始恢复 PENDING 任务");
            applicationContext.getBean(TaskScheduler.class).recoverAll();
        }
    }
}
