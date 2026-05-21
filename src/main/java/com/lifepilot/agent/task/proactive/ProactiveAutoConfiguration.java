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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
public class ProactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GoalTrackingRepository goalTrackingRepository(JdbcTemplate jdbcTemplate) {
        return new GoalTrackingRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProactiveMemoryBridge proactiveMemoryBridge(
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) ProceduralMemory proceduralMemory,
            GoalTrackingRepository goalTrackingRepository,
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher) {
        var bridge = new ProactiveMemoryBridge(
                semanticMemory, episodicMemory, proceduralMemory, goalTrackingRepository, jdbcTemplate);
        bridge.setEventPublisher(eventPublisher);
        return bridge;
    }

    /**
     * memory-staleness spec C-P0-1：L3 生命周期事件 → 主动引擎缓存失效。
     * ProactiveMemoryBridge 通过 ObjectProvider 注入允许其缺失时监听器仍能启动。
     */
    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.task.proactive.cache.ProactiveCacheInvalidator proactiveCacheInvalidator(
            org.springframework.beans.factory.ObjectProvider<ProactiveMemoryBridge> bridgeProvider) {
        return new com.lifepilot.agent.task.proactive.cache.ProactiveCacheInvalidator(bridgeProvider);
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
    public TrustUpgradeService trustUpgradeService(AutonomyRepository autonomyRepository,
                                                     @Autowired(required = false) AgentConfigProperties config,
                                                     @Autowired(required = false) ReminderFeedbackRepository reminderFeedbackRepository,
                                                     @Autowired(required = false) SemanticMemory semanticMemory) {
        // Task 25（解 S14）：注入 ReminderFeedbackRepository + SemanticMemory 后
        // recordNegativeFeedback(userId, behaviorName, notificationId) 可溯源到
        // L3 proactive_insight_* 实体做 importanceScore 惩罚
        return new TrustUpgradeService(autonomyRepository, config,
                reminderFeedbackRepository, semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionGate proactiveDecisionGate(@Autowired(required = false) TrustUpgradeService trustUpgradeService,
                                               @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
                                               @Autowired(required = false) AgentConfigProperties config) {
        if (config != null) {
            return new DecisionGate(
                    trustUpgradeService, memoryBridge,
                    config.getTask().getProactiveEngineBoundaryNotifyDelta(),
                    config.getTask().getProactiveEngineBoundaryInterruptDelta(),
                    config.getTask().getProactiveEngineOutOfBoundaryDelta()
            );
        }
        return new DecisionGate(trustUpgradeService, memoryBridge);
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
    public FollowUpBehavior followUpBehavior(ProactiveMemoryBridge memoryBridge,
                                              @Autowired(required = false) GenerationRouter generationRouter,
                                              @Autowired(required = false) PromptRegistry promptRegistry,
                                              @Autowired(required = false) AgentConfigProperties config) {
        return new FollowUpBehavior(memoryBridge, generationRouter, promptRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public InsightBehavior insightBehavior(@Autowired(required = false) SemanticMemory semanticMemory,
                                           @Autowired(required = false) GenerationRouter generationRouter,
                                           @Autowired(required = false) PromptRegistry promptRegistry) {
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
    public InfoSupplementBehavior infoSupplementBehavior(
            @Autowired(required = false) ProactiveMemoryBridge memoryBridge) {
        return new InfoSupplementBehavior(memoryBridge);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextPrepBehavior contextPrepBehavior(
            @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) PromptRegistry promptRegistry,
            @Autowired(required = false) AgentConfigProperties config) {
        return new ContextPrepBehavior(memoryBridge, generationRouter, promptRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportBehavior reportBehavior(
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) AgentConfigProperties config,
            @Autowired(required = false) PromptRegistry promptRegistry) {
        return new ReportBehavior(episodicMemory, generationRouter, config, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskExecutionBehavior taskExecutionBehavior(
            @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
            @Autowired(required = false) TrustUpgradeService trustUpgradeService) {
        return new TaskExecutionBehavior(memoryBridge, trustUpgradeService);
    }

    // ── 日程提取 ──

    @Bean
    @ConditionalOnMissingBean
    public ScheduleExtractor scheduleExtractor() {
        return new ScheduleExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationCompletionHook conversationCompletionHook(
            @Autowired(required = false) ImplicitSignalCollector implicitSignalCollector,
            @Autowired(required = false) ConversationSummaryGenerator summaryGenerator,
            @Autowired(required = false) UserProfileConsolidator userProfileConsolidator) {
        return new ConversationCompletionHook(implicitSignalCollector, summaryGenerator, userProfileConsolidator);
    }

    // ── 隐式信号 ──

    @Bean
    @ConditionalOnMissingBean
    public ImplicitSignalCollector implicitSignalCollector(
            @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
            @Autowired(required = false) TrustUpgradeService trustUpgradeService) {
        return new ImplicitSignalCollector(
                memoryBridge, trustUpgradeService);
    }

    // ── Boundary / Focus（proactive-boundary-training spec） ──

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.boundary-signal-enabled", matchIfMissing = true)
    public BoundarySignalCollector boundarySignalCollector(
            @Autowired(required = false) AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        int window = config != null ? config.getTask().getBoundaryWindowMinutes() : 10;
        return new BoundarySignalCollector(window, notificationProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.focus-detection-enabled", matchIfMissing = true)
    public FocusStateDetector focusStateDetector(@Autowired(required = false) AgentConfigProperties config) {
        int density = config != null ? config.getTask().getFocusMessageDensityThreshold() : 5;
        int interval = config != null ? config.getTask().getFocusMessageIntervalSeconds() : 40;
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
    public ProactiveTrainingReplayService proactiveTrainingReplayService(
            @Autowired(required = false) ReminderExecutionRepository executionRepository,
            @Autowired(required = false) AgentConfigProperties config) {
        if (executionRepository == null || config == null) return null;
        return new ProactiveTrainingReplayService(executionRepository, config.getTask());
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "lifepilot.agent.task.proactive-training-enabled", havingValue = "true")
    public ProactiveTrainingScheduler proactiveTrainingScheduler(
            @Autowired(required = false) SharedScheduler sharedScheduler,
            @Autowired(required = false) ProactiveTrainingReplayService replayService,
            ProactiveFewShotLibrary library,
            @Autowired(required = false) AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        if (sharedScheduler == null || replayService == null || config == null) {
            return null;
        }
        var scheduler = new ProactiveTrainingScheduler(
                sharedScheduler.heartbeat(), replayService, library,
                config.getTask(), notificationProperties);
        scheduler.start();
        return scheduler;
    }

    // ── Timing / CoT / 分层激活（proactive-timing-cot spec） ──

    @Bean
    @ConditionalOnMissingBean
    public BehaviorActivationPolicy behaviorActivationPolicy(
            @Autowired(required = false) AgentConfigProperties config) {
        AgentConfigProperties.TaskConfig taskConfig =
                config != null ? config.getTask() : new AgentConfigProperties().getTask();
        return new BehaviorActivationPolicy(taskConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public GoldilocksWindowCalculator goldilocksWindowCalculator(
            @Autowired(required = false) ReminderExecutionRepository executionRepository,
            @Autowired(required = false) AgentConfigProperties config) {
        if (executionRepository == null || config == null) return null;
        return new GoldilocksWindowCalculator(executionRepository, config.getTask());
    }

    @Bean
    @ConditionalOnMissingBean
    public GateThreeReasoner gateThreeReasoner(
            ProactiveFewShotLibrary fewShotLibrary,
            @Autowired(required = false) AgentConfigProperties config,
            @Autowired(required = false) com.lifepilot.notification.config.NotificationProperties notificationProperties) {
        AgentConfigProperties.TaskConfig taskConfig =
                config != null ? config.getTask() : new AgentConfigProperties().getTask();
        return new GateThreeReasoner(fewShotLibrary, taskConfig, notificationProperties);
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
                                           @Autowired(required = false) BehaviorActivationPolicy behaviorActivationPolicy) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder,
                memoryBridge, trustUpgradeService, implicitSignalCollector,
                boundarySignalCollector, focusStateDetector, behaviorActivationPolicy);
    }
}
