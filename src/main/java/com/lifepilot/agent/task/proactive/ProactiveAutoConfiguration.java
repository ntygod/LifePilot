package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.config.ReminderAutoConfiguration;
import com.lifepilot.agent.task.proactive.behavior.*;
import com.lifepilot.agent.task.proactive.boundary.BoundarySignalCollector;
import com.lifepilot.agent.task.proactive.boundary.FocusStateDetector;
import com.lifepilot.agent.task.proactive.cot.GateThreeReasoner;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector;
import com.lifepilot.agent.task.proactive.training.ProactiveFewShotLibrary;
import com.lifepilot.agent.task.proactive.training.ProactiveTrainingReplayService;
import com.lifepilot.agent.task.proactive.training.ProactiveTrainingScheduler;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.agent.task.reminder.timing.GoldilocksWindowCalculator;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationAutoConfiguration;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 主动引擎框架自动配置。
 *
 * <p>注册 DecisionGate、DeliveryEngine、QueuedActionRepository、ProactiveEngine。
 * 行为插件（如 ReminderBehavior）由各自的自动配置类注册。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
@AutoConfiguration(after = {ReminderAutoConfiguration.class, NotificationAutoConfiguration.class})
@EnableConfigurationProperties(AgentConfigProperties.class)
public class ProactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GoalTrackingRepository goalTrackingRepository(JdbcTemplate jdbcTemplate) {
        return new GoalTrackingRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, EpisodicMemory.class, ProceduralMemory.class})
    public ProactiveMemoryBridge proactiveMemoryBridge(
            SemanticMemory semanticMemory,
            EpisodicMemory episodicMemory,
            ProceduralMemory proceduralMemory,
            GoalTrackingRepository goalTrackingRepository,
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher) {
        return new ProactiveMemoryBridge(
                semanticMemory, episodicMemory, proceduralMemory, goalTrackingRepository, jdbcTemplate,
                eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueuedActionRepository queuedActionRepository(JdbcTemplate jdbcTemplate) {
        return new QueuedActionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AutonomyRepository autonomyRepository(JdbcTemplate jdbcTemplate) {
        return new AutonomyRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReminderFeedbackRepository.class, SemanticMemory.class, AgentConfigProperties.class})
    public TrustUpgradeService trustUpgradeService(AutonomyRepository autonomyRepository,
                                                   AgentConfigProperties config,
                                                   ReminderFeedbackRepository reminderFeedbackRepository,
                                                   SemanticMemory semanticMemory) {
        // Task 25（解 S14）：负反馈可溯源到 L3 proactive_insight_* 实体做 importanceScore 惩罚。
        return new TrustUpgradeService(autonomyRepository, config,
                reminderFeedbackRepository, semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionGate proactiveDecisionGate(@Autowired(required = false) TrustUpgradeService trustUpgradeService,
                                              @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
                                              AgentConfigProperties config) {
        return new DecisionGate(
                trustUpgradeService, memoryBridge,
                config.getTask().getProactiveEngineBoundaryNotifyDelta(),
                config.getTask().getProactiveEngineBoundaryInterruptDelta(),
                config.getTask().getProactiveEngineOutOfBoundaryDelta()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryEngine proactiveDeliveryEngine(
            @Autowired(required = false) NotificationService notificationService,
            QueuedActionRepository queuedActionRepository) {
        return new DeliveryEngine(notificationService, queuedActionRepository);
    }

    // ── Phase 2 行为插件 ──

    @Bean
    @ConditionalOnMissingBean
    public ClipboardIntentBuffer clipboardIntentBuffer() {
        return new ClipboardIntentBuffer();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProactiveMemoryBridge.class, GenerationRouter.class, PromptRegistry.class, AgentConfigProperties.class})
    public FollowUpBehavior followUpBehavior(ProactiveMemoryBridge memoryBridge,
                                              GenerationRouter generationRouter,
                                              PromptRegistry promptRegistry,
                                              AgentConfigProperties config) {
        return new FollowUpBehavior(memoryBridge, generationRouter, promptRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, GenerationRouter.class, PromptRegistry.class})
    public InsightBehavior insightBehavior(SemanticMemory semanticMemory,
                                           GenerationRouter generationRouter,
                                           PromptRegistry promptRegistry) {
        return new InsightBehavior(semanticMemory, generationRouter, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClipboardBehavior clipboardBehavior(ClipboardIntentBuffer clipboardIntentBuffer) {
        return new ClipboardBehavior(clipboardIntentBuffer);
    }

    // ── Phase 3 行为插件 ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MemoryAttentionService.class)
    public MemoryAttentionBehavior memoryAttentionBehavior(MemoryAttentionService memoryAttentionService) {
        return new MemoryAttentionBehavior(memoryAttentionService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicMemory.class, GenerationRouter.class, PromptRegistry.class, AgentConfigProperties.class})
    public ReportBehavior reportBehavior(
            EpisodicMemory episodicMemory,
            GenerationRouter generationRouter,
            AgentConfigProperties config,
            PromptRegistry promptRegistry) {
        return new ReportBehavior(episodicMemory, generationRouter, config, promptRegistry);
    }

    // ── 日程提取 ──

    @Bean
    @ConditionalOnMissingBean
    public ScheduleExtractor scheduleExtractor() {
        return new ScheduleExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ImplicitSignalCollector.class, ConversationSummaryGenerator.class, UserProfileConsolidator.class})
    public ConversationCompletionHook conversationCompletionHook(
            ImplicitSignalCollector implicitSignalCollector,
            ConversationSummaryGenerator summaryGenerator,
            UserProfileConsolidator userProfileConsolidator) {
        return new ConversationCompletionHook(implicitSignalCollector, summaryGenerator, userProfileConsolidator);
    }

    // ── 隐式信号 ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProactiveMemoryBridge.class, TrustUpgradeService.class})
    public ImplicitSignalCollector implicitSignalCollector(
            ProactiveMemoryBridge memoryBridge,
            TrustUpgradeService trustUpgradeService) {
        return new ImplicitSignalCollector(
                memoryBridge, trustUpgradeService);
    }

    // ── Boundary / Focus（proactive-boundary-training spec） ──

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.boundary-signal-enabled", matchIfMissing = true)
    public BoundarySignalCollector boundarySignalCollector(
            AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        int window = config.getTask().getBoundaryWindowMinutes();
        return new BoundarySignalCollector(window, notificationProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.focus-detection-enabled", matchIfMissing = true)
    public FocusStateDetector focusStateDetector(AgentConfigProperties config) {
        int density = config.getTask().getFocusMessageDensityThreshold();
        int interval = config.getTask().getFocusMessageIntervalSeconds();
        return new FocusStateDetector(density, interval);
    }

    // ── 训练回放（proactive-boundary-training spec） ──

    @Bean
    @ConditionalOnMissingBean
    public ProactiveFewShotLibrary proactiveFewShotLibrary() {
        return ProactiveFewShotLibrary.withDefaultCacheDir();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReminderExecutionRepository.class, AgentConfigProperties.class})
    public ProactiveTrainingReplayService proactiveTrainingReplayService(
            ReminderExecutionRepository executionRepository,
            AgentConfigProperties config) {
        return new ProactiveTrainingReplayService(executionRepository, config.getTask());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SharedScheduler.class, ProactiveTrainingReplayService.class, AgentConfigProperties.class})
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.proactive-training-enabled", havingValue = "true")
    public ProactiveTrainingScheduler proactiveTrainingScheduler(
            SharedScheduler sharedScheduler,
            ProactiveTrainingReplayService replayService,
            ProactiveFewShotLibrary library,
            AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        var scheduler = new ProactiveTrainingScheduler(
                sharedScheduler.heartbeat(), replayService, library,
                config.getTask(), notificationProperties);
        scheduler.start();
        return scheduler;
    }

    // ── Timing / CoT / 分层激活（proactive-timing-cot spec） ──

    @Bean
    @ConditionalOnMissingBean
    public BehaviorActivationPolicy behaviorActivationPolicy() {
        return new BehaviorActivationPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReminderExecutionRepository.class, AgentConfigProperties.class})
    public GoldilocksWindowCalculator goldilocksWindowCalculator(
            ReminderExecutionRepository executionRepository,
            AgentConfigProperties config) {
        return new GoldilocksWindowCalculator(executionRepository, config.getTask());
    }

    @Bean
    @ConditionalOnMissingBean
    public GateThreeReasoner gateThreeReasoner(
            ProactiveFewShotLibrary fewShotLibrary,
            AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        return new GateThreeReasoner(fewShotLibrary, config.getTask(), notificationProperties);
    }

    // ── 引擎 ──

    @Bean
    @ConditionalOnMissingBean
    public ProactiveEngine proactiveEngine(List<ProactiveBehavior> behaviors,
                                           DecisionGate decisionGate,
                                           DeliveryEngine deliveryEngine,
                                           @Autowired(required = false) NotificationProperties notificationProperties,
                                           @Autowired(required = false) NotificationRepository notificationRepository,
                                           @Autowired(required = false) AgentConfigProperties config,
                                           @Autowired(required = false) ReminderFocusStateHolder focusStateHolder,
                                           @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
                                           @Autowired(required = false) TrustUpgradeService trustUpgradeService,
                                           @Autowired(required = false) ImplicitSignalCollector implicitSignalCollector,
                                           @Autowired(required = false) BoundarySignalCollector boundarySignalCollector,
                                           @Autowired(required = false) FocusStateDetector focusStateDetector,
                                           BehaviorActivationPolicy behaviorActivationPolicy) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder,
                memoryBridge, trustUpgradeService, implicitSignalCollector,
                boundarySignalCollector, focusStateDetector, behaviorActivationPolicy);
    }
}
