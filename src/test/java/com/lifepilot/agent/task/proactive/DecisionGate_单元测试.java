package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.memory.store.procedural.PreferenceRule;
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
                0.8f, "proactive-engine", 5, now, now, null, null);
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
        return new ContextPacket(USER, now, ZONE, qStart, qEnd, sent, max, focus, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }

    private ContextPacket ctxWithBoundary(Instant now, BoundaryState bs, FocusMode fm) {
        return new ContextPacket(USER, now, ZONE, null, null, 0, 5,
                null, null, 30, null, null, bs, fm);
    }

    // ── Boundary / Focus（proactive-boundary-training spec） ──

    @Test
    void boundary内低分候选可被NOTIFY() {
        // 候选分数 0.4（默认 scoreToLevel → QUEUE），boundary 内 NOTIFY 阈值降到 0.35 → 应 NOTIFY
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.IN_BOUNDARY, FocusMode.NORMAL);
        // 插件建议 NOTIFY 作为上限 — 分数只影响 boundary 动态映射
        var actions = List.of(action(0.4f, DeliveryLevel.NOTIFY));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void boundary外中等分候选被降为QUEUE() {
        // 候选分数 0.6（默认 NOTIFY），boundary 外 NOTIFY 阈值升到 0.75 → 动态映射为 QUEUE
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL);
        var actions = List.of(action(0.6f, DeliveryLevel.NOTIFY));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        // 0.6 >= QUEUE(0.3) 但 < 0.75 → QUEUE
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.QUEUE);
    }

    @Test
    void focus_mode下NOTIFY降为QUEUE() {
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.UNKNOWN, FocusMode.FOCUS_MODE);
        var actions = List.of(action(0.6f, DeliveryLevel.NOTIFY));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.QUEUE);
    }

    @Test
    void focus_mode下INTERRUPT保留() {
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.UNKNOWN, FocusMode.FOCUS_MODE);
        var actions = List.of(action(0.9f, DeliveryLevel.INTERRUPT));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        // INTERRUPT 在 focus_mode 下不降级（真紧急保留打断能力）
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    @Test
    void boundary内focus_mode叠加NOTIFY降QUEUE() {
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.IN_BOUNDARY, FocusMode.FOCUS_MODE);
        var actions = List.of(action(0.4f, DeliveryLevel.NOTIFY));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        assertThat(result).hasSize(1);
        // boundary 内阈值 0.35 → NOTIFY；focus_mode 再降 → QUEUE
        assertThat(result.getFirst().level()).isEqualTo(DeliveryLevel.QUEUE);
    }

    @Test
    void boundary外低分候选仍然SILENT() {
        var ctx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL);
        var actions = List.of(action(0.25f, DeliveryLevel.NOTIFY));

        var result = new DecisionGate(null, null).evaluate(actions, ctx);

        // 0.25 < QUEUE(0.3) → SILENT 被跳过
        assertThat(result).isEmpty();
    }

    @Test
    void 动态scoreToLevel_boundary内放宽阈值() {
        var gate = new DecisionGate(null, null);
        var inCtx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.IN_BOUNDARY, FocusMode.NORMAL);

        // 0.4 在 boundary 内达到 NOTIFY 阈值（0.35）
        assertThat(gate.scoreToLevel(0.4f, inCtx)).isEqualTo(DeliveryLevel.NOTIFY);
        // 0.6 在 boundary 内达到 INTERRUPT 阈值（0.55）
        assertThat(gate.scoreToLevel(0.6f, inCtx)).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    @Test
    void 动态scoreToLevel_boundary外收紧阈值() {
        var gate = new DecisionGate(null, null);
        var outCtx = ctxWithBoundary(Instant.parse("2026-05-09T06:00:00Z"),
                BoundaryState.OUT_OF_BOUNDARY, FocusMode.NORMAL);

        // 0.6 在 boundary 外不达 NOTIFY 阈值（0.75）→ QUEUE
        assertThat(gate.scoreToLevel(0.6f, outCtx)).isEqualTo(DeliveryLevel.QUEUE);
        // 0.8 在 boundary 外达到 NOTIFY 但不达 INTERRUPT（0.95）
        assertThat(gate.scoreToLevel(0.8f, outCtx)).isEqualTo(DeliveryLevel.NOTIFY);
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
