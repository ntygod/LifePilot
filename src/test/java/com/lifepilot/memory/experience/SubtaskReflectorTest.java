package com.lifepilot.memory.experience;

import com.lifepilot.agent.learning.experience.SubtaskReflector;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);

        var properties = new AgentLearningProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);
        properties.getExperience().getSubtask().setLlmTimeoutSeconds(120);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(eq("测试子任务: 测试工具策略"), eq(1), eq(0.90f))).thenReturn(List.of());
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
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
                """, null, null, List.of(), Map.of(), 10, 5, null, 0, "qwen-plus", "qwen3.5-plus", 100, false));

        var reflector = new SubtaskReflector(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties,
                chatSessionRepository,
                projectContextResolver
        );

        reflector.reflect(buildState());

        verify(generationRouter).call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
                eq("prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120))
        );
    }

    @Test
    void LLM响应缺success时应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var properties = new AgentLearningProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
                eq("prompt"),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                eq(Duration.ofSeconds(120))
        )).thenReturn(new LlmResponse("""
                {
                  "scenario": "测试子任务",
                  "strategy": "测试工具策略"
                }
                """, null, null, List.of(), Map.of(), 10, 5, null, 0, "qwen-plus", "qwen3.5-plus", 100, false));

        var reflector = new SubtaskReflector(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties,
                chatSessionRepository,
                projectContextResolver
        );

        assertThatThrownBy(() -> reflector.reflect(buildState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子任务反思 LLM 响应 success 必须是 boolean");
    }

    @Test
    void 向量搜索返回null时应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var properties = new AgentLearningProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(eq("测试子任务: 测试工具策略"), eq(1), eq(0.90f))).thenReturn(null);
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
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
                """, null, null, List.of(), Map.of(), 10, 5, null, 0, "qwen-plus", "qwen3.5-plus", 100, false));

        var reflector = new SubtaskReflector(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties,
                chatSessionRepository,
                projectContextResolver
        );

        assertThatThrownBy(() -> reflector.reflect(buildState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子任务反思: 向量搜索结果不能为空");
    }

    @Test
    void 去重命中实体不存在时应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        var properties = new AgentLearningProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(eq("测试子任务: 测试工具策略"), eq(1), eq(0.90f)))
                .thenReturn(List.of(new VectorSearchResult("missing-subtask", 0.95f)));
        when(semanticMemory.findById("missing-subtask")).thenReturn(Optional.empty());
        when(generationRouter.call(
                eq(LlmScene.BACKGROUND_ANALYSIS),
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
                """, null, null, List.of(), Map.of(), 10, 5, null, 0, "qwen-plus", "qwen3.5-plus", 100, false));

        var reflector = new SubtaskReflector(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties,
                chatSessionRepository,
                projectContextResolver
        );

        assertThatThrownBy(() -> reflector.reflect(buildState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子任务反思: 去重命中实体不存在");
    }

    private ReactAgentState buildState() {
        return ReactAgentState.builder()
                .traceId("trace-subtask")
                .sessionId(null)
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
