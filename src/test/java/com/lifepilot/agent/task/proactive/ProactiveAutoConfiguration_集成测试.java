package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.behavior.ClipboardBehavior;
import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.proactive.behavior.ContextPrepBehavior;
import com.lifepilot.agent.task.proactive.behavior.FollowUpBehavior;
import com.lifepilot.agent.task.proactive.behavior.InfoSupplementBehavior;
import com.lifepilot.agent.task.proactive.behavior.InsightBehavior;
import com.lifepilot.agent.task.proactive.behavior.ReportBehavior;
import com.lifepilot.agent.task.proactive.behavior.TaskExecutionBehavior;
import com.lifepilot.agent.task.proactive.intent.IntentExtractor;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRepository;
import com.lifepilot.agent.task.proactive.preference.PreferenceLearner;
import com.lifepilot.agent.task.proactive.preference.PreferenceRepository;
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
 * <p>手动模拟 Bean 注册流程，验证 ProactiveAutoConfiguration 注册的所有 Bean
 * 能正确构造且依赖注入链完整。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
class ProactiveAutoConfiguration_集成测试 {

    @Test
    void 所有Bean能正确构造_依赖注入链完整() {
        // 模拟基础依赖
        var jdbcTemplate = mock(JdbcTemplate.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = mock(NotificationProperties.class);
        when(notificationProperties.getDefaultUserId()).thenReturn("test-user");

        var config = new ProactiveAutoConfiguration();

        // ── 仓储层 Bean ──
        QueuedActionRepository queuedActionRepository = config.queuedActionRepository(jdbcTemplate);
        assertThat(queuedActionRepository).isNotNull();

        AutonomyRepository autonomyRepository = config.autonomyRepository(jdbcTemplate);
        assertThat(autonomyRepository).isNotNull();

        IntentRepository intentRepository = config.intentRepository(jdbcTemplate);
        assertThat(intentRepository).isNotNull();

        PreferenceRepository preferenceRepository = config.preferenceRepository(jdbcTemplate);
        assertThat(preferenceRepository).isNotNull();

        // ── 服务层 Bean ──
        TrustUpgradeService trustUpgradeService = config.trustUpgradeService(autonomyRepository, null);
        assertThat(trustUpgradeService).isNotNull();

        DecisionGate decisionGate = config.proactiveDecisionGate(trustUpgradeService);
        assertThat(decisionGate).isNotNull();

        DeliveryEngine deliveryEngine = config.proactiveDeliveryEngine(notificationService, queuedActionRepository);
        assertThat(deliveryEngine).isNotNull();

        IntentExtractor intentExtractor = config.intentExtractor();
        assertThat(intentExtractor).isNotNull();

        IntentMemoryService intentMemoryService = config.intentMemoryService(intentRepository, intentExtractor, null);
        assertThat(intentMemoryService).isNotNull();

        ClipboardIntentBuffer clipboardIntentBuffer = config.clipboardIntentBuffer();
        assertThat(clipboardIntentBuffer).isNotNull();

        PreferenceLearner preferenceLearner = config.preferenceLearner(preferenceRepository);
        assertThat(preferenceLearner).isNotNull();

        ScheduleExtractor scheduleExtractor = config.scheduleExtractor();
        assertThat(scheduleExtractor).isNotNull();

        // ── 行为插件 Bean ──
        FollowUpBehavior followUpBehavior = config.followUpBehavior(intentMemoryService, null, null, null);
        assertThat(followUpBehavior).isNotNull();
        assertThat(followUpBehavior.name()).isEqualTo("follow-up");

        InsightBehavior insightBehavior = config.insightBehavior(null, null, null);
        assertThat(insightBehavior).isNotNull();
        assertThat(insightBehavior.name()).isEqualTo("insight");

        ClipboardBehavior clipboardBehavior = config.clipboardBehavior(clipboardIntentBuffer);
        assertThat(clipboardBehavior).isNotNull();
        assertThat(clipboardBehavior.name()).isEqualTo("clipboard");

        InfoSupplementBehavior infoSupplementBehavior = config.infoSupplementBehavior(intentMemoryService);
        assertThat(infoSupplementBehavior).isNotNull();
        assertThat(infoSupplementBehavior.name()).isEqualTo("info-supplement");

        ContextPrepBehavior contextPrepBehavior = config.contextPrepBehavior(intentMemoryService, null, null, null);
        assertThat(contextPrepBehavior).isNotNull();
        assertThat(contextPrepBehavior.name()).isEqualTo("context-prep");

        ReportBehavior reportBehavior = config.reportBehavior(null, null, null);
        assertThat(reportBehavior).isNotNull();
        assertThat(reportBehavior.name()).isEqualTo("report");

        TaskExecutionBehavior taskExecutionBehavior = config.taskExecutionBehavior(intentMemoryService, trustUpgradeService);
        assertThat(taskExecutionBehavior).isNotNull();
        assertThat(taskExecutionBehavior.name()).isEqualTo("task-execution");
    }

    @Test
    void ProactiveEngine_注入所有行为插件() {
        // 模拟基础依赖
        var jdbcTemplate = mock(JdbcTemplate.class);
        var notificationService = mock(NotificationService.class);
        var notificationProperties = mock(NotificationProperties.class);
        when(notificationProperties.getDefaultUserId()).thenReturn("test-user");

        var config = new ProactiveAutoConfiguration();

        // 构建依赖链
        var queuedActionRepo = config.queuedActionRepository(jdbcTemplate);
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var intentRepo = config.intentRepository(jdbcTemplate);
        var preferenceRepo = config.preferenceRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(autonomyRepo, null);
        var gate = config.proactiveDecisionGate(trustUpgrade);
        var delivery = config.proactiveDeliveryEngine(notificationService, queuedActionRepo);
        var extractor = config.intentExtractor();
        var intentMemory = config.intentMemoryService(intentRepo, extractor, null);
        var buffer = config.clipboardIntentBuffer();
        var preferenceLearner = config.preferenceLearner(preferenceRepo);

        // 构建行为插件列表（模拟 Spring 注入 List<ProactiveBehavior>）
        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(intentMemory, null, null, null),
                config.insightBehavior(null, null, null),
                config.clipboardBehavior(buffer),
                config.infoSupplementBehavior(intentMemory),
                config.contextPrepBehavior(intentMemory, null, null, null),
                config.reportBehavior(null, null, null),
                config.taskExecutionBehavior(intentMemory, trustUpgrade)
        );

        // 构建引擎
        ProactiveEngine engine = config.proactiveEngine(
                behaviors, gate, delivery, notificationProperties,
                null, null, null, intentMemory, preferenceLearner);

        assertThat(engine).isNotNull();
    }

