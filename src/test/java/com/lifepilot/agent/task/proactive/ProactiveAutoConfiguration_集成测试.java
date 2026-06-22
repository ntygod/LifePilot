package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.behavior.ClipboardBehavior;
import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.proactive.behavior.FollowUpBehavior;
import com.lifepilot.agent.task.proactive.behavior.InsightBehavior;
import com.lifepilot.agent.task.proactive.behavior.MemoryAttentionBehavior;
import com.lifepilot.agent.task.proactive.behavior.ReportBehavior;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.reminder.ReminderBehavior;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProactiveAutoConfiguration 集成测试 — 验证所有 Bean 能正确注册和构造。
 *
 * @author zsg
 * @since 2026-04-14
 */
class ProactiveAutoConfiguration_集成测试 {

    @Test
    void 所有Bean能正确构造_依赖注入链完整() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = mock(NotificationProperties.class);
        when(notificationProperties.getDefaultUserId()).thenReturn("test-user");
        var memoryBridge = mock(ProactiveMemoryBridge.class);
        var agentConfig = new AgentConfigProperties();
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var semanticMemory = mock(SemanticMemory.class);
        var episodicMemory = mock(EpisodicMemory.class);
        var memoryAttentionService = mock(MemoryAttentionService.class);
        var reminderFeedbackRepository = mock(ReminderFeedbackRepository.class);

        var config = new ProactiveAutoConfiguration();

        // ── 仓储层 Bean ──
        GoalTrackingRepository goalTrackingRepo = config.goalTrackingRepository(jdbcTemplate);
        assertThat(goalTrackingRepo).isNotNull();

        QueuedActionRepository queuedActionRepository = config.queuedActionRepository(jdbcTemplate);
        assertThat(queuedActionRepository).isNotNull();

        AutonomyRepository autonomyRepository = config.autonomyRepository(jdbcTemplate);
        assertThat(autonomyRepository).isNotNull();

        // ── 服务层 Bean ──
        TrustUpgradeService trustUpgradeService = config.trustUpgradeService(
                autonomyRepository, agentConfig, reminderFeedbackRepository, semanticMemory);
        assertThat(trustUpgradeService).isNotNull();

        DecisionGate decisionGate = config.proactiveDecisionGate(trustUpgradeService, memoryBridge, agentConfig);
        assertThat(decisionGate).isNotNull();

        DeliveryEngine deliveryEngine = config.proactiveDeliveryEngine(notificationService, queuedActionRepository);
        assertThat(deliveryEngine).isNotNull();

        ClipboardIntentBuffer clipboardIntentBuffer = config.clipboardIntentBuffer();
        assertThat(clipboardIntentBuffer).isNotNull();

        ScheduleExtractor scheduleExtractor = config.scheduleExtractor();
        assertThat(scheduleExtractor).isNotNull();

        // ── 行为插件 Bean ──
        FollowUpBehavior followUpBehavior = config.followUpBehavior(
                memoryBridge, generationRouter, promptRegistry, agentConfig);
        assertThat(followUpBehavior).isNotNull();
        assertThat(followUpBehavior.name()).isEqualTo("follow-up");

        InsightBehavior insightBehavior = config.insightBehavior(semanticMemory, generationRouter, promptRegistry);
        assertThat(insightBehavior).isNotNull();
        assertThat(insightBehavior.name()).isEqualTo("insight");

        ClipboardBehavior clipboardBehavior = config.clipboardBehavior(clipboardIntentBuffer);
        assertThat(clipboardBehavior).isNotNull();
        assertThat(clipboardBehavior.name()).isEqualTo("clipboard");

        MemoryAttentionBehavior memoryAttentionBehavior = config.memoryAttentionBehavior(memoryAttentionService);
        assertThat(memoryAttentionBehavior).isNotNull();
        assertThat(memoryAttentionBehavior.name()).isEqualTo("memory-attention");

        ReportBehavior reportBehavior = config.reportBehavior(episodicMemory, generationRouter, agentConfig, promptRegistry);
        assertThat(reportBehavior).isNotNull();
        assertThat(reportBehavior.name()).isEqualTo("report");

