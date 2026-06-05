package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider 项目上下文驱动的 filter 构造测试。
 *
 * <p>验证三条读取路径（search / search-experience / cancel）按 sessionId 反查 projectId
 * 得到的 ProjectContext 正确传入 HybridRetriever 的 filter 参数。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class MemoryToolProvider_项目上下文测试 {

    private HybridRetriever hybridRetriever;
    private SemanticMemory semanticMemory;
    private ProjectContextResolver projectContextResolver;
    private ChatSessionRepository chatSessionRepository;
    private DynamicToolRegistry registry;

    @BeforeEach
    void 初始化() {
        hybridRetriever = mock(HybridRetriever.class);
        semanticMemory = mock(SemanticMemory.class);
        projectContextResolver = mock(ProjectContextResolver.class);
        chatSessionRepository = mock(ChatSessionRepository.class);
        registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        var provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                mock(EpisodicMemory.class),
                mock(DocumentRetriever.class),
                mock(SessionKnowledgeBaseRepository.class),
                mock(SessionKnowledgeScopeResolver.class),
                new MemoryRetrievalProperties(),
                projectContextResolver,
                chatSessionRepository
        );
        provider.registerTools(registry);

        when(hybridRetriever.retrieve(anyString(), anyInt(), any(RetrievalWeights.class), any()))
                .thenReturn(List.of());
        when(semanticMemory.findByIds(any(), any())).thenReturn(Map.of());
    }

    @Test
    void search路径_隔离项目_filter包含三个space() {
        when(chatSessionRepository.findById("s-1"))
                .thenReturn(Optional.of(session("s-1", "proj-1")));
        when(projectContextResolver.resolve("proj-1")).thenReturn(
                new ProjectContext("proj-1", "space-proj", "space-personal", "space-experience", true));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "search", "query", "xxx"), tool.inputSchema(), null,
                Map.of("sessionId", "s-1")));

        MemoryReadFilter captured = captureFilter();
        assertThat(captured.spaceIds())
                .containsExactlyInAnyOrder("space-proj", "space-personal", "space-experience");
        assertThat(captured.scopes())
                .containsExactlyInAnyOrder(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT);
    }

    @Test
    void searchExperience路径_隔离项目_filter含project和experience_scope() {
        when(chatSessionRepository.findById("s-2"))
                .thenReturn(Optional.of(session("s-2", "proj-2")));
        when(projectContextResolver.resolve("proj-2")).thenReturn(
                new ProjectContext("proj-2", "space-proj-2", "space-personal", "space-experience", true));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "search-experience", "query", "任务"), tool.inputSchema(), null,
                Map.of("sessionId", "s-2")));

        MemoryReadFilter captured = captureFilter();
        assertThat(captured.spaceIds())
                .containsExactlyInAnyOrder("space-proj-2", "space-personal", "space-experience");
        assertThat(captured.scopes()).containsExactly(MemoryScope.AGENT_EXPERIENCE);
    }

    @Test
    void cancel路径_主账户对话_filter只含主账户两space_且不限定scope() {
        when(chatSessionRepository.findById("s-3"))
                .thenReturn(Optional.of(session("s-3", null)));
        when(projectContextResolver.resolve(null)).thenReturn(
                ProjectContext.personal("space-personal", "space-experience"));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "cancel", "query", "不再做这事"), tool.inputSchema(), null,
                Map.of("sessionId", "s-3")));

        MemoryReadFilter captured = captureFilter();
        assertThat(captured.spaceIds())
                .containsExactlyInAnyOrder("space-personal", "space-experience");
        assertThat(captured.restrictsScopes()).isFalse();
    }

    @Test
    void 无sessionId_fallback到原all过滤() {
        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "cancel", "query", "x"), tool.inputSchema(), null,
                Map.of()));

        MemoryReadFilter captured = captureFilter();
        assertThat(captured.isUnrestricted()).isTrue();
    }

    @Test
    void queryAtTime路径_隔离项目_按项目可读范围过滤() {
        Instant queryTime = Instant.parse("2026-05-04T00:00:00Z");
        when(chatSessionRepository.findById("s-time"))
                .thenReturn(Optional.of(session("s-time", "proj-time")));
        when(projectContextResolver.resolve("proj-time")).thenReturn(
                new ProjectContext("proj-time", "space-proj-time", "space-personal", "space-experience", true));
        when(semanticMemory.queryAtTime(eq(queryTime), any())).thenReturn(List.of());

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "query-at-time", "timestamp", queryTime.toString()),
                tool.inputSchema(), null,
                Map.of("sessionId", "s-time")));

        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(semanticMemory).queryAtTime(eq(queryTime), captor.capture());
        assertThat(captor.getValue().spaceIds())
                .containsExactlyInAnyOrder("space-proj-time", "space-personal", "space-experience");
        assertThat(captor.getValue().restrictsScopes()).isFalse();
    }

    private MemoryReadFilter captureFilter() {
        ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
        verify(hybridRetriever).retrieve(anyString(), anyInt(), any(RetrievalWeights.class), captor.capture());
        return captor.getValue();
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }
}
