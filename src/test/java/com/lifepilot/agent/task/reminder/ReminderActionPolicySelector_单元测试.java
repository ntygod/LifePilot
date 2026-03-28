package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderActionPolicySelector 单元测试。
 *
 * <p>验证学习性动作策略能在标准提醒和轻提醒之间做偏好切换。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderActionPolicySelector_单元测试 {

    private final ReminderActionPolicySelector selector = new ReminderActionPolicySelector();
    private final ReminderPolicyConfig config = new ReminderPolicyConfig();

    @Test
    void refine_标准提醒历史表现差_降级为轻提醒() {
        ReminderCandidate candidate = new ReminderCandidate(
                "topic-1",
                "周计划整理",
                ReminderCandidateType.HABIT_WINDOW,
                "sig-1",
                0.82f,
                0.85f,
                0.62f,
                0.68f,
                0.90f,
                0.00f,
                0.02f,
                0.81f,
                null,
                "稳定习惯窗口"
        );
        ReminderDecision baseDecision = new ReminderDecision(
                candidate,
                ReminderAction.NORMAL_PUSH,
                null,
                "命中高优先级提醒条件"
        );
        ReminderActionPolicyProfile profile = new ReminderActionPolicyProfile(List.of(
                new ReminderActionPerformanceStats("HABIT_WINDOW", ReminderAction.NORMAL_PUSH, 6, 1, 0, 3, 2, 0),
                new ReminderActionPerformanceStats("HABIT_WINDOW", ReminderAction.SOFT_PUSH, 5, 2, 2, 0, 0, 1)
        ));

        ReminderDecision refined = selector.refine(baseDecision, config, profile);

        assertThat(refined.action()).isEqualTo(ReminderAction.SOFT_PUSH);
        assertThat(refined.reason()).contains("结合近期反馈偏好调整");
    }

    @Test
    void refine_轻提醒历史表现更差_升级为标准提醒() {
        ReminderCandidate candidate = new ReminderCandidate(
                "topic-2",
                "缴纳账单",
                ReminderCandidateType.DUE_SOON,
                "sig-2",
                0.90f,
                0.94f,
                0.82f,
                0.60f,
                0.96f,
                0.00f,
                0.00f,
                0.80f,
                null,
                "今天截止"
        );
        ReminderDecision baseDecision = new ReminderDecision(
                candidate,
                ReminderAction.SOFT_PUSH,
                null,
                "适合发送轻提醒"
        );
        ReminderActionPolicyProfile profile = new ReminderActionPolicyProfile(List.of(
                new ReminderActionPerformanceStats("DUE_SOON", ReminderAction.SOFT_PUSH, 4, 0, 1, 2, 1, 0),
                new ReminderActionPerformanceStats("DUE_SOON", ReminderAction.NORMAL_PUSH, 5, 3, 1, 0, 0, 1)
        ));

        ReminderDecision refined = selector.refine(baseDecision, config, profile);

        assertThat(refined.action()).isEqualTo(ReminderAction.NORMAL_PUSH);
    }

    @Test
    void refine_存在上下文训练样本_按场景选择更合适动作() {
        ReminderActionPolicySelector banditSelector = new ReminderActionPolicySelector(
                new ReminderActionContextualBandit(0.15f, 6, 2, 1.0d)
        );
        ReminderCandidate candidate = new ReminderCandidate(
                "topic-3",
                "周计划整理",
                ReminderCandidateType.HABIT_WINDOW,
                "sig-3",
                0.78f,
                0.80f,
                0.48f,
                0.74f,
                0.88f,
                0.03f,
                0.06f,
                0.77f,
                null,
                "这是用户稳定的晚间整理窗口"
        );
        ReminderDecision baseDecision = new ReminderDecision(
                candidate,
                ReminderAction.NORMAL_PUSH,
                null,
                "命中高优先级提醒条件"
        );
        ReminderTopicState topicState = new ReminderTopicState(
                null,
                0,
                2,
                1,
                0,
                0,
                0,
                false
        );
        ReminderActionPolicyProfile profile = new ReminderActionPolicyProfile(List.of(
                new ReminderActionPerformanceStats("HABIT_WINDOW", ReminderAction.NORMAL_PUSH, 2, 1, 0, 1, 0, 0),
                new ReminderActionPerformanceStats("HABIT_WINDOW", ReminderAction.SOFT_PUSH, 2, 1, 1, 0, 0, 0)
        ));

        ReminderDecision refined = banditSelector.refine(
                baseDecision,
                topicState,
                config,
                profile,
                buildBanditExamples()
        );

        assertThat(refined.action()).isEqualTo(ReminderAction.SOFT_PUSH);
        assertThat(refined.reason()).contains("上下文反馈策略调整");
    }

    private List<ReminderActionTrainingExample> buildBanditExamples() {
        List<ReminderActionTrainingExample> examples = new ArrayList<>();
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.75f, 0.78f, 0.82f, 0.45f, 0.76f, 0.85f,
                0.02f, 0.05f, 0, 2, 1, 0, 1, 0, 0.92f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.77f, 0.79f, 0.80f, 0.48f, 0.72f, 0.83f,
                0.01f, 0.04f, 0, 1, 1, 0, 1, 0, 0.88f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.SOFT_PUSH,
                0.73f, 0.75f, 0.81f, 0.42f, 0.70f, 0.84f,
                0.03f, 0.06f, 1, 2, 1, 0, 0, 0, 0.90f
        ));
        examples.add(new ReminderActionTrainingExample(
                "HABIT_WINDOW", ReminderAction.NORMAL_PUSH,
                0.76f, 0.77f, 0.80f, 0.46f, 0.70f, 0.84f,
                0.02f, 0.06f, 1, 1, 0, 1, 0, 0, 0.18f
        ));
        examples.add(new ReminderActionTrainingExample(
                "DUE_SOON", ReminderAction.NORMAL_PUSH,
                0.92f, 0.90f, 0.88f, 0.93f, 0.58f, 0.96f,
                0.00f, 0.02f, 0, 0, 0, 0, 0, 0, 0.96f
        ));
        examples.add(new ReminderActionTrainingExample(
                "DUE_SOON", ReminderAction.NORMAL_PUSH,
                0.90f, 0.88f, 0.86f, 0.91f, 0.60f, 0.95f,
                0.00f, 0.02f, 0, 0, 0, 0, 0, 0, 0.94f
        ));
        examples.add(new ReminderActionTrainingExample(
                "DUE_SOON", ReminderAction.SOFT_PUSH,
                0.89f, 0.87f, 0.85f, 0.92f, 0.56f, 0.95f,
                0.00f, 0.02f, 0, 0, 0, 1, 0, 0, 0.12f
        ));
        return List.copyOf(examples);
    }
}