    @Test
    void ReminderBehavior由ReminderAutoConfiguration注册_ProactiveAutoConfiguration不含() {
        // ReminderBehavior 是由 ReminderAutoConfiguration 注册的，
        // ProactiveAutoConfiguration 不应重复注册
        var config = new ProactiveAutoConfiguration();
        var methods = config.getClass().getDeclaredMethods();
        boolean hasReminderBehavior = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getReturnType() == ReminderBehavior.class);
        assertThat(hasReminderBehavior).isFalse();
    }

    @Test
    void DecisionGate_支持无TrustUpgradeService构造() {
        var config = new ProactiveAutoConfiguration();

        // TrustUpgradeService 可为 null（@Autowired(required = false)）
        DecisionGate gate = config.proactiveDecisionGate(null);
        assertThat(gate).isNotNull();
    }

    @Test
    void 行为插件名称唯一() {
        var config = new ProactiveAutoConfiguration();
        var jdbcTemplate = mock(JdbcTemplate.class);
        var intentRepo = config.intentRepository(jdbcTemplate);
        var extractor = config.intentExtractor();
        var intentMemory = config.intentMemoryService(intentRepo, extractor, null);
        var buffer = config.clipboardIntentBuffer();
        var autonomyRepo = config.autonomyRepository(jdbcTemplate);
        var trustUpgrade = config.trustUpgradeService(autonomyRepo, null);

        var behaviors = java.util.List.<ProactiveBehavior>of(
                config.followUpBehavior(intentMemory, null, null, null),
                config.insightBehavior(null, null, null),
                config.clipboardBehavior(buffer),
                config.infoSupplementBehavior(intentMemory),
                config.contextPrepBehavior(intentMemory, null, null, null),
                config.reportBehavior(null, null, null),
                config.taskExecutionBehavior(intentMemory, trustUpgrade)
        );

        var names = behaviors.stream().map(ProactiveBehavior::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).containsExactlyInAnyOrder(
                "follow-up", "insight", "clipboard",
                "info-supplement", "context-prep", "report", "task-execution");
    }
}
