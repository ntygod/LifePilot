package com.lifepilot.knowledge.enricher;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ChunkContextEnricher 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@ExtendWith(MockitoExtension.class)
class ChunkContextEnricherTest {

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    @Test
    void enrich_增强上下文后保留文档来源元数据() {
        when(promptRegistry.render(eq("knowledge/chunk-context-single"), any(Map.class)))
                .thenReturn("prompt");
        when(generationRouter.call(
                eq("knowledge_extraction"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(null),
                eq(GenerationCapability.CHAT),
                eq(null)
        )).thenReturn(LlmResponse.simple("旅行攻略摘要", 0, 0, "mock", "mock-model", 0));

        var enricher = new ChunkContextEnricher(
                generationRouter,
                new KnowledgeBaseProperties.ContextEnricher(true, 64, false, 1, 2000),
                promptRegistry,
                new TokenCounter.Heuristic()
        );

        var chunk = new DocumentChunk(
                "chunk-1",
                "doc-1",
                "kb-1",
                "2026 春季旅行｜10 大热门目的地推荐",
                Optional.empty(),
                0,
                0,
                32,
                12,
                "hash-1",
                List.of("春季旅游"),
                1,
                Map.of("category", "travel"),
                DocumentSourceType.FILE,
                Optional.empty(),
                0
        );

        var enriched = enricher.enrich(List.of(chunk), "春季旅游摘要");

        assertThat(enriched).hasSize(1);
        assertThat(enriched.getFirst().contextPrefix()).hasValue("旅行攻略摘要");
        assertThat(enriched.getFirst().sourceType()).isEqualTo(DocumentSourceType.FILE);
        assertThat(enriched.getFirst().metadata()).containsEntry("category", "travel");
    }
}
