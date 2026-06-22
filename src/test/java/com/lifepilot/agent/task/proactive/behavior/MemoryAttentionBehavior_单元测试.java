package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.DeliveryLevel;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionItem;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService.AttentionKind;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryAttentionBehavior_单元测试 {

    MemoryAttentionService memoryAttentionService;
    MemoryAttentionBehavior behavior;

    @BeforeEach
    void setUp() {
        memoryAttentionService = mock(MemoryAttentionService.class);
        behavior = new MemoryAttentionBehavior(memoryAttentionService);
    }

    @Test
    void 插件名称和分层() {
        assertThat(behavior.name()).isEqualTo("memory-attention");
        assertThat(behavior.layer()).isEqualTo(BehaviorLayer.FACT_DRIVEN);
    }

    @Test
    void detect_注意力项生成候选并跳过EVOLVING() {
        when(memoryAttentionService.computeAttention(MemoryReadFilter.userMemory(), 5)).thenReturn(List.of(
                item(AttentionKind.DUE_SOON, "g1", "述职报告", 0.8f, null),
                item(AttentionKind.EVOLVING, "g2", "演进目标", 0.7f, null)
        ));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().behaviorName()).isEqualTo("memory-attention");
        assertThat(candidates.getFirst().topicKey()).isEqualTo("memory-attention:due_soon:g1");
        assertThat(candidates.getFirst().score()).isEqualTo(0.8f);
    }

    @Test
    void reason_截止类高分使用NOTIFY() {
        var item = item(AttentionKind.DUE_SOON, "g1", "述职报告", 0.8f, null);
        var candidate = candidate(item);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(actions.getFirst().content()).contains("要现在看一下吗");
    }

    @Test
    void reason_停滞和关联只入队列() {
        var neglected = candidate(item(AttentionKind.NEGLECTED, "g1", "学小提琴", 0.8f, null));
        var connection = candidate(item(AttentionKind.CONNECTION, "g2", "网易", 0.8f,
                List.of("学编程 --关联--> 网易")));

        var actions = behavior.reason(List.of(neglected, connection), testCtx());

        assertThat(actions).hasSize(2);
        assertThat(actions).allSatisfy(a -> assertThat(a.suggestedLevel()).isEqualTo(DeliveryLevel.QUEUE));
        assertThat(actions.get(1).content()).contains("我注意到一个关联");
    }

    private AttentionItem item(AttentionKind kind, String id, String name, float score, List<String> pathLabels) {
        return new AttentionItem(id, name, "GOAL", kind, score,
                "原因-" + name, Instant.now().plusSeconds(3600), null, pathLabels);
    }

    private com.lifepilot.agent.task.proactive.ProactiveCandidate candidate(AttentionItem item) {
        return new com.lifepilot.agent.task.proactive.ProactiveCandidate(
                "c-" + item.entityId(),
                "memory-attention",
                "memory-attention:" + item.kind().name().toLowerCase(java.util.Locale.ROOT) + ":" + item.entityId(),
                item.name(),
                item.score(),
                item.reason(),
                item);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
