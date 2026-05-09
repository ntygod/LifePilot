package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.behavior.ClipboardBehavior;
import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.proactive.behavior.ContextPrepBehavior;
import com.lifepilot.agent.task.proactive.behavior.FollowUpBehavior;
import com.lifepilot.agent.task.proactive.behavior.InfoSupplementBehavior;
import com.lifepilot.agent.task.proactive.behavior.InsightBehavior;
import com.lifepilot.agent.task.proactive.behavior.ReportBehavior;
import com.lifepilot.agent.task.proactive.behavior.TaskExecutionBehavior;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.reminder.ReminderBehavior;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
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

        var config = new ProactiveAutoConfiguration();

        // ── 仓储层 Bean ──
        GoalTrackingRepository goalTrackingRepo = config.goalTrackingRepository(jdbcTemplate);
        assertThat(goalTrackingRepo).isNotNull();

        QueuedActionRepository queuedActionRepository = config.queuedActionRepository(jdbcTemplate);
        assertThat(queuedActionRepository).isNotNull();

        AutonomyRepository autonomyRepository = config.autonomyRepository(jdbcTemplate);
        assertThat(autonomyRepository).isNotNull();

        // ── 服务层 Bean ──
        TrustUpgradeService trustUpgradeService = config.trustUpgradeService(autonomyRepository, null, null, null);
        assertThat(trustUpgradeService).isNotNull();

        DecisionGate decisionGate = config.proactiveDecisionGate(trustUpgradeService, memoryBridge, null);
        assertThat(decisionGate).isNotNull();

        DeliveryEngine deliveryEngine = config.proactiveDeliveryEngine(notificationService, queuedActionRepository);
        assertThat(deliveryEngine).isNotNull();

        ClipboardIntentBuffer clipboardIntentBuffer = config.clipboardIntentBuffer();
        assertThat(clipboardIntentBuffer).isNotNull();

        ScheduleExtractor scheduleExtractor = config.scheduleExtractor();
        assertThat(scheduleExtractor).isNotNull();

        // ── 行为插件 Bean ──
        FollowUpBehavior followUpBehavior = config.followUpBehavior(memoryBridge, null, null, null);
        assertThat(followUpBehavior).isNotNull();
        assertThat(followUpBehavior.name()).isEqualTo("follow-up");

        InsightBehavior insightBehavior = config.insightBehavior(null, null, null);
        assertThat(insightBehavior).isNotNull();
        assertThat(insightBehavior.name()).isEqualTo("insight");

        ClipboardBehavior clipboardBehavior = config.clipboardBehavior(clipboardIntentBuffer);
        assertThat(clipboardBehavior).isNotNull();
        assertThat(clipboardBehavior.name()).isEqualTo("clipboard");

        InfoSupplementBehavior infoSupplementBehavior = config.infoSupplementBehavior(memoryBridge);
        assertThat(infoSupplementBehavior).isNotNull();
        assertThat(infoSupplementBehavior.name()).isEqualTo("info-supplement");

        ContextPrepBehavior contextPrepBehavior = config.contextPrepBehavior(memoryBridge, null, null, null);
        assertThat(contextPrepBehavior).isNotNull();
        assertThat(contextPrepBehavior.name()).isEqualTo("context-prep");

        ReportBehavior reportBehavior = config.reportBehavior(null, null, null, null);
        assertThat(reportBehavior).isNotNull();
        assertThat(reportBehavior.name()).isEqualTo("report");

        TaskExecutionBehavior taskExecutionBehavior = config.taskExecutionBehavior(memoryBridge, trustUpgradeService);
        assertThat(taskExecutionBehavior).isNotNull();
        assertThat(taskExecutionBehavior.name()).isEqualTo("task-execution");

        // ── 隐式信号 + Hook ──
        var signalCollector = config.implicitSignalCollector(memoryBridge, trustUpgradeService);
        assertThat(signalCollector).isNotNull();

        var hook = config.conversationCompletionHook(signalCollector, null, null);
        assertThat(hook).isNotNull();
    }

    @Test
    void ProactiveEngine_注入所有行为插件() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = mock(NotificationProperties.class);
        when(notificationProperties.getDefaultUserId()).thenReturn("test-user");
        var memoryBridge = mock(ProactiveMemoryBridge.class);

        var config = new ProactiveAutoConfiguration();

        var queuedActionRepo = config.queuedActionRepository(jdbcTemplate);
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(autonomyRepo, null, null, null);
        var gate = config.proactiveDecisionGate(trustUpgrade, memoryBridge, null);
        var delivery = config.proactiveDeliveryEngine(notificationService, queuedActionRepo);
        var buffer = config.clipboardIntentBuffer();
        var signalCollector = config.implicitSignalCollector(memoryBridge, trustUpgrade);

        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(memoryBridge, null, null, null),
                config.insightBehavior(null, null, null),
                config.clipboardBehavior(buffer),
                config.infoSupplementBehavior(memoryBridge),
                config.contextPrepBehavior(memoryBridge, null, null, null),
                config.reportBehavior(null, null, null, null),
                config.taskExecutionBehavior(memoryBridge, trustUpgrade)
        );

        ProactiveEngine engine = config.proactiveEngine(
                behaviors, gate, delivery, notificationProperties,
                null, null, null, memoryBridge, trustUpgrade, signalCollector,
                null, null, null);

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
        DecisionGate gate = config.proactiveDecisionGate(null, null, null);
        assertThat(gate).isNotNull();
    }

    @Test
    void 行为插件名称唯一() {
        var config = new ProactiveAutoConfiguration();
        var memoryBridge = mock(ProactiveMemoryBridge.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(autonomyRepo, null, null, null);
        var buffer = config.clipboardIntentBuffer();

        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(memoryBridge, null, null, null),
                config.insightBehavior(null, null, null),
                config.clipboardBehavior(buffer),
                config.infoSupplementBehavior(memoryBridge),
                config.contextPrepBehavior(memoryBridge, null, null, null),
                config.reportBehavior(null, null, null, null),
                config.taskExecutionBehavior(memoryBridge, trustUpgrade)
        );

        var names = behaviors.stream().map(ProactiveBehavior::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).containsExactlyInAnyOrder(
                "follow-up", "insight", "clipboard",
                "info-supplement", "context-prep", "report", "task-execution");
    }
}
