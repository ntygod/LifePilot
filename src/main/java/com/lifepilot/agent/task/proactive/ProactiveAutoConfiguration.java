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
import com.lifepilot.agent.task.proactive.preference.PreferenceLearner;
import com.lifepilot.agent.task.proactive.preference.PreferenceRepository;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.proactive.intent.IntentExtractor;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRepository;
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
                                                     AgentConfigProperties config) {
        return new TrustUpgradeService(autonomyRepository, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionGate proactiveDecisionGate(@Autowired(required = false) TrustUpgradeService trustUpgradeService) {
        return new DecisionGate(trustUpgradeService);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryEngine proactiveDeliveryEngine(NotificationService notificationService,
                                                   QueuedActionRepository queuedActionRepository) {
        return new DeliveryEngine(notificationService, queuedActionRepository);
    }

    // ── 意图记忆 ──

    @Bean
    @ConditionalOnMissingBean
    public IntentRepository intentRepository(JdbcTemplate jdbcTemplate) {
        return new IntentRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public IntentExtractor intentExtractor() {
        return new IntentExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public IntentMemoryService intentMemoryService(IntentRepository intentRepository,
                                                    IntentExtractor intentExtractor,
                                                    @Autowired(required = false) EpisodicMemory episodicMemory) {
        return new IntentMemoryService(intentRepository, intentExtractor, episodicMemory);
    }

    // ── Phase 2 行为插件 ──

    @Bean
    @ConditionalOnMissingBean
    public ClipboardIntentBuffer clipboardIntentBuffer() {
        return new ClipboardIntentBuffer();
    }

    @Bean
    @ConditionalOnMissingBean
    public FollowUpBehavior followUpBehavior(IntentMemoryService intentMemoryService,
                                              @Autowired(required = false) GenerationRouter generationRouter,
                                              @Autowired(required = false) PromptRegistry promptRegistry,
                                              AgentConfigProperties config) {
        return new FollowUpBehavior(intentMemoryService, generationRouter, promptRegistry, config);
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
            @Autowired(required = false) IntentMemoryService intentMemoryService) {
        return new InfoSupplementBehavior(intentMemoryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextPrepBehavior contextPrepBehavior(
            @Autowired(required = false) IntentMemoryService intentMemoryService,
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) PromptRegistry promptRegistry,
            AgentConfigProperties config) {
        return new ContextPrepBehavior(intentMemoryService, generationRouter, promptRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportBehavior reportBehavior(
            @Autowired(required = false) EpisodicMemory episodicMemory,
            @Autowired(required = false) GenerationRouter generationRouter,
            AgentConfigProperties config) {
        return new ReportBehavior(episodicMemory, generationRouter, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskExecutionBehavior taskExecutionBehavior(
            @Autowired(required = false) IntentMemoryService intentMemoryService,
            @Autowired(required = false) TrustUpgradeService trustUpgradeService) {
        return new TaskExecutionBehavior(intentMemoryService, trustUpgradeService);
    }

    // ── Phase 4: 偏好模型 + 日程提取 ──

    @Bean
    @ConditionalOnMissingBean
    public PreferenceRepository preferenceRepository(JdbcTemplate jdbcTemplate) {
        return new PreferenceRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public PreferenceLearner preferenceLearner(PreferenceRepository preferenceRepository) {
        return new PreferenceLearner(preferenceRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleExtractor scheduleExtractor() {
        return new ScheduleExtractor();
    }

    // ── 引擎 ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProactiveBehavior.class)
    public ProactiveEngine proactiveEngine(List<ProactiveBehavior> behaviors,
                                           DecisionGate decisionGate,
                                           DeliveryEngine deliveryEngine,
                                           NotificationProperties notificationProperties,
                                           NotificationRepository notificationRepository,
                                           AgentConfigProperties config,
                                           @Autowired(required = false) ReminderFocusStateHolder focusStateHolder,
                                           @Autowired(required = false) IntentMemoryService intentMemoryService,
                                           @Autowired(required = false) PreferenceLearner preferenceLearner) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder,
                intentMemoryService, preferenceLearner);
    }
}
