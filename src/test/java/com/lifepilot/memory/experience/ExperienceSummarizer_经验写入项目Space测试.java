package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExperienceSummarizer 按 ProjectContext 路由经验写入测试 ——
 * 验证 persistExperience 构造的 MemoryWriteContext.spaceId
 * 随 ChatSession.projectId + ProjectContext.isolated 正确路由。
 *
 * @author zsg
 * @since 2026-04-23
 */
class ExperienceSummarizer_经验写入项目Space测试 {

    private SemanticMemory semanticMemory;
    private VectorSearcher vectorSearcher;
    private GenerationRouter generationRouter;
    private PromptRegistry promptRegistry;
    private TrajectoryQualityAssessor qualityAssessor;
    private ChatSessionRepository chatSessionRepository;
    private ProjectContextResolver projectContextResolver;
    private MemoryProperties properties;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        vectorSearcher = mock(VectorSearcher.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        qualityAssessor = mock(TrajectoryQualityAssessor.class);
        chatSessionRepository = mock(ChatSessionRepository.class);
        projectContextResolver = mock(ProjectContextResolver.class);
        properties = new MemoryProperties();

        when(qualityAssessor.assess(any()))
                .thenReturn(new TrajectoryQualityReport(true, true, 1.0f, true, 2, true));
        when(promptRegistry.render(eq("memory/experience-extraction"), anyMap())).thenReturn("prompt");
        when(vectorSearcher.searchEntities(any(), eq(1), eq(0.90f))).thenReturn(List.of());
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.simple("""
                        {"scenario":"任务执行","strategy":"分两步","lessons":[],
                         "applicableConditions":[],"toolsUsed":[],"success":true,
                         "failureAttribution":null,"effectivenessScore":0.0,"injectionCount":0,
                         "positiveOutcomes":0,"negativeOutcomes":0}
                        """, 10, 5, "mock", "mock", 10L));
        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 隔离项目会话_经验写入项目space() {
        when(chatSessionRepository.findById("session-iso"))
                .thenReturn(Optional.of(session("session-iso", "proj-iso")));
        when(projectContextResolver.resolve("proj-iso"))
                .thenReturn(new ProjectContext("proj-iso", "space-proj-iso",
                        "space-personal", "space-experience", true));

        var summarizer = newSummarizer();
        summarizer.summarize(buildState("session-iso"));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isEqualTo("space-proj-iso");
        assertThat(ctx.memoryScope()).isNull();
    }

    @Test
    void 主账户会话_经验写入spaceId为null() {
        when(chatSessionRepository.findById("session-main"))
                .thenReturn(Optional.of(session("session-main", null)));

        var summarizer = newSummarizer();
        summarizer.summarize(buildState("session-main"));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isNull();
    }

    @Test
    void 共享项目会话_经验写入spaceId为null() {
        when(chatSessionRepository.findById("session-shared"))
                .thenReturn(Optional.of(session("session-shared", "proj-shared")));
        when(projectContextResolver.resolve("proj-shared"))
                .thenReturn(new ProjectContext("proj-shared", "space-shared",
                        "space-personal", "space-experience", false));

        var summarizer = newSummarizer();
        summarizer.summarize(buildState("session-shared"));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isNull();
    }

    @Test
    void resolver缺失_fallback为null() {
        var summarizer = new ExperienceSummarizer(
                semanticMemory, vectorSearcher, generationRouter,
                promptRegistry, properties, qualityAssessor);
        summarizer.summarize(buildState("session-x"));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isNull();
    }

    private ExperienceSummarizer newSummarizer() {
        return new ExperienceSummarizer(
                semanticMemory, vectorSearcher, generationRouter,
                promptRegistry, properties, qualityAssessor,
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
                .traceId("trace-exp")
                .sessionId(sessionId)
                .goal("任务执行")
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
