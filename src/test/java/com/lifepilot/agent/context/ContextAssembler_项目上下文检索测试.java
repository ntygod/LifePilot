package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 项目上下文驱动的记忆过滤器构造测试。
 *
 * <p>覆盖以下场景：
 * <ul>
 *   <li>主账户对话（projectId=null）→ filter spaceIds 只含 personal/experience</li>
 *   <li>隔离项目对话（isolated=true）→ filter spaceIds 含 3 space</li>
 *   <li>不隔离项目对话（isolated=false）→ filter spaceIds 只含 personal/experience</li>
 *   <li>sessionId 查不到 ChatSession → fallback 到原 scope-only 行为</li>
 *   <li>resolver bean 缺失 → fallback</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ContextAssembler_项目上下文检索测试 {

    @Test
    void 主账户对话_experienceFilter_只含personal和experience两个space() {
        var deps = buildDeps();
        when(deps.chatSessionRepository.findById("session-1"))
                .thenReturn(java.util.Optional.of(session("session-1", null)));
        when(deps.projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));

        var assembler = buildAssembler(deps);
        assembler.assemble(state("session-1"));

        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(deps.hybridRetriever, atLeastOnce())
                .retrieve(any(), anyInt(), any(RetrievalWeights.class), captor.capture());
        MemoryReadFilter filter = pickExperienceFilter(captor);
        assertThat(filter.spaceIds()).containsExactlyInAnyOrder("space-personal", "space-experience");
        assertThat(filter.scopes()).containsExactly(MemoryScope.AGENT_EXPERIENCE);
    }

    @Test
    void 隔离项目对话_experienceFilter_含项目space及主账户两space() {
        var deps = buildDeps();
        when(deps.chatSessionRepository.findById("session-2"))
                .thenReturn(java.util.Optional.of(session("session-2", "proj-1")));
        when(deps.projectContextResolver.resolve("proj-1"))
                .thenReturn(new ProjectContext(
                        "proj-1", "space-proj", "space-personal", "space-experience", true));

        var assembler = buildAssembler(deps);
        assembler.assemble(state("session-2"));

        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(deps.hybridRetriever, atLeastOnce())
                .retrieve(any(), anyInt(), any(RetrievalWeights.class), captor.capture());
        MemoryReadFilter filter = pickExperienceFilter(captor);
        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder("space-proj", "space-personal", "space-experience");
        assertThat(filter.scopes()).containsExactly(MemoryScope.AGENT_EXPERIENCE);
    }

    @Test
    void 不隔离项目对话_experienceFilter_忽略项目space() {
        var deps = buildDeps();
        when(deps.chatSessionRepository.findById("session-3"))
                .thenReturn(java.util.Optional.of(session("session-3", "proj-2")));
        when(deps.projectContextResolver.resolve("proj-2"))
                .thenReturn(new ProjectContext(
                        "proj-2", "space-proj-2", "space-personal", "space-experience", false));

        var assembler = buildAssembler(deps);
        assembler.assemble(state("session-3"));

        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(deps.hybridRetriever, atLeastOnce())
                .retrieve(any(), anyInt(), any(RetrievalWeights.class), captor.capture());
        MemoryReadFilter filter = pickExperienceFilter(captor);
        assertThat(filter.spaceIds())
                .containsExactlyInAnyOrder("space-personal", "space-experience");
        assertThat(filter.spaceIds()).doesNotContain("space-proj-2");
    }

    @Test
    void sessionId查不到ChatSession_回退到scopeOnly的userMemory行为() {
        var deps = buildDeps();
        when(deps.chatSessionRepository.findById("session-unknown"))
                .thenReturn(java.util.Optional.empty());

        var assembler = buildAssembler(deps);
        assembler.assemble(state("session-unknown"));

        // fallback 应落在 agentExperience() — 等同 scope-only，不限定 space
        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(deps.hybridRetriever, atLeastOnce())
                .retrieve(any(), anyInt(), any(RetrievalWeights.class), captor.capture());
        MemoryReadFilter filter = pickExperienceFilter(captor);
        assertThat(filter.restrictsSpaces()).isFalse();
        assertThat(filter.scopes()).containsExactly(MemoryScope.AGENT_EXPERIENCE);
    }

    @Test
    void resolverBean缺失_完全回退原scope过滤行为() {
        var deps = buildDeps();
        // 不设置 projectContextResolver 和 chatSessionRepository
        var assembler = buildAssemblerWithoutProjectDeps(deps);
        assembler.assemble(state("session-1"));

        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(deps.hybridRetriever, atLeastOnce())
                .retrieve(any(), anyInt(), any(RetrievalWeights.class), captor.capture());
        MemoryReadFilter filter = pickExperienceFilter(captor);
        assertThat(filter.restrictsSpaces()).isFalse();
        assertThat(filter.scopes()).containsExactly(MemoryScope.AGENT_EXPERIENCE);
    }

    // ==================== helpers ====================

    private static final class Deps {
        AgentConfigProperties config;
        PromptRegistry promptRegistry;
        ContextEngine contextEngine;
        SemanticMemory semanticMemory;
        MemoryProperties memoryProperties;
        HybridRetriever hybridRetriever;
        ProjectContextResolver projectContextResolver;
        ChatSessionRepository chatSessionRepository;
    }

    private Deps buildDeps() {
        var deps = new Deps();
        deps.config = new AgentConfigProperties();
        deps.config.getContext().setMaxContextTokens(4096);
        deps.config.getContext().setOutputReservedTokens(512);
        deps.promptRegistry = mock(PromptRegistry.class);
        deps.contextEngine = mock(ContextEngine.class);
        deps.semanticMemory = mock(SemanticMemory.class);
        deps.memoryProperties = new MemoryProperties();
        deps.hybridRetriever = mock(HybridRetriever.class);
        deps.projectContextResolver = mock(ProjectContextResolver.class);
        deps.chatSessionRepository = mock(ChatSessionRepository.class);

        when(deps.promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(deps.promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(deps.promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");
        when(deps.promptRegistry.render(eq("memory/agentic-tool-guide"))).thenReturn("");
        when(deps.promptRegistry.render(eq("agent/react-user-prompt"), anyMap())).thenReturn("user prompt");

        when(deps.contextEngine.load(any(ReactAgentState.class), anyInt())).thenReturn(
                new ContextEngine.ContextSnapshot(
                        List.of(),
                        List.of(),
                        "",
                        true, false, 0, 0, 0,
                        Map.of("source", "test")));

        // 让 semanticMemory 返回空列表，不影响 filter 捕获
        when(deps.semanticMemory.findCurrentByType(any(), any())).thenReturn(List.of());
        when(deps.semanticMemory.countByEntityType(any())).thenReturn(Map.of());

        return deps;
    }

    private ContextAssembler buildAssembler(Deps deps) {
        var assembler = new ContextAssembler(
                deps.config,
                deps.promptRegistry,
                null,
                deps.semanticMemory,
                deps.memoryProperties,
                null,
                null,
                null,
                null,
                deps.contextEngine,
                null, null, null, null, null, null,
                deps.hybridRetriever);
        assembler.setProjectContextResolver(deps.projectContextResolver);
        assembler.setChatSessionRepository(deps.chatSessionRepository);
        return assembler;
    }

    private ContextAssembler buildAssemblerWithoutProjectDeps(Deps deps) {
        return new ContextAssembler(
                deps.config,
                deps.promptRegistry,
                null,
                deps.semanticMemory,
                deps.memoryProperties,
                null,
                null,
                null,
                null,
                deps.contextEngine,
                null, null, null, null, null, null,
                deps.hybridRetriever);
    }

    /** 从 hybridRetriever.retrieve 的所有捕获 filter 中挑出 AGENT_EXPERIENCE scope 那次。 */
    private MemoryReadFilter pickExperienceFilter(ArgumentCaptor<MemoryReadFilter> captor) {
        return captor.getAllValues().stream()
                .filter(f -> f.scopes().contains(MemoryScope.AGENT_EXPERIENCE))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到 AGENT_EXPERIENCE filter 的 retrieve 调用，所有捕获: "
                        + captor.getAllValues()));
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }

    private ReactAgentState state(String sessionId) {
        // 加一个 ToolCall step：让 ContextAssembler 的"简单任务跳过"门通过，触发经验检索路径
        var dummyToolCall = new ReactStep.ToolCall("memory", "记忆", "{}", 0, "call-test");
        return ReactAgentState.builder()
                .traceId("trace-test")
                .sessionId(sessionId)
                .goal("请帮我做一件事")
                .channel("web")
                .steps(List.<ReactStep>of(dummyToolCall))
                .stepCount(1)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .parentTraceId(null)
                .depth(0)
                .preferredProvider(null)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .reasoningSummary(null)
                .allowedToolIds(null)
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();
    }
}
