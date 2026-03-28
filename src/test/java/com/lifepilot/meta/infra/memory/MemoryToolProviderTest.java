package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class MemoryToolProviderTest {

    @Test
    void knowledgeSearch工具应携带会话Datastore作用域执行检索() {
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        SessionKnowledgeScopeResolver scopeResolver = mock(SessionKnowledgeScopeResolver.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        MemoryToolProvider provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                null,
                documentRetriever,
                mock(SessionKnowledgeBaseRepository.class),
                scopeResolver,
                memoryProperties
        );
        provider.registerTools(registry);

        List<KnowledgeSearchScope> scopes = List.of(new KnowledgeSearchScope("kb-1", "ds-a"));
        when(scopeResolver.resolveScopes("session-1")).thenReturn(scopes);
        when(documentRetriever.retrieveByScopes(eq("主角金手指"), eq(scopes), eq(3)))
                .thenReturn(List.of(new DocumentSearchResult(
                        "chunk-1",
                        "doc-1",
                        "kb-1",
                        "主角金手指设定：时间回溯。",
                        Optional.empty(),
                        List.of("人物设定"),
                        0.91,
                        "fts",
                        Map.of("section", "power"),
                        Optional.empty(),
                        Optional.empty(),
                        DocumentSourceType.DATASTORE_DOCUMENT,
                        Optional.of("ds-a"),
                        Optional.of("collection-a")
                )));

        var tool = registry.resolve("knowledge.search").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("query", "主角金手指", "top_k", 3),
                tool.inputSchema(),
                null,
                Map.of("sessionId", "session-1")
        ));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = result.getData("results");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst())
                .containsEntry("sourceDatastoreId", "ds-a")
                .containsEntry("sourceType", "DATASTORE_DOCUMENT");
        verify(scopeResolver).resolveScopes("session-1");
        verify(documentRetriever).retrieveByScopes("主角金手指", scopes, 3);
    }

    @Test
    void knowledgeSearch工具在当前会话未绑定资料时应返回空结果() {
        HybridRetriever hybridRetriever = mock(HybridRetriever.class);
        SemanticMemory semanticMemory = mock(SemanticMemory.class);
        DocumentRetriever documentRetriever = mock(DocumentRetriever.class);
        SessionKnowledgeScopeResolver scopeResolver = mock(SessionKnowledgeScopeResolver.class);
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        MemoryToolProvider provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                null,
                documentRetriever,
                null,
                scopeResolver,
                new MemoryProperties()
        );
        provider.registerTools(registry);

        when(scopeResolver.resolveScopes("session-empty")).thenReturn(List.of());

        var tool = registry.resolve("knowledge.search").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("query", "春季旅游"),
                tool.inputSchema(),
                null,
                Map.of("sessionId", "session-empty")
        ));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isZero();
        assertThat(result.<String>getData("message")).isEqualTo("当前会话未绑定知识库或 datastore");
        verify(scopeResolver).resolveScopes("session-empty");
        verifyNoInteractions(documentRetriever);
    }
}
