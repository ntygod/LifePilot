package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.behavior.ClipboardBehavior;
import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.proactive.behavior.ContextPrepBehavior;
import com.lifepilot.agent.task.proactive.behavior.FollowUpBehavior;
import com.lifepilot.agent.task.proactive.behavior.InfoSupplementBehavior;
import com.lifepilot.agent.task.proactive.behavior.InsightBehavior;
import com.lifepilot.agent.task.proactive.behavior.ReportBehavior;
import com.lifepilot.agent.task.proactive.behavior.TaskExecutionBehavior;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
@AutoConfiguration(after = com.lifepilot.agent.task.config.ReminderAutoConfiguration.class)
public class ProactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GoalTrackingRepository goalTrackingRepository(JdbcTemplate jdbcTemplate) {
        return new GoalTrackingRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProactiveMemoryBridge proactiveMemoryBridge(
            @Autowired(required = false) com.lifepilot.memory.semantic.SemanticMemory semanticMemory,
            @Autowired(required = false) com.lifepilot.memory.episodic.EpisodicMemory episodicMemory,
            @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
            GoalTrackingRepository goalTrackingRepository) {
        return new ProactiveMemoryBridge(semanticMemory, episodicMemory, proceduralMemory, goalTrackingRepository);
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
                                                     @Autowired(required = false) AgentConfigProperties config) {
        return new TrustUpgradeService(autonomyRepository, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionGate proactiveDecisionGate(@Autowired(required = false) TrustUpgradeService trustUpgradeService,
                                               @Autowired(required = false) ProactiveMemoryBridge memoryBridge) {
        return new DecisionGate(trustUpgradeService, memoryBridge);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NotificationService.class)
    public DeliveryEngine proactiveDeliveryEngine(NotificationService notificationService,
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
            @Autowired(required = false) com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector implicitSignalCollector) {
        return new ConversationCompletionHook(implicitSignalCollector);
    }

    // ── 隐式信号 ──

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector implicitSignalCollector(
            @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
            @Autowired(required = false) TrustUpgradeService trustUpgradeService) {
        return new com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector(
                memoryBridge, trustUpgradeService);
    }

    // ── 引擎 ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProactiveBehavior.class, DeliveryEngine.class})
    public ProactiveEngine proactiveEngine(List<ProactiveBehavior> behaviors,
                                           DecisionGate decisionGate,
                                           DeliveryEngine deliveryEngine,
                                           @Autowired(required = false) NotificationProperties notificationProperties,
                                           @Autowired(required = false) NotificationRepository notificationRepository,
                                           @Autowired(required = false) AgentConfigProperties config,
                                           @Autowired(required = false) ReminderFocusStateHolder focusStateHolder,
                                           @Autowired(required = false) ProactiveMemoryBridge memoryBridge,
                                           @Autowired(required = false) TrustUpgradeService trustUpgradeService,
                                           @Autowired(required = false) com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector implicitSignalCollector) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder,
                memoryBridge, trustUpgradeService, implicitSignalCollector);
    }
}
