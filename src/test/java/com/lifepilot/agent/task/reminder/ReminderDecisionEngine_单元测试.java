package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderDecisionEngine 单元测试。
 *
 * <p>验证候选检测、统一评分、静默时段、冷却期和每日上限等核心决策边界。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderDecisionEngine_单元测试 {

    private final ReminderDecisionEngine engine = new ReminderDecisionEngine();
    private final ReminderPolicyConfig config = new ReminderPolicyConfig();
    private final ZoneId zoneId = ZoneId.of("Asia/Shanghai");

    @Test
    void evaluateTopic_截止临近_返回标准提醒() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ReminderTopicSnapshot snapshot = new ReminderTopicSnapshot(
                "bill-payment",
                "缴纳账单",
                List.of(new ReminderSignal(
                        "sig-1",
                        ReminderSignalKind.DEADLINE,
                        0.92f,
                        0.90f,
                        3,
                        now.minus(Duration.ofHours(10)),
                        now.plus(Duration.ofHours(2)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "账单今晚截止"
                )),
                ReminderTopicState.empty()
        );
        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, zoneId, LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        ReminderDecision decision = engine.evaluateTopic(snapshot, context, config).orElseThrow();

        assertThat(decision.action()).isEqualTo(ReminderAction.NORMAL_PUSH);
        assertThat(decision.finalScore()).isGreaterThan(0.78f);
    }

    @Test
    void evaluateTopic_静默时段_跳过提醒() {
        Instant now = Instant.parse("2026-03-28T15:30:00Z");
        ReminderTopicSnapshot snapshot = new ReminderTopicSnapshot(
                "night-task",
                "睡前任务",
                List.of(new ReminderSignal(
                        "sig-2",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.85f,
                        3,
                        now.minus(Duration.ofHours(4)),
                        now.plus(Duration.ofHours(1)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "还剩 1 小时"
                )),
                ReminderTopicState.empty()
        );
        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, zoneId, LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        ReminderDecision decision = engine.evaluateTopic(snapshot, context, config).orElseThrow();

        assertThat(decision.action()).isEqualTo(ReminderAction.SKIP);
        assertThat(decision.reason()).contains("静默时段");
    }

    @Test
    void evaluateTopic_习惯窗口将到_延后到窗口提醒() {
        Instant now = Instant.parse("2026-03-28T11:30:00Z");
        ReminderTopicSnapshot snapshot = new ReminderTopicSnapshot(
                "weekly-plan",
                "周计划整理",
                List.of(new ReminderSignal(
                        "sig-3",
                        ReminderSignalKind.HABIT,
                        0.88f,
                        0.70f,
                        3,
                        now.minus(Duration.ofDays(2)),
                        null,
                        null,
                        20,
                        22,
                        0.0f,
                        true,
                        false,
                        "用户通常在晚上整理计划"
                )),
                ReminderTopicState.empty()
        );
        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, zoneId, LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        ReminderDecision decision = engine.evaluateTopic(snapshot, context, config).orElseThrow();

        assertThat(decision.action()).isEqualTo(ReminderAction.DEFER_TO_WINDOW);
        assertThat(decision.nextEvaluationAt()).isNotNull();
        assertThat(decision.nextEvaluationAt()).isAfter(now);
    }

    @Test
    void evaluateTopic_冷却期内_跳过提醒() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ReminderTopicSnapshot snapshot = new ReminderTopicSnapshot(
                "exercise",
                "晨间锻炼",
                List.of(new ReminderSignal(
                        "sig-4",
                        ReminderSignalKind.HABIT,
                        0.90f,
                        0.80f,
                        3,
                        now.minus(Duration.ofDays(3)),
                        null,
                        null,
                        10,
                        12,
                        0.0f,
                        true,
                        false,
                        "用户最近一直在坚持锻炼"
                )),
                new ReminderTopicState(
                        now.minus(Duration.ofHours(2)),
                        1, 0, 1, 0, 0, 0, false)
        );
        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, zoneId, LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        ReminderDecision decision = engine.evaluateTopic(snapshot, context, config).orElseThrow();

        assertThat(decision.action()).isEqualTo(ReminderAction.SKIP);
        assertThat(decision.reason()).contains("冷却");
    }

    @Test
    void evaluate_每日上限仅保留最高分提醒() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, zoneId, LocalTime.of(23, 0), LocalTime.of(8, 0), 2);

        ReminderTopicSnapshot high = new ReminderTopicSnapshot(
                "tax",
                "报税",
                List.of(new ReminderSignal(
                        "sig-high",
                        ReminderSignalKind.DEADLINE,
                        0.97f,
                        0.95f,
                        4,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofMinutes(30)),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "30 分钟后截止"
                )),
                ReminderTopicState.empty()
        );
        ReminderTopicSnapshot medium = new ReminderTopicSnapshot(
                "review",
                "代码复盘",
                List.of(new ReminderSignal(
                        "sig-medium",
                        ReminderSignalKind.COMMITMENT,
                        0.82f,
                        0.70f,
                        2,
                        now.minus(Duration.ofHours(30)),
                        null,
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "用户前天说过要复盘"
                )),
                ReminderTopicState.empty()
        );

        List<ReminderDecision> decisions = engine.evaluate(List.of(high, medium), context, config);

        long pushCount = decisions.stream()
                .filter(decision -> decision.action() == ReminderAction.NORMAL_PUSH
                        || decision.action() == ReminderAction.SOFT_PUSH)
                .count();

        ReminderDecision skipped = decisions.stream()
                .filter(decision -> decision.candidate().topicKey().equals("review"))
                .findFirst()
                .orElseThrow();

        assertThat(pushCount).isEqualTo(1);
        assertThat(skipped.action()).isEqualTo(ReminderAction.SKIP);
        assertThat(skipped.reason()).contains("今日主动提醒上限");
    }
}
