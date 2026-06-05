package com.lifepilot.memory.experience;

import com.lifepilot.agent.learning.experience.ExperienceSummarizer;
import com.lifepilot.agent.learning.experience.TrajectoryQualityAssessor;
import com.lifepilot.agent.learning.experience.TrajectoryQualityReport;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * ExperienceSummarizer 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class ExperienceSummarizerTest {

    @Test
    void summarize_应使用经验配置中的超时覆盖() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var qualityAssessor = mock(TrajectoryQualityAssessor.class);

        var properties = new AgentLearningProperties();
        properties.getExperience().setLlmTimeoutSeconds(120);

        when(qualityAssessor.assess(eq(buildState())))
                .thenReturn(new TrajectoryQualityReport(true, true, 1.0f, true, 2, true));
        when(promptRegistry.render(eq("memory/experience-extraction"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(eq("测试场景: 测试策略"), eq(1), eq(0.90f))).thenReturn(List.of());
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
                  "scenario": "测试场景",
                  "strategy": "测试策略",
                  "lessons": [],
                  "applicableConditions": [],
                  "toolsUsed": [],
                  "success": true,
                  "failureAttribution": null,
                  "effectivenessScore": 0.0,
                  "injectionCount": 0,
                  "positiveOutcomes": 0,
                  "negativeOutcomes": 0
                }
                """, null, null, List.of(), Map.of(), 10, 5, null, 0, "qwen-plus", "qwen3.5-plus", 100, false));

        var summarizer = new ExperienceSummarizer(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                properties,
                qualityAssessor
        );

        assertNotNull(summarizer.summarize(buildState()));
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

    private ReactAgentState buildState() {
        return ReactAgentState.builder()
                .traceId("trace-exp")
                .sessionId("session-exp")
                .goal("整理一次复杂执行经验")
                .channel("web")
                .steps(List.of(
                        new ReactStep.ToolCall("shell.exec", "执行 Shell 命令", "{\"command\":\"echo hi\"}", 10),
                        new ReactStep.Observation("shell.exec", "执行 Shell 命令", true, "hi", 0),
                        new ReactStep.ToolCall("shell.exec", "执行 Shell 命令", "{\"command\":\"echo done\"}", 10),
                        new ReactStep.Observation("shell.exec", "执行 Shell 命令", true, "done", 0)
                ))
                .stepCount(4)
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
