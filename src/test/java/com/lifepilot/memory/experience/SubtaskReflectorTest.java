package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubtaskReflector 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class SubtaskReflectorTest {

    @Test
    void reflect_应使用子任务配置中的超时覆盖() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);

        var properties = new MemoryProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);
        properties.getExperience().getSubtask().setLlmTimeoutSeconds(120);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(eq("测试子任务: 测试工具策略"), eq(1), eq(0.90f))).thenReturn(List.of());
        when(generationRouter.call(
                eq(LlmScene.CHAT),
                eq("prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120))
        )).thenReturn(new LlmResponse("""
                {
                  "scenario": "测试子任务",
                  "strategy": "测试工具策略",
                  "lessons": [],
                  "applicableConditions": [],
                  "toolsUsed": ["web.search"],
                  "success": true,
                  "failureAttribution": null,
                  "effectivenessScore": 0.0,
                  "injectionCount": 0,
                  "positiveOutcomes": 0,
                  "negativeOutcomes": 0
                }
                """, 10, 5, "qwen-plus", "qwen3.5-plus", 100, false));

        var reflector = new SubtaskReflector(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties
        );

        reflector.reflect(buildState());

        verify(generationRouter).call(
                eq(LlmScene.CHAT),
                eq("prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120))
        );
    }

    private ReactAgentState buildState() {
        return ReactAgentState.builder()
                .traceId("trace-subtask")
                .sessionId("session-subtask")
                .goal("总结这次工具调用")
                .channel("web")
                .steps(List.of(
                        new ReactStep.ToolCall("web.search", "Web 搜索", "{\"q\":\"test\"}", 20),
                        new ReactStep.Observation("web.search", "Web 搜索", true, "命中结果", 0)
                ))
                .stepCount(2)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(1000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(false)
                .completionMode(com.lifepilot.agent.model.CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false)
                .build();
    }
}
