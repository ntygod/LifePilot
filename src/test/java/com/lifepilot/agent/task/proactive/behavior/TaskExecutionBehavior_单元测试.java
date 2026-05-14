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

class TaskExecutionBehavior_单元测试 {

    ProactiveMemoryBridge memoryBridge;
    TrustUpgradeService trustUpgradeService;
    TaskExecutionBehavior behavior;

    @BeforeEach
    void setUp() {
        memoryBridge = mock(ProactiveMemoryBridge.class);
        trustUpgradeService = mock(TrustUpgradeService.class);
        behavior = new TaskExecutionBehavior(memoryBridge, trustUpgradeService);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("task-execution");
    }

    @Test
    void detect_真实条件评估未实现时始终返回空() {
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_B级建议确认() {
        when(trustUpgradeService.getLevel("u1", "task-execution")).thenReturn(AutonomyLevel.B);
        var goal = new GoalView("e1", "等降到300以下", "价格低于300时通知", 0.6f, 3,
                Instant.now().minusSeconds(86400), 0, null, Map.of());
        var candidate = new ProactiveCandidate("c1", "task-execution", "exec-e1",
                "等降到300以下", 0.55f, "条件可能已满足", goal);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("需要我帮你处理吗");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void reason_C级自动执行() {
        when(trustUpgradeService.getLevel("u1", "task-execution")).thenReturn(AutonomyLevel.C);
        var goal = new GoalView("e1", "等降到300以下", "价格低于300时通知", 0.6f, 3,
                Instant.now().minusSeconds(86400), 0, null, Map.of());
        var candidate = new ProactiveCandidate("c1", "task-execution", "exec-e1",
                "等降到300以下", 0.55f, "条件可能已满足", goal);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("已帮你处理");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.INTERRUPT);
        verify(memoryBridge).markGoalFulfilled("e1");
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
