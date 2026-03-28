package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderPolicyTuner 单元测试。
 *
 * <p>验证策略会根据正负反馈自动收紧或放宽。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderPolicyTuner_单元测试 {

    private final ReminderPolicyTuner tuner = new ReminderPolicyTuner();
    private final ReminderPolicyConfig baseConfig = new ReminderPolicyConfig();
    private final AgentConfigProperties.TaskConfig taskConfig = new AgentConfigProperties.TaskConfig();

    @Test
    void tune_负反馈偏多_策略收紧() {
        ReminderUserFeedbackSummary summary = new ReminderUserFeedbackSummary(
                1, 0, 3, 2, 1
        );

        ReminderPolicyConfig tuned = tuner.tune(baseConfig, summary);

        assertThat(tuned.dailyMaxReminders()).isLessThanOrEqualTo(baseConfig.dailyMaxReminders());
        assertThat(tuned.defaultCooldownHours()).isGreaterThan(baseConfig.defaultCooldownHours());
        assertThat(tuned.minFinalScore()).isGreaterThan(baseConfig.minFinalScore());
        assertThat(tuned.softPushThreshold()).isGreaterThanOrEqualTo(baseConfig.softPushThreshold());
    }

    @Test
    void tune_正反馈偏多_策略放宽() {
        ReminderUserFeedbackSummary summary = new ReminderUserFeedbackSummary(
                5, 2, 0, 0, 0
        );

        ReminderPolicyConfig tuned = tuner.tune(baseConfig, summary);

        assertThat(tuned.dailyMaxReminders()).isGreaterThanOrEqualTo(baseConfig.dailyMaxReminders());
        assertThat(tuned.defaultCooldownHours()).isLessThanOrEqualTo(baseConfig.defaultCooldownHours());
        assertThat(tuned.minFinalScore()).isLessThan(baseConfig.minFinalScore());
        assertThat(tuned.strongPushThreshold()).isLessThanOrEqualTo(baseConfig.strongPushThreshold());
    }

    @Test
    void tune_回放建议收紧_会抬高阈值并拉长冷却() {
        ReminderReplayReport replayReport = new ReminderReplayReport(
                "default",
                java.time.Instant.parse("2026-03-01T00:00:00Z"),
                20,
                20,
                14,
                6,
                1,
                8,
                0.52f,
                0.50f,
                0.56f,
                java.util.Map.of("NORMAL_PUSH->SOFT_PUSH", 5, "SOFT_PUSH->SKIP", 3),
                java.util.List.of()
        );

        ReminderPolicyConfig tuned = tuner.tune(baseConfig, ReminderUserFeedbackSummary.empty(), replayReport);

        assertThat(tuned.minFinalScore()).isGreaterThan(baseConfig.minFinalScore());
        assertThat(tuned.softPushThreshold()).isGreaterThan(baseConfig.softPushThreshold());
        assertThat(tuned.defaultCooldownHours()).isGreaterThan(baseConfig.defaultCooldownHours());
    }

    @Test
    void applyGuardrail_单轮步长过大_会按锚点裁剪() {
        ReminderPolicyConfig latest = new ReminderPolicyConfig(24, 18, 3, 24, 60, 0.55f, 0.63f, 0.78f, 0.65f);
        ReminderPolicyConfig tuned = new ReminderPolicyConfig(36, 9, 5, 12, 150, 0.46f, 0.52f, 0.67f, 0.52f);

        ReminderPolicyGuardrailResult result = tuner.applyGuardrail(
                baseConfig,
                tuned,
                latest,
                ReminderPolicyVersionInsight.empty(),
                ReminderUserFeedbackSummary.empty(),
                null,
                taskConfig
        );

        assertThat(result.adjusted()).isTrue();
        assertThat(result.config().dueSoonThresholdHours()).isEqualTo(30);
        assertThat(result.config().dailyMaxReminders()).isEqualTo(4);
        assertThat(result.config().preferredWindowLookaheadMinutes()).isEqualTo(105);
        assertThat(result.config().minFinalScore()).isEqualTo(0.51f);
    }

    @Test
    void applyGuardrail_回放恶化且策略更激进_回退到上一版() {
        ReminderPolicyConfig latest = new ReminderPolicyConfig(24, 18, 3, 24, 60, 0.55f, 0.63f, 0.78f, 0.65f);
        ReminderPolicyConfig tuned = new ReminderPolicyConfig(28, 14, 4, 18, 90, 0.50f, 0.59f, 0.74f, 0.61f);
        ReminderReplayReport replayReport = new ReminderReplayReport(
                "default",
                java.time.Instant.parse("2026-03-01T00:00:00Z"),
                30,
                12,
                18,
                1,
                6,
                7,
                0.61f,
                0.70f,
                0.62f,
                java.util.Map.of(),
                java.util.List.of()
        );

        ReminderPolicyGuardrailResult result = tuner.applyGuardrail(
                baseConfig,
                tuned,
                latest,
                new ReminderPolicyVersionInsight(ReminderPolicyAdjustmentDirection.LOOSER, 24, 0.04f, true),
                ReminderUserFeedbackSummary.empty(),
                replayReport,
                taskConfig
        );

        assertThat(result.rolledBack()).isTrue();
        assertThat(result.config()).isEqualTo(latest);
    }

    @Test
    void applyGuardrail_放松方向与上一版不一致_拒绝激进调整() {
        ReminderPolicyConfig latest = new ReminderPolicyConfig(24, 18, 3, 24, 60, 0.55f, 0.63f, 0.78f, 0.65f);
        ReminderPolicyConfig tuned = new ReminderPolicyConfig(28, 15, 4, 20, 90, 0.51f, 0.60f, 0.75f, 0.62f);
        ReminderReplayReport replayReport = new ReminderReplayReport(
                "default",
                java.time.Instant.parse("2026-03-01T00:00:00Z"),
                20,
                10,
                13,
                2,
                5,
                5,
                0.64f,
                0.66f,
                0.72f,
                java.util.Map.of(),
                java.util.List.of()
        );

        ReminderPolicyGuardrailResult result = tuner.applyGuardrail(
                baseConfig,
                tuned,
                latest,
                new ReminderPolicyVersionInsight(ReminderPolicyAdjustmentDirection.TIGHTER, 20, 0.03f, true),
                ReminderUserFeedbackSummary.empty(),
                replayReport,
                taskConfig
        );

        assertThat(result.adjusted()).isTrue();
        assertThat(result.config().dailyMaxReminders()).isEqualTo(latest.dailyMaxReminders());
        assertThat(result.config().defaultCooldownHours()).isEqualTo(latest.defaultCooldownHours());
        assertThat(result.config().minFinalScore()).isEqualTo(latest.minFinalScore());
    }
}
