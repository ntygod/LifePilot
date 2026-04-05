package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ReminderPolicyVersionService 单元测试。
 *
 * <p>验证策略版本服务能够复用同配置版本，并为新配置递增版本号。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderPolicyVersionService_单元测试 {

    private ReminderPolicyVersionRepository repository;
    private ReminderPolicyVersionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReminderPolicyVersionRepository.class);
        service = new ReminderPolicyVersionService(repository);
    }

    @Test
    void resolve_相同配置签名复用已有版本() {
        Instant now = Instant.parse("2026-03-28T10:30:00Z");
        ReminderPolicyConfig config = new ReminderPolicyConfig();
        ReminderUserFeedbackSummary summary = ReminderUserFeedbackSummary.empty();
        ReminderPolicyVersionRecord existing = new ReminderPolicyVersionRecord(
                "policy-v1",
                "default",
                1,
                "same-signature",
                "{\"dailyMaxReminders\":3}",
                "base",
                "{}",
                now,
                now
        );

        when(repository.findByUserIdAndConfigSignature(eq("default"), anyString()))
                .thenReturn(Optional.of(existing));

        ReminderPolicyVersionRecord resolved = service.resolve("default", config, summary, null, now);

        assertThat(resolved).isEqualTo(existing);
        verify(repository, never()).save(any());
        verify(repository, never()).nextVersion(anyString());
    }

    @Test
    void resolve_新配置创建递增版本() {
        Instant now = Instant.parse("2026-03-28T10:35:00Z");
        ReminderPolicyConfig config = new ReminderPolicyConfig(24, 18, 2, 30, 90, 0.60f, 0.68f, 0.82f, 0.66f);
        ReminderUserFeedbackSummary summary = new ReminderUserFeedbackSummary(3, 1, 0, 0, 0);
        ReminderReplayReport replayReport = new ReminderReplayReport(
                "default",
                now.minusSeconds(86400),
                20,
                14,
                13,
                2,
                1,
                5,
                0.74f,
                0.72f,
                0.79f,
                java.util.Map.of("NORMAL_PUSH->SOFT_PUSH", 5),
                java.util.List.of()
        );

        when(repository.findByUserIdAndConfigSignature(eq("default"), anyString()))
                .thenReturn(Optional.empty());
        when(repository.nextVersion("default")).thenReturn(4);

        ReminderPolicyVersionRecord resolved = service.resolve("default", config, summary, replayReport, now);

        assertThat(resolved.version()).isEqualTo(4);
        assertThat(resolved.source()).isEqualTo("feedback+replay");
        verify(repository, times(1)).save(any(ReminderPolicyVersionRecord.class));
    }

    @Test
    void parseConfig_能够还原版本中的策略配置() {
        ReminderPolicyVersionRecord record = new ReminderPolicyVersionRecord(
                "policy-v9",
                "default",
                9,
                "sig",
                """
                {"dueSoonThresholdHours":30,"commitmentGapThresholdHours":12,"dailyMaxReminders":2,
                 "defaultCooldownHours":36,"preferredWindowLookaheadMinutes":90,
                 "minFinalScore":0.61,"softPushThreshold":0.69,"strongPushThreshold":0.84,
                 "anomalyThreshold":0.71}
                """,
                "feedback",
                "{}",
                Instant.parse("2026-03-28T10:35:00Z"),
                Instant.parse("2026-03-28T10:35:00Z")
        );

        ReminderPolicyConfig config = service.parseConfig(record);

        assertThat(config.dueSoonThresholdHours()).isEqualTo(30);
        assertThat(config.commitmentGapThresholdHours()).isEqualTo(12);
        assertThat(config.dailyMaxReminders()).isEqualTo(2);
        assertThat(config.defaultCooldownHours()).isEqualTo(36);
        assertThat(config.minFinalScore()).isEqualTo(0.61f);
    }

    @Test
    void parseInsight_能够识别历史回放的方向和收益变化() {
        ReminderPolicyVersionRecord record = new ReminderPolicyVersionRecord(
                "policy-v10",
                "default",
                10,
                "sig",
                "{\"dailyMaxReminders\":3}",
                "feedback+replay",
                """
                {"feedback":{"actedCount":3,"snoozedCount":1,"dismissedCount":0,"notRelevantCount":0,"mutedTopicCount":0,"totalFeedbackCount":4},
                 "replay":{"sampleCount":18,"promotedCount":5,"suppressedCount":2,
                 "historicalEstimatedPushRewardMean":0.62,"replayedEstimatedPushRewardMean":0.71,"expectedDelta":0.09}}
                """,
                Instant.parse("2026-03-28T10:35:00Z"),
                Instant.parse("2026-03-28T10:35:00Z")
        );

        ReminderPolicyVersionInsight insight = service.parseInsight(record);

        assertThat(insight.direction()).isEqualTo(ReminderPolicyAdjustmentDirection.LOOSER);
        assertThat(insight.sampleCount()).isEqualTo(18);
        assertThat(insight.expectedDelta()).isEqualTo(0.09f);
        assertThat(insight.hasReplay()).isTrue();
    }
}
