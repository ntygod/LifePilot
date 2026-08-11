package com.lifepilot.memory.experience;

import com.lifepilot.agent.learning.experience.SubtaskReflector;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * SubtaskReflector 按 ProjectContext 路由子任务经验写入测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class SubtaskReflector_经验写入项目Space测试 {

    private SemanticMemory semanticMemory;
    private VectorSearcher vectorSearcher;
    private GenerationRouter generationRouter;
    private PromptRegistry promptRegistry;
    private ChatSessionRepository chatSessionRepository;
    private ProjectContextResolver projectContextResolver;
    private AgentLearningProperties properties;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        vectorSearcher = mock(VectorSearcher.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        chatSessionRepository = mock(ChatSessionRepository.class);
        projectContextResolver = mock(ProjectContextResolver.class);
        properties = new AgentLearningProperties();
        properties.getExperience().getSubtask().setMinToolSequence(1);

        when(promptRegistry.render(eq("memory/subtask-reflection"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(any(), eq(1), any(Float.class))).thenReturn(List.of());
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("""
                        {"scenario":"子任务","strategy":"策略","lessons":[],
                         "applicableConditions":[],"toolsUsed":["web.search"],"success":true,
                         "failureAttribution":null,"effectivenessScore":0.0,"injectionCount":0,
                         "positiveOutcomes":0,"negativeOutcomes":0}
                        """, null, null, List.of(), Map.of(), 10, 5, null, 0, "mock", "mock", 10L, false));
        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 隔离项目会话_子任务经验写入项目space() {
        when(chatSessionRepository.findById("session-iso"))
                .thenReturn(Optional.of(session("session-iso", "proj-iso")));
        when(projectContextResolver.resolve("proj-iso"))
                .thenReturn(new ProjectContext("proj-iso", "space-proj-iso",
                        "space-personal", "space-experience", true));

        newReflector().reflect(buildState("session-iso"));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isEqualTo("space-proj-iso");
        assertThat(ctx.memoryScope()).isNull();
    }

    @Test
    void 主账户会话_子任务经验spaceId为null() {
        when(chatSessionRepository.findById("session-main"))
                .thenReturn(Optional.of(session("session-main", null)));

        newReflector().reflect(buildState("session-main"));

        assertThat(captureWriteContext().spaceId()).isNull();
    }

    @Test
    void 会话不存在_应暴露写入空间判定失败() {
        when(chatSessionRepository.findById("session-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newReflector().reflect(buildState("session-x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法找到会话，不能判定子任务经验写入空间: session-x");
    }

    private SubtaskReflector newReflector() {
        return new SubtaskReflector(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties,
                chatSessionRepository, projectContextResolver);
    }

    private MemoryWriteContext captureWriteContext() {
        ArgumentCaptor<MemoryWriteContext> captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(TemporalEntity.class), any(), captor.capture());
        return captor.getValue();
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }

    private ReactAgentState buildState(String sessionId) {
        return ReactAgentState.builder()
                .traceId("trace")
                .sessionId(sessionId)
                .goal("子任务")
                .channel("web")
                .steps(List.of(
                        new ReactStep.ToolCall("web.search", "Web 搜索", "{}", 20),
                        new ReactStep.Observation("web.search", "Web 搜索", true, "ok", 0)
                ))
                .stepCount(2)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(1000).tokensUsed(0).tokensReserved(0)
                        .maxSteps(10).stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1)).elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(false)
                .completionMode(com.lifepilot.agent.model.CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false)
                .build();
    }
}
