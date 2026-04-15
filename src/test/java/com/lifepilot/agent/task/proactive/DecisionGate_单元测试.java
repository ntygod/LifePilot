package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.memory.procedural.PreferenceRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionGate_单元测试 {

    private static final String USER = "u1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // ── 硬边界 ──

    @Test
    void 安静时段内全部过滤() {
        // 23:30 在安静时段 23:00-08:00 内
        var ctx = ctx(Instant.parse("2026-04-14T15:30:00Z"), // UTC 15:30 = CST 23:30
                LocalTime.of(23, 0), LocalTime.of(8, 0), 0, 5, null);
        var actions = List.of(action(0.8f));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void 全屏模式全部过滤() {
        var focus = new ReminderFocusState("game.exe", "Game", true, 0, Instant.now());
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"), // CST 14:00
                null, null, 0, 5, focus);
        var actions = List.of(action(0.8f));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void 每日上限已满时过滤() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 5, 5, null);  // sent=5, max=5
        var actions = List.of(action(0.8f));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).isEmpty();
    }

    // ── 软约束 ──

    @Test
    void 编码中INTERRUPT降级为NOTIFY() {
        var focus = new ReminderFocusState("code.exe", "VS Code - project", false, 0, Instant.now());
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 0, 5, focus);
        var actions = List.of(action(0.8f, DeliveryLevel.INTERRUPT));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void 多个候选按分数降序排列() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 0, 5, null);
        var actions = List.of(
                action(0.5f, DeliveryLevel.NOTIFY),
                action(0.8f, DeliveryLevel.INTERRUPT),
                action(0.6f, DeliveryLevel.NOTIFY)
        );

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result.getFirst().action().candidate().score()).isEqualTo(0.8f);
    }

    @Test
    void 超出每日额度截断低分() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 4, 5, null);  // 剩余 1 个额度
        var actions = List.of(
                action(0.5f, DeliveryLevel.NOTIFY),
                action(0.8f, DeliveryLevel.INTERRUPT)
        );

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().action().candidate().score()).isEqualTo(0.8f);
    }

    @Test
    void SILENT级别不占额度() {
        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),
                null, null, 0, 5, null);
        var silentAction = action(0.2f, DeliveryLevel.SILENT);
        var notifyAction = action(0.6f, DeliveryLevel.NOTIFY);

        var result = new DecisionGate(null, null).evaluate(List.of(silentAction, notifyAction), ctx);

        // SILENT 被跳过，只有 NOTIFY 通过
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    // ── 投递级别映射 ──

    @Test
    void 分数到投递级别映射() {
        assertThat(DecisionGate.scoreToLevel(0.2f)).isEqualTo(DeliveryLevel.SILENT);
        assertThat(DecisionGate.scoreToLevel(0.4f)).isEqualTo(DeliveryLevel.QUEUE);
        assertThat(DecisionGate.scoreToLevel(0.6f)).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(DecisionGate.scoreToLevel(0.8f)).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    @Test
    void 边界值映射() {
        assertThat(DecisionGate.scoreToLevel(0.3f)).isEqualTo(DeliveryLevel.QUEUE);
        assertThat(DecisionGate.scoreToLevel(0.5f)).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(DecisionGate.scoreToLevel(0.7f)).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    // ── 自主度与偏好约束 ──

    @Test
    void 自主度A级限制INTERRUPT降级为NOTIFY() {
        // given — TrustUpgradeService mock 返回 A 级自主度
        var trustService = mock(TrustUpgradeService.class);
        when(trustService.getLevel(USER, "reminder")).thenReturn(AutonomyLevel.A);

        var ctx = ctx(Instant.parse("2026-04-14T06:00:00Z"),  // CST 14:00
                null, null, 0, 5, null);
        var actions = List.of(action(0.8f, DeliveryLevel.INTERRUPT));

        // when
        var result = new DecisionGate(trustService, null).evaluate(actions, ctx);

        // then — A 级自主度下 INTERRUPT 应降为 NOTIFY
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void 偏好低时NOTIFY降为QUEUE() {
        // given — ProactiveMemoryBridge mock 返回低偏好值
        var memoryBridge = mock(ProactiveMemoryBridge.class);

        // 当前时间 CST 14:00 → TimeSlotResolver 解析为 "afternoon"
        var now = Instant.parse("2026-04-14T06:00:00Z");
        var lowPreference = new PreferenceRule(
                "r1", "proactive-timing", "afternoon", "0.1",
                0.8f, "proactive-engine", 5, now, now);
        when(memoryBridge.getPreferences("proactive-timing"))
                .thenReturn(List.of(lowPreference));

        var ctx = ctx(now, null, null, 0, 5, null);
        var actions = List.of(action(0.6f, DeliveryLevel.NOTIFY));

        // when
        var result = new DecisionGate(null, memoryBridge).evaluate(actions, ctx);

        // then — 偏好低于阈值，NOTIFY 应降为 QUEUE
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.QUEUE);
    }

    // ── helpers ──

    private ContextPacket ctx(Instant now, LocalTime qStart, LocalTime qEnd,
                              int sent, int max, ReminderFocusState focus) {
        return new ContextPacket(USER, now, ZONE, qStart, qEnd, sent, max, focus, null, 30, null, null);
    }

    private ProactiveAction action(float score) {
        return action(score, DecisionGate.scoreToLevel(score));
    }

    private ProactiveAction action(float score, DeliveryLevel level) {
        var candidate = new ProactiveCandidate(
                "c-" + score, "reminder", "topic-" + score,
                "Title", score, "test", null);
        return new ProactiveAction(candidate, "内容", level, null);
    }
}
