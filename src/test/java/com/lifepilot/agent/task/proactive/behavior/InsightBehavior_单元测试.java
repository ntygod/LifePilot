package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InsightBehavior_单元测试 {

    SemanticMemory semanticMemory;
    InsightBehavior behavior;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        behavior = new InsightBehavior(semanticMemory, null, null);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("insight");
    }

    @Test
    void detect_无语义记忆时返回空() {
        behavior = new InsightBehavior(null, null, null);
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_查询异常时不崩溃() {
        when(semanticMemory.findCurrentByType(any())).thenThrow(new RuntimeException("模拟异常"));
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_使用回退模板() {
        var candidate = new ProactiveCandidate("c1", "insight", "goal-trend",
                "新增目标: 健身", 0.5f, "新增目标", null);

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("健身");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
