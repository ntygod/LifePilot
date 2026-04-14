package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutionBehavior_单元测试 {

    IntentMemoryService intentMemoryService;
    TrustUpgradeService trustUpgradeService;
    TaskExecutionBehavior behavior;

    @BeforeEach
    void setUp() {
        intentMemoryService = mock(IntentMemoryService.class);
        trustUpgradeService = mock(TrustUpgradeService.class);
        behavior = new TaskExecutionBehavior(intentMemoryService, trustUpgradeService);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("task-execution");
    }

    @Test
    void detect_真实条件评估未实现时始终返回空() {
        // 当前版本未对接外部数据源，detect 始终返回空，避免无依据的自主行动
        var intent = new IntentRecord("i1", "u1", IntentType.CONDITIONAL, "等降到300以下",
                "价格<300", "sess-1", IntentStatus.ACTIVE, 3,
                Instant.now().minusSeconds(10 * 86400), null, null, null, Instant.now());
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_B级建议确认() {
        when(trustUpgradeService.getLevel("u1", "task-execution")).thenReturn(AutonomyLevel.B);
        var intent = new IntentRecord("i1", "u1", IntentType.CONDITIONAL, "等降到300以下",
                "价格<300", null, IntentStatus.ACTIVE, 3,
                Instant.now().minusSeconds(86400), null, null, null, Instant.now());
        var candidate = new ProactiveCandidate("c1", "task-execution", "exec-i1",
                "等降到300以下", 0.55f, "条件可能已满足", intent);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("需要我帮你处理吗");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void reason_C级自动执行() {
        when(trustUpgradeService.getLevel("u1", "task-execution")).thenReturn(AutonomyLevel.C);
        var intent = new IntentRecord("i1", "u1", IntentType.CONDITIONAL, "等降到300以下",
                "价格<300", null, IntentStatus.ACTIVE, 3,
                Instant.now().minusSeconds(86400), null, null, null, Instant.now());
        var candidate = new ProactiveCandidate("c1", "task-execution", "exec-i1",
                "等降到300以下", 0.55f, "条件可能已满足", intent);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("已帮你处理");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.INTERRUPT);
        verify(intentMemoryService).fulfillIntent("i1");
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null);
    }
}
