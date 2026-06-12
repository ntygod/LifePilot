package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.task.reminder.DefaultReminderSignalCollector;
import com.lifepilot.agent.task.reminder.DefaultReminderMessageGenerator;
import com.lifepilot.agent.task.reminder.ReminderBehavior;
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
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.agent.task.reminder.ReminderSignalCollector;
import com.lifepilot.agent.task.reminder.ReminderSituationSynthesizer;
import com.lifepilot.agent.task.reminder.ReminderRetentionScheduler;
import com.lifepilot.agent.task.reminder.ReminderTopicAliasRepository;
import com.lifepilot.agent.task.reminder.ReminderTrustGradient;
import com.lifepilot.agent.task.reminder.WeatherSignalSource;
import com.lifepilot.agent.context.LocationResolver;
import com.lifepilot.agent.context.OpenMeteoWeatherService;
import com.lifepilot.agent.context.WeatherService;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.workspace.SessionWorkspaceService;
import com.lifepilot.notification.NotificationRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/**
 * 主动提醒引擎 Spring Boot 自动配置。
 *
 * <p>注册主动提醒相关的仓储、采集器、决策引擎、策略选择器、
 * 调度器等 Bean，与 {@link TaskAutoConfiguration} 分离以保持各自内聚。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
@AutoConfiguration(after = TaskAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(AgentOrchestrator.class)
public class ReminderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReminderAutoConfiguration.class);

    // ===== 仓储层 =====

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

    // ===== 服务层 =====

    @Bean
    @ConditionalOnMissingBean
    public ReminderPolicyVersionService reminderPolicyVersionService(
            ReminderPolicyVersionRepository reminderPolicyVersionRepository) {
        return new ReminderPolicyVersionService(reminderPolicyVersionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocationResolver locationResolver(
            @Autowired(required = false) AgentConfigProperties config) {
        return new LocationResolver(safeAgentConfig(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenMeteoWeatherService openMeteoWeatherService(LocationResolver locationResolver,
                                                            @Autowired(required = false) AgentConfigProperties config) {
        return new OpenMeteoWeatherService(locationResolver, safeAgentConfig(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderSignalCollector reminderSignalCollector(
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) ProceduralMemory proceduralMemory,
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) SessionWorkspaceService sessionWorkspaceService,
            @Autowired(required = false) NotificationRepository notificationRepository,
            @Autowired(required = false) ReminderFeedbackRepository reminderFeedbackRepository,
            @Autowired(required = false) ReminderOutcomeRepository reminderOutcomeRepository,
            @Autowired(required = false) ReminderTopicAliasRepository reminderTopicAliasRepository,
            @Autowired(required = false) WeatherService weatherService) {
        return new DefaultReminderSignalCollector(
                semanticMemory,
                proceduralMemory,
                episodicMemory,
                sessionWorkspaceService,
                notificationRepository,
                reminderFeedbackRepository,
                reminderOutcomeRepository,
                reminderTopicAliasRepository,
                weatherService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderTrustGradient reminderTrustGradient(JdbcTemplate jdbcTemplate) {
        return new ReminderTrustGradient(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderDecisionEngine reminderDecisionEngine(
            @Autowired(required = false) ReminderTrustGradient reminderTrustGradient,
            @Autowired(required = false) com.lifepilot.agent.task.reminder.timing.GoldilocksWindowCalculator goldilocksWindowCalculator,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        java.util.function.Supplier<String> userIdSupplier = notificationProperties != null
                ? notificationProperties::getDefaultUserId : null;
        return new ReminderDecisionEngine(new com.lifepilot.agent.task.reminder.ReminderCandidateDetector(),
                reminderTrustGradient, goldilocksWindowCalculator, userIdSupplier);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderBehavior reminderBehavior(ReminderSignalCollector reminderSignalCollector,
                                             ReminderMessageGenerator reminderMessageGenerator,
                                             @Autowired(required = false) ReminderOutcomeInferenceService outcomeInferenceService) {
        return new ReminderBehavior(
                reminderSignalCollector,
                new com.lifepilot.agent.task.reminder.ReminderCandidateDetector(),
                reminderMessageGenerator,
                outcomeInferenceService);
    }

    @Bean
    @ConditionalOnMissingBean
    public WeatherSignalSource weatherSignalSource(
            @Autowired(required = false) AgentConfigProperties config) {
        return new WeatherSignalSource(safeAgentConfig(config).getTask().getWeatherApiBaseUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderFocusStateHolder reminderFocusStateHolder() {
        return new ReminderFocusStateHolder();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderMessageGenerator reminderMessageGenerator(
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) PromptRegistry promptRegistry,
            @Autowired(required = false) AgentConfigProperties config) {
        return new DefaultReminderMessageGenerator(generationRouter, promptRegistry, safeAgentConfig(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderPolicyTuner reminderPolicyTuner() {
        return new ReminderPolicyTuner();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderActionPolicySelector reminderActionPolicySelector(
            @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderActionPolicySelector(safeAgentConfig(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderOpportunityPolicySelector reminderOpportunityPolicySelector(
            @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderOpportunityPolicySelector(safeAgentConfig(config));
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
            @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderOutcomeInferenceService(
                reminderExecutionRepository,
                reminderOutcomeRepository,
                sessionWorkspaceService,
                semanticMemory,
                episodicMemory,
                workflowRepository,
                traceQuery,
                safeAgentConfig(config)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderReplayService reminderReplayService(ReminderExecutionRepository reminderExecutionRepository,
                                                       ReminderOpportunityPolicySelector reminderOpportunityPolicySelector,
                                                       ReminderActionPolicySelector reminderActionPolicySelector,
                                                       @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderReplayService(
                reminderExecutionRepository,
                reminderOpportunityPolicySelector,
                reminderActionPolicySelector,
                null,
                safeAgentConfig(config)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ReminderSituationSynthesizer reminderSituationSynthesizer(
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) PromptRegistry promptRegistry) {
        return new ReminderSituationSynthesizer(generationRouter, promptRegistry);
    }

    // ===== 调度器层 =====

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "proactive-reminder-replay-evaluation-enabled",
            havingValue = "true", matchIfMissing = true)
    public ReminderReplayEvaluationScheduler reminderReplayEvaluationScheduler(
            SharedScheduler sharedScheduler,
            ReminderExecutionRepository reminderExecutionRepository,
            ReminderReplayReportRepository reminderReplayReportRepository,
            ReminderReplayService reminderReplayService,
            @Autowired(required = false) NotificationProperties notificationProperties,
            @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderReplayEvaluationScheduler(
                sharedScheduler.heartbeat(),
                reminderExecutionRepository,
                reminderReplayReportRepository,
                reminderReplayService,
                notificationProperties != null ? notificationProperties : new NotificationProperties(),
                safeAgentConfig(config)
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
            @Autowired(required = false) AgentConfigProperties config) {
        return new ReminderRetentionScheduler(
                sharedScheduler.cleanup(),
                reminderExecutionRepository,
                reminderFeedbackRepository,
                reminderReplayReportRepository,
                reminderTopicAliasRepository,
                safeAgentConfig(config)
        );
    }

    private AgentConfigProperties safeAgentConfig(@Nullable AgentConfigProperties config) {
        return config != null ? config : new AgentConfigProperties();
    }
}
