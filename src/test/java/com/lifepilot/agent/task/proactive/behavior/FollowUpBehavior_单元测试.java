package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FollowUpBehavior_单元测试 {

    ProactiveMemoryBridge memoryBridge;
    FollowUpBehavior behavior;

    @BeforeEach
    void setUp() {
        memoryBridge = mock(ProactiveMemoryBridge.class);
        behavior = new FollowUpBehavior(memoryBridge, null, null, null);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("follow-up");
    }

    @Test
    void detect_有活跃目标时返回候选() {
        var goal = new GoalView("e1", "买耳机", "想买一副降噪耳机", 0.6f, 5,
                Instant.now().minusSeconds(3 * 86400), 2, null, Map.of());
        when(memoryBridge.getActiveGoals()).thenReturn(List.of(goal));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().behaviorName()).isEqualTo("follow-up");
    }

    @Test
    void detect_无目标时返回空() {
        when(memoryBridge.getActiveGoals()).thenReturn(List.of());
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_刚创建的目标不追问() {
        var goal = new GoalView("e1", "买耳机", null, 0.5f, 0,
                Instant.now().minusSeconds(3600), 0, null, Map.of());
        when(memoryBridge.getActiveGoals()).thenReturn(List.of(goal));

        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_追问次数过多不追问() {
        var goal = new GoalView("e1", "买耳机", null, 0.5f, 10,
                Instant.now().minusSeconds(10 * 86400), 5, null, Map.of());
        when(memoryBridge.getActiveGoals()).thenReturn(List.of(goal));

        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_使用回退模板生成追问() {
        var goal = new GoalView("e1", "买耳机", null, 0.5f, 3,
                Instant.now().minusSeconds(3 * 86400), 1, null, Map.of());
        when(memoryBridge.enrichGoalContext("e1", "买耳机")).thenReturn("");
        var candidate = new ProactiveCandidate("c1", "follow-up", "goal-e1",
                "买耳机", 0.5f, "活跃目标", goal);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("买耳机");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void reason_不递增追问计数_由onDelivered处理() {
        var goal = new GoalView("e1", "买耳机", null, 0.5f, 3,
                Instant.now().minusSeconds(3 * 86400), 1, null, Map.of());
        when(memoryBridge.enrichGoalContext("e1", "买耳机")).thenReturn("");
        var candidate = new ProactiveCandidate("c1", "follow-up", "goal-e1",
                "买耳机", 0.5f, "活跃目标", goal);

        behavior.reason(List.of(candidate), testCtx());

        // reason 阶段不递增 checkCount，避免被 DecisionGate 拦截后浪费配额
        verify(memoryBridge, never()).incrementCheckCount(any());
    }

    @Test
    void onDelivered_递增追问计数() {
        var goal = new GoalView("e1", "买耳机", null, 0.5f, 3,
                Instant.now().minusSeconds(3 * 86400), 1, null, Map.of());
        var candidate = new ProactiveCandidate("c1", "follow-up", "goal-e1",
                "买耳机", 0.5f, "活跃目标", goal);
        var action = new ProactiveAction(candidate, "进展如何？", DeliveryLevel.NOTIFY, goal);
        var result = new DeliveryResult("n1", DeliveryLevel.NOTIFY, Instant.now());

        behavior.onDelivered(action, result);

        verify(memoryBridge).incrementCheckCount("e1");
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
