package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.agent.task.HeartbeatRunner;
import com.lifepilot.agent.task.reminder.DefaultReminderSignalCollector;
import com.lifepilot.agent.task.reminder.DefaultReminderMessageGenerator;
import com.lifepilot.agent.task.reminder.ProactiveReminderService;
import com.lifepilot.agent.task.reminder.ReminderDecisionEngine;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderReplayReportRepository;
import com.lifepilot.agent.task.reminder.ReminderReplayEvaluationScheduler;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderOutcomeInferenceService;
import com.lifepilot.agent.task.reminder.ReminderOutcomeRepository;
import com.lifepilot.agent.task.reminder.ReminderPolicyTuner;
import com.lifepilot.agent.task.reminder.ReminderPolicyVersionRepository;
import com.lifepilot.agent.task.reminder.ReminderPolicyVersionService;
import com.lifepilot.agent.task.reminder.ReminderReplayService;
import com.lifepilot.agent.task.reminder.ReminderActionPolicySelector;
import com.lifepilot.agent.task.reminder.ReminderOpportunityPolicySelector;
import com.lifepilot.agent.task.reminder.ReminderMessageGenerator;
import com.lifepilot.agent.task.reminder.ReminderSignalCollector;
import com.lifepilot.agent.task.reminder.ReminderRetentionScheduler;
import com.lifepilot.agent.task.reminder.ReminderTopicAliasRepository;
import com.lifepilot.agent.task.reminder.ReminderWakeupScheduler;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.workflow.repository.WorkflowRepository;
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
 * <p>注册 Cron 定时调度、主动提醒与心跳唤醒相关 Bean。
 * 在 {@link ApplicationReadyEvent} 时恢复所有 active 定时任务并启动心跳唤醒。</p>
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
    public ReminderExecutionRepository reminderExecutionRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderExecutionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderFeedbackRepository reminderFeedbackRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderFeedbackRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderReplayReportRepository reminderReplayReportRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderReplayReportRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderOutcomeRepository reminderOutcomeRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderOutcomeRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderTopicAliasRepository reminderTopicAliasRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderTopicAliasRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderPolicyVersionRepository reminderPolicyVersionRepository(JdbcTemplate jdbcTemplate) {
        return new ReminderPolicyVersionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderPolicyVersionService reminderPolicyVersionService(
            ReminderPolicyVersionRepository reminderPolicyVersionRepository) {
        return new ReminderPolicyVersionService(reminderPolicyVersionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderSignalCollector reminderSignalCollector(
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) ProceduralMemory proceduralMemory,
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) SessionWorkspaceService sessionWorkspaceService,
            NotificationRepository notificationRepository,
            @Autowired(required = false) ReminderFeedbackRepository reminderFeedbackRepository,
            @Autowired(required = false) ReminderOutcomeRepository reminderOutcomeRepository,
            @Autowired(required = false) ReminderTopicAliasRepository reminderTopicAliasRepository) {
        return new DefaultReminderSignalCollector(
                semanticMemory,
                proceduralMemory,
                episodicMemory,
                sessionWorkspaceService,
                notificationRepository,
                reminderFeedbackRepository,
                reminderOutcomeRepository,
                reminderTopicAliasRepository
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderDecisionEngine reminderDecisionEngine() {
        return new ReminderDecisionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderMessageGenerator reminderMessageGenerator(
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) PromptRegistry promptRegistry,
            AgentConfigProperties config) {
        return new DefaultReminderMessageGenerator(generationRouter, promptRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderPolicyTuner reminderPolicyTuner() {
        return new ReminderPolicyTuner();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderActionPolicySelector reminderActionPolicySelector(AgentConfigProperties config) {
        return new ReminderActionPolicySelector(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderOpportunityPolicySelector reminderOpportunityPolicySelector(AgentConfigProperties config) {
        return new ReminderOpportunityPolicySelector(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderOutcomeInferenceService reminderOutcomeInferenceService(
            @Autowired(required = false) ReminderExecutionRepository reminderExecutionRepository,
            @Autowired(required = false) ReminderOutcomeRepository reminderOutcomeRepository,
            @Autowired(required = false) SessionWorkspaceService sessionWorkspaceService,
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) WorkflowRepository workflowRepository,
            @Autowired(required = false) TraceQuery traceQuery,
            AgentConfigProperties config) {
        return new ReminderOutcomeInferenceService(
                reminderExecutionRepository,
                reminderOutcomeRepository,
                sessionWorkspaceService,
                semanticMemory,
                episodicMemory,
                workflowRepository,
                traceQuery,
                config
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderReplayService reminderReplayService(ReminderExecutionRepository reminderExecutionRepository,
                                                       ReminderOpportunityPolicySelector reminderOpportunityPolicySelector,
                                                       ReminderActionPolicySelector reminderActionPolicySelector,
                                                       AgentConfigProperties config) {
        return new ReminderReplayService(
                reminderExecutionRepository,
                reminderOpportunityPolicySelector,
                reminderActionPolicySelector,
                null,
                config
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ProactiveReminderService proactiveReminderService(ReminderSignalCollector reminderSignalCollector,
                                                             ReminderDecisionEngine reminderDecisionEngine,
                                                             ReminderPolicyTuner reminderPolicyTuner,
                                                             ReminderOpportunityPolicySelector reminderOpportunityPolicySelector,
                                                             ReminderActionPolicySelector reminderActionPolicySelector,
                                                             ReminderMessageGenerator reminderMessageGenerator,
                                                             NotificationService notificationService,
                                                             NotificationRepository notificationRepository,
                                                             ReminderExecutionRepository reminderExecutionRepository,
                                                             @Autowired(required = false) ReminderFeedbackRepository reminderFeedbackRepository,
                                                             @Autowired(required = false) ReminderOutcomeInferenceService reminderOutcomeInferenceService,
                                                             @Autowired(required = false) ReminderReplayService reminderReplayService,
                                                             @Autowired(required = false) ReminderPolicyVersionService reminderPolicyVersionService,
                                                             AgentConfigProperties config,
                                                             NotificationProperties notificationProperties) {
        return new ProactiveReminderService(
                reminderSignalCollector,
                reminderDecisionEngine,
                notificationService,
                notificationRepository,
                reminderExecutionRepository,
                reminderFeedbackRepository,
                reminderPolicyTuner,
                reminderOpportunityPolicySelector,
                reminderActionPolicySelector,
                reminderMessageGenerator,
                reminderOutcomeInferenceService,
                reminderReplayService,
                reminderPolicyVersionService,
                config,
                notificationProperties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "proactive-reminder-enabled",
            havingValue = "true", matchIfMissing = true)
    public ReminderWakeupScheduler reminderWakeupScheduler(SharedScheduler sharedScheduler,
                                                           ReminderExecutionRepository reminderExecutionRepository,
                                                           ProactiveReminderService proactiveReminderService,
                                                           AgentConfigProperties config) {
        return new ReminderWakeupScheduler(
                sharedScheduler.heartbeat(),
                reminderExecutionRepository,
                proactiveReminderService,
                config
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "proactive-reminder-replay-evaluation-enabled",
            havingValue = "true", matchIfMissing = true)
    public ReminderReplayEvaluationScheduler reminderReplayEvaluationScheduler(
            SharedScheduler sharedScheduler,
            ReminderExecutionRepository reminderExecutionRepository,
            ReminderReplayReportRepository reminderReplayReportRepository,
            ReminderReplayService reminderReplayService,
            NotificationProperties notificationProperties,
            AgentConfigProperties config) {
        return new ReminderReplayEvaluationScheduler(
                sharedScheduler.heartbeat(),
                reminderExecutionRepository,
                reminderReplayReportRepository,
                reminderReplayService,
                notificationProperties,
                config
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "proactive-reminder-enabled",
            havingValue = "true", matchIfMissing = true)
    public ReminderRetentionScheduler reminderRetentionScheduler(
            SharedScheduler sharedScheduler,
            ReminderExecutionRepository reminderExecutionRepository,
            ReminderFeedbackRepository reminderFeedbackRepository,
            ReminderReplayReportRepository reminderReplayReportRepository,
            ReminderTopicAliasRepository reminderTopicAliasRepository,
            AgentConfigProperties config) {
        return new ReminderRetentionScheduler(
                sharedScheduler.cleanup(),
                reminderExecutionRepository,
                reminderFeedbackRepository,
                reminderReplayReportRepository,
                reminderTopicAliasRepository,
                config
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "heartbeat-enabled",
            havingValue = "true", matchIfMissing = true)
    public HeartbeatRunner heartbeatRunner(SharedScheduler sharedScheduler,
                                           AgentConfigProperties config,
                                           @Autowired(required = false) ProactiveReminderService proactiveReminderService) {
        return new HeartbeatRunner(
                sharedScheduler.heartbeat(),
                config,
                proactiveReminderService
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
