package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderOpportunityPolicySelector 单元测试。
 *
 * <p>验证机会学习可以前移决定是否值得打扰用户。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderOpportunityPolicySelector_单元测试 {

    @Test
    void refine_收益低的轻提醒_会被抑制为跳过() {
        ReminderOpportunityPolicySelector selector = new ReminderOpportunityPolicySelector(
                new ReminderActionContextualBandit(0.15f, 6, 2, 1.0d),
                0.72f,
                0.34f,
                0.08f
        );
        ReminderDecision baseDecision = new ReminderDecision(
                new ReminderCandidate(
                        "topic-soft",
                        "周计划整理",
                        ReminderCandidateType.HABIT_WINDOW,
                        "sig-soft",
                        0.68f,
                        0.78f,
                        0.35f,
                        0.72f,
                        0.82f,
                        0.04f,
                        0.08f,
                        0.70f,
                        null,
                        "这是晚间习惯窗口"
                ),
                ReminderAction.SOFT_PUSH,
                null,
                "适合发送轻提醒"
        );
        ReminderDecision refined = selector.refine(
                baseDecision,
                new ReminderTopicState(null, 0, 2, 0, 0, 1, 0, false),
                new ReminderPolicyConfig(),
                lowYieldExamples()
        );

        assertThat(refined.action()).isEqualTo(ReminderAction.SKIP);
        assertThat(refined.reason()).contains("收益偏低");
    }

    @Test
    void refine_接近阈值的跳过_会被补发轻提醒() {
        ReminderOpportunityPolicySelector selector = new ReminderOpportunityPolicySelector(
                new ReminderActionContextualBandit(0.15f, 6, 2, 1.0d),
                0.72f,
                0.34f,
                0.08f
        );
        ReminderDecision baseDecision = new ReminderDecision(
                new ReminderCandidate(
                        "topic-upgrade",
                        "整理报销",
                        ReminderCandidateType.COMMITMENT_GAP,
                        "sig-upgrade",
                        0.60f,
                        0.70f,
                        0.44f,
                        0.76f,
                        0.88f,
                        0.02f,
                        0.03f,
                        0.52f,
                        null,
                        "你最近几次都提过这件事"
                ),
                ReminderAction.SKIP,
                ReminderSkipReason.LOW_SCORE,
                null,
                ReminderSkipReason.LOW_SCORE.label()
        );
        ReminderDecision refined = selector.refine(
                baseDecision,
                new ReminderTopicState(null, 0, 2, 1, 0, 0, 0, false),
                new ReminderPolicyConfig(),
                highYieldExamples()
        );

        assertThat(refined.action()).isEqualTo(ReminderAction.SOFT_PUSH);
        assertThat(refined.reason()).contains("补发轻提醒");
    }

    private List<ReminderActionTrainingExample> lowYieldExamples() {
        List<ReminderActionTrainingExample> examples = new ArrayList<>();
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.66f, 0.70f, 0.78f, 0.32f, 0.70f, 0.80f,
                0.04f, 0.08f, 0, 1, 0, 1, 0, 1, 0.18f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.69f, 0.72f, 0.80f, 0.34f, 0.72f, 0.82f,
                0.05f, 0.09f, 0, 1, 0, 1, 0, 1, 0.22f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.67f, 0.71f, 0.79f, 0.36f, 0.74f, 0.81f,
                0.05f, 0.08f, 1, 1, 0, 1, 0, 0, 0.20f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.NORMAL_PUSH,
                0.70f, 0.73f, 0.81f, 0.35f, 0.68f, 0.82f,
                0.05f, 0.08f, 1, 1, 0, 1, 0, 1, 0.16f
        ));
        examples.add(new ReminderActionTrainingExample(
                "DUE_SOON", ReminderAction.NORMAL_PUSH,
                0.90f, 0.88f, 0.86f, 0.92f, 0.58f, 0.95f,
                0.00f, 0.02f, 0, 0, 0, 0, 0, 0, 0.96f
        ));
        examples.add(new ReminderActionTrainingExample(
                "DUE_SOON", ReminderAction.SOFT_PUSH,
                0.86f, 0.84f, 0.82f, 0.88f, 0.56f, 0.92f,
                0.00f, 0.03f, 0, 0, 0, 0, 0, 0, 0.74f
        ));
        return List.copyOf(examples);
    }

    private List<ReminderActionTrainingExample> highYieldExamples() {
        List<ReminderActionTrainingExample> examples = new ArrayList<>();
        examples.add(new ReminderActionTrainingExample(
                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                0.53f, 0.62f, 0.72f, 0.42f, 0.78f, 0.86f,
                0.02f, 0.03f, 0, 2, 1, 0, 0, 0, 0.92f
        ));
        examples.add(new ReminderActionTrainingExample(
                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                0.55f, 0.64f, 0.74f, 0.45f, 0.80f, 0.88f,
                0.02f, 0.03f, 0, 2, 1, 0, 0, 0, 0.90f
        ));
        examples.add(new ReminderActionTrainingExample(
                "COMMITMENT_GAP", ReminderAction.SOFT_PUSH,
                0.52f, 0.63f, 0.71f, 0.40f, 0.79f, 0.87f,
                0.03f, 0.04f, 0, 1, 1, 0, 0, 0, 0.88f
        ));
        examples.add(new ReminderActionTrainingExample(
                "COMMITMENT_GAP", ReminderAction.NORMAL_PUSH,
                0.56f, 0.65f, 0.73f, 0.46f, 0.74f, 0.88f,
                0.02f, 0.04f, 0, 1, 1, 0, 0, 0, 0.82f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.74f, 0.76f, 0.81f, 0.40f, 0.72f, 0.83f,
                0.02f, 0.04f, 0, 2, 1, 0, 0, 0, 0.80f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.NORMAL_PUSH,
                0.76f, 0.78f, 0.82f, 0.44f, 0.70f, 0.84f,
                0.02f, 0.04f, 0, 1, 1, 0, 0, 0, 0.78f
        ));
        return List.copyOf(examples);
    }
}
