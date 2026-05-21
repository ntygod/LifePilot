package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QueryRewriter 属性测试，验证降级不变量。
 *
 * @author zsg
 * @since 2026-03-17
 */
class QueryRewriter属性测试 {

    @Property(tries = 100)
    void rewrite模式异常时降级返回原始查询(@ForAll("refinedQueries") String refinedQuery) {
        var generationRouter = mock(GenerationRouter.class);
        var embeddingRouter = mock(EmbeddingRouter.class);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any()))
                .thenThrow(new LlmUnavailableException("模拟生成服务不可用", "memory_query_rewrite", List.of()));

        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        var rewriteProps = buildProperties("rewrite");
        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, rewriteProps, promptRegistry);
        var result = rewriter.rewrite(refinedQuery);

        assertEquals(refinedQuery, result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
    }

    @Property(tries = 100)
    void hyde模式异常时降级返回原始查询(@ForAll("refinedQueries") String refinedQuery) {
        var generationRouter = mock(GenerationRouter.class);
        var embeddingRouter = mock(EmbeddingRouter.class);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any()))
                .thenThrow(new LlmUnavailableException("模拟生成服务不可用", "memory_query_rewrite", List.of()));

        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        var hydeProps = buildProperties("hyde");
        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, hydeProps, promptRegistry);
        var result = rewriter.rewrite(refinedQuery);

        assertEquals(refinedQuery, result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
    }

    @Provide
    Arbitrary<String> refinedQueries() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(200)
                .alpha()
                .filter(s -> !s.isBlank());
    }

    private MemoryRetrievalProperties buildProperties(String mode) {
        var properties = new MemoryRetrievalProperties();
        properties.getRetrieval().setQueryRewriteMode(mode);
        properties.getRetrieval().setMaxRewrites(3);
        properties.getRetrieval().setRewriteTimeoutMs(5000);
        return properties;
    }
}
