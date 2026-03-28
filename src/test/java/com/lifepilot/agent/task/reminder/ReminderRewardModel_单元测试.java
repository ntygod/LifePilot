package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderRewardModel 单元测试。
 *
 * <p>验证隐式完成去因分和最终奖励值的基本行为。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
class ReminderRewardModel_单元测试 {

    @Test
    void inferAttributionScore_快速工作流完成_分数高于延迟语义完成() {
        Instant decidedAt = Instant.parse("2026-03-29T00:00:00Z");
        float workflowScore = ReminderRewardModel.inferAttributionScore(
                ReminderOutcomeEvidenceSource.WORKFLOW_INSTANCE,
                0.92f,
                decidedAt,
                decidedAt.plusSeconds(900),
                "DUE_SOON"
        );
        float semanticScore = ReminderRewardModel.inferAttributionScore(
                ReminderOutcomeEvidenceSource.SEMANTIC_STATE,
                0.88f,
                decidedAt,
                decidedAt.plusSeconds(48 * 3600),
                "COMMITMENT_GAP"
        );

        assertThat(workflowScore).isGreaterThan(semanticScore);
        assertThat(workflowScore).isGreaterThan(0.8f);
    }

    @Test
    void rewardFor_隐式完成采用去因奖励而不是固定满分() {
        float reward = ReminderRewardModel.rewardFor(null, "UNREAD", 0.76f);

        assertThat(reward).isEqualTo(ReminderRewardModel.implicitActedReward(0.76f));
        assertThat(reward).isLessThan(1.0f);
        assertThat(reward).isGreaterThan(0.58f);
    }

    @Test
    void rewardFor_显式已处理仍保持最高奖励() {
        float reward = ReminderRewardModel.rewardFor("ACTED", "READ", 0.65f);

        assertThat(reward).isEqualTo(1.0f);
    }
}
