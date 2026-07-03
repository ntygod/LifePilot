package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.consumption.hot.HotMemoryDigest;
import com.lifepilot.memory.consumption.hot.HotMemoryDigestService;
import com.lifepilot.memory.consumption.hot.HotMemorySectionKind;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 记忆消费契约测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("ContextAssembler 记忆消费契约")
class ContextAssembler_记忆消费契约测试 {

    @Test
    void assemble只消费热摘要并记录来源实体Id() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        when(hotDigestService.build(any(), anyString())).thenReturn(new HotMemoryDigest(
                "hot-1",
                "personal",
                Instant.now(),
                "rev-1",
                List.of(
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.USER_PROFILE,
                                "L3.5 热记忆 - 用户画像:\n- [L4偏好|EXPLICIT/USER_CONFIRMED|confidence=0.9] response_language = 中文",
                                List.of("pref-1"),
                                500),
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.EXPERIENCE,
                                "L3.5 热记忆 - 高价值经验:\n- [经验|DERIVED/LLM_SUMMARIZED_EXPERIENCE] 先跑测试",
                                List.of("exp-1"),
                                500),
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.FACTS,
                                "L3.5 热记忆 - 常用事实:\n- [主题|VERIFIED/DOCUMENT_GROUNDED] 项目使用 SQLite",
                                List.of("fact-1"),
                                400))));
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        var context = assembler.assemble(state("继续优化记忆"));

        String contextText = context.contextMessages().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(contextText)
                .contains("response_language = 中文")
                .contains("先跑测试")
                .contains("项目使用 SQLite");
        assertThat(context.injectedEntityIds()).containsExactly("pref-1", "exp-1", "fact-1");
        verify(hotDigestService).build(any(), anyString());
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    @Test
    void 热摘要构建失败时应直接暴露且不回退旧画像或冷检索路径() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        when(hotDigestService.build(any(), anyString())).thenThrow(new IllegalStateException("构建失败"));
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        assertThatThrownBy(() -> assembler.assemble(state("继续优化记忆")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上下文组装失败")
                .hasRootCauseMessage("构建失败");
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    @Test
    void 项目上下文解析失败时跳过热摘要且不回退个人记忆() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(Optional.of(session("session-1", "project-1")));
        when(projectContextResolver.resolve("project-1"))
                .thenThrow(new IllegalStateException("项目缺失"));
        var assembler = newAssembler(
                semanticMemory,
                contextEngine,
                hotDigestService,
                projectContextResolver,
                chatSessionRepository);

        var context = assembler.assemble(state("继续优化记忆"));

        assertThat(context.injectedEntityIds()).isEmpty();
        verify(hotDigestService, never()).build(any(), anyString());
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    private ContextAssembler newAssembler(SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService) {
        var projectContextResolver = mock(ProjectContextResolver.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(Optional.of(session("session-1", null)));
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
        return newAssembler(
                semanticMemory,
                contextEngine,
                hotDigestService,
                projectContextResolver,
                chatSessionRepository);
    }

    private ContextAssembler newAssembler(SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService,
                                          ProjectContextResolver projectContextResolver,
                                          ChatSessionRepository chatSessionRepository) {
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString())).thenReturn("");
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("");
        var assembler = new ContextAssembler(
                new AgentConfigProperties(),
                promptRegistry,
                null,
                semanticMemory,
                null,
                null,
                null,
                contextEngine,
                null,
                null,
                null,
                null);
        assembler.setProjectContextResolver(projectContextResolver);
        assembler.setChatSessionRepository(chatSessionRepository);
        assembler.setHotMemoryDigestService(hotDigestService);
        return assembler;
    }

    private ReactAgentState state(String goal) {
        return ReactAgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal(goal)
                .source(InteractionSource.system("test"))
                .steps(List.of())
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4096)
                        .maxSteps(10)
                        .maxDuration(Duration.ofMinutes(5))
                        .elapsed(Duration.ZERO)
                        .build())
                .completionMode(com.lifepilot.agent.model.CompletionMode.NORMAL)
                .build();
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }
}