        // ── 隐式信号 + Hook ──
        var signalCollector = config.implicitSignalCollector(memoryBridge, trustUpgradeService);
        assertThat(signalCollector).isNotNull();

        assertThat(signalCollector).isNotNull();
    }

    @Test
    void ProactiveEngine_注入所有行为插件() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = mock(NotificationProperties.class);
        when(notificationProperties.getDefaultUserId()).thenReturn("test-user");
        var memoryBridge = mock(ProactiveMemoryBridge.class);
        var agentConfig = new AgentConfigProperties();
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var semanticMemory = mock(SemanticMemory.class);
        var episodicMemory = mock(EpisodicMemory.class);
        var memoryAttentionService = mock(MemoryAttentionService.class);
        var reminderFeedbackRepository = mock(ReminderFeedbackRepository.class);

        var config = new ProactiveAutoConfiguration();

        var queuedActionRepo = config.queuedActionRepository(jdbcTemplate);
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(
                autonomyRepo, agentConfig, reminderFeedbackRepository, semanticMemory);
        var gate = config.proactiveDecisionGate(trustUpgrade, memoryBridge, agentConfig);
        var delivery = config.proactiveDeliveryEngine(notificationService, queuedActionRepo);
        var buffer = config.clipboardIntentBuffer();
        var signalCollector = config.implicitSignalCollector(memoryBridge, trustUpgrade);
        var activationPolicy = config.behaviorActivationPolicy();

        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(memoryBridge, generationRouter, promptRegistry, agentConfig),
                config.insightBehavior(semanticMemory, generationRouter, promptRegistry),
                config.clipboardBehavior(buffer),
                config.memoryAttentionBehavior(memoryAttentionService),
                config.reportBehavior(episodicMemory, generationRouter, agentConfig, promptRegistry)
        );

        ProactiveEngine engine = config.proactiveEngine(
                behaviors, gate, delivery, notificationProperties,
                null, null, null, memoryBridge, trustUpgrade, signalCollector,
                null, null, activationPolicy);

        assertThat(engine).isNotNull();
    }

    @Test
    void ReminderBehavior由ReminderAutoConfiguration注册_ProactiveAutoConfiguration不含() {
        var config = new ProactiveAutoConfiguration();
        var methods = config.getClass().getDeclaredMethods();
        boolean hasReminderBehavior = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getReturnType() == ReminderBehavior.class);
        assertThat(hasReminderBehavior).isFalse();
    }

    @Test
    void DecisionGate_支持无依赖构造() {
        var config = new ProactiveAutoConfiguration();
        DecisionGate gate = config.proactiveDecisionGate(null, null, new AgentConfigProperties());
        assertThat(gate).isNotNull();
    }

    @Test
    void 行为插件名称唯一() {
        var config = new ProactiveAutoConfiguration();
        var memoryBridge = mock(ProactiveMemoryBridge.class);
        var agentConfig = new AgentConfigProperties();
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var semanticMemory = mock(SemanticMemory.class);
        var episodicMemory = mock(EpisodicMemory.class);
        var memoryAttentionService = mock(MemoryAttentionService.class);
        var reminderFeedbackRepository = mock(ReminderFeedbackRepository.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(
                autonomyRepo, agentConfig, reminderFeedbackRepository, semanticMemory);
        var buffer = config.clipboardIntentBuffer();

        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(memoryBridge, generationRouter, promptRegistry, agentConfig),
                config.insightBehavior(semanticMemory, generationRouter, promptRegistry),
                config.clipboardBehavior(buffer),
                config.memoryAttentionBehavior(memoryAttentionService),
                config.reportBehavior(episodicMemory, generationRouter, agentConfig, promptRegistry)
        );

        var names = behaviors.stream().map(ProactiveBehavior::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).containsExactlyInAnyOrder(
                "follow-up", "insight", "clipboard",
                "memory-attention", "report");
    }
}
