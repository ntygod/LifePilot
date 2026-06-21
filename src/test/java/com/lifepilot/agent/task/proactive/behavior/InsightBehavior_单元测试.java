package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InsightBehavior_单元测试 {

    SemanticMemory semanticMemory;
    GenerationRouter generationRouter;
    PromptRegistry promptRegistry;
    InsightBehavior behavior;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("prompt");
        when(generationRouter.call(eq("chat"), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any(Duration.class)))
                .thenReturn(llmResponse("最近你对健身的关注更明确了，可以顺势定个小目标。"));
        behavior = new InsightBehavior(semanticMemory, generationRouter, promptRegistry);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("insight");
    }

    @Test
    void 构造器拒绝缺少语义记忆() {
        assertThatThrownBy(() -> new InsightBehavior(null, generationRouter, promptRegistry))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("语义记忆不能为空");
    }

    @Test
    void detect_查询异常时不崩溃() {
        when(semanticMemory.findCurrentByType(any())).thenThrow(new RuntimeException("模拟异常"));
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_使用LLM生成洞察() {
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

    private LlmResponse llmResponse(String content) {
        return new LlmResponse(content, null, null, List.of(), Map.of(),
                1, 1, null, 0, "mock", "mock", 1L, false);
    }
}
