package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider 写入路径 ProjectContext 测试 —— 验证 create / update / tag
 * 三条写入路径构造的 MemoryWriteContext.spaceId 按 ProjectContext 正确路由。
 *
 * @author zsg
 * @since 2026-04-23
 */
class MemoryToolProvider_项目写入路径测试 {

    private SemanticMemory semanticMemory;
    private ProjectContextResolver projectContextResolver;
    private ChatSessionRepository chatSessionRepository;
    private DynamicToolRegistry registry;

    @BeforeEach
    void 初始化() {
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
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
                new MemoryProperties(),
                projectContextResolver,
                chatSessionRepository
        );
        provider.registerTools(registry);

        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenAnswer(inv -> withId((TemporalEntity) inv.getArgument(0)));
    }

    @Test
    void create路径_隔离项目_writeContext写入项目space() {
        when(chatSessionRepository.findById("s-iso"))
                .thenReturn(Optional.of(session("s-iso", "proj-iso")));
        when(projectContextResolver.resolve("proj-iso")).thenReturn(
                new ProjectContext("proj-iso", "space-proj-iso", "space-personal", "space-experience", true));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "create", "name", "咖啡", "entityType", "PREFERENCE"),
                tool.inputSchema(), null,
                Map.of("sessionId", "s-iso")));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isEqualTo("space-proj-iso");
        assertThat(ctx.memoryScope()).isNull();
    }

    @Test
    void create路径_主账户对话_writeContext的spaceId为null() {
        when(chatSessionRepository.findById("s-main"))
                .thenReturn(Optional.of(session("s-main", null)));
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "create", "name", "跑步", "entityType", "HABIT"),
                tool.inputSchema(), null,
                Map.of("sessionId", "s-main")));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isNull();
        assertThat(ctx.memoryScope()).isNull();
    }

    @Test
    void update路径_隔离项目_renamed路径同样用项目space() {
        when(chatSessionRepository.findById("s-upd"))
                .thenReturn(Optional.of(session("s-upd", "proj-upd")));
        when(projectContextResolver.resolve("proj-upd")).thenReturn(
                new ProjectContext("proj-upd", "space-upd", "space-personal", "space-experience", true));

        var oldEntity = new TemporalEntity("e-1", EntityType.PREFERENCE, "旧名", "desc",
                Map.of(), 1, true, Instant.now(), null, "s-upd",
                1.0f, 0.5f, 0, null, Instant.now(), Instant.now());
        when(semanticMemory.findByIds(any(), any())).thenReturn(Map.of("e-1", oldEntity));

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "update", "entityId", "e-1", "name", "新名"),
                tool.inputSchema(), null,
                Map.of("sessionId", "s-upd")));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isEqualTo("space-upd");
    }

    @Test
    void tag路径_隔离项目_addRelation传入项目writeContext() {
        when(chatSessionRepository.findById("s-tag"))
                .thenReturn(Optional.of(session("s-tag", "proj-tag")));
        when(projectContextResolver.resolve("proj-tag")).thenReturn(
                new ProjectContext("proj-tag", "space-tag", "space-personal", "space-experience", true));
        when(semanticMemory.findByIds(any(), any())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            String id = ids.getFirst();
            return Map.of(id, entity(id));
        });

        var tool = registry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "tag",
                        "sourceEntityId", "src-1",
                        "targetEntityId", "tgt-1",
                        "relationType", "RELATED"),
                tool.inputSchema(), null,
                Map.of("sessionId", "s-tag")));

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).addRelation(any(), captor.capture());
        assertThat(captor.getValue().spaceId()).isEqualTo("space-tag");
    }

    @Test
    void create路径_resolver缺失_走fallback填null() {
        // 构造未注入 resolver 的 provider
        var providerWithoutResolver = new MemoryToolProvider(
                mock(HybridRetriever.class),
                semanticMemory,
                mock(EpisodicMemory.class),
                mock(DocumentRetriever.class),
                mock(SessionKnowledgeBaseRepository.class),
                mock(SessionKnowledgeScopeResolver.class),
                new MemoryProperties()
        );
        var fallbackRegistry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        providerWithoutResolver.registerTools(fallbackRegistry);

        var tool = fallbackRegistry.resolve("memory").orElseThrow();
        tool.execute(new ToolInput(tool.id(),
                Map.of("action", "create", "name", "x", "entityType", "PREFERENCE"),
                tool.inputSchema(), null,
                Map.of("sessionId", "s")));

        MemoryWriteContext ctx = captureWriteContext();
        assertThat(ctx.spaceId()).isNull();
    }

    private MemoryWriteContext captureWriteContext() {
        ArgumentCaptor<MemoryWriteContext> captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        return captor.getValue();
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }

    private TemporalEntity withId(TemporalEntity e) {
        if (e.id() != null) return e;
        return new TemporalEntity("gen-" + System.nanoTime(), e.type(), e.name(), e.description(),
                e.properties(), e.version(), e.isCurrent(), e.validFrom(), e.validTo(),
                e.sourceConversationId(), e.extractionConfidence(), e.importanceScore(),
                e.accessCount(), e.lastAccessedAt(), e.createdAt(), e.updatedAt());
    }

    private TemporalEntity entity(String id) {
        var now = Instant.now();
        return new TemporalEntity(id, EntityType.PREFERENCE, id, "desc",
                Map.of(), 1, true, now, null, "s-tag",
                1.0f, 0.5f, 0, null, now, now);
    }
}
