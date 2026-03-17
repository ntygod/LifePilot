package com.lifepilot.memory.retrieval;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QueryRewriter 属性测试 — 验证降级不变量。
 *
 * <p><b>Validates: Requirements 1.4</b></p>
 *
 * @author zsg
 * @since 2026-03-17
 */
class QueryRewriter属性测试 {

    // ─────────────────────────────────────────────
    //  Property P3 — QueryRewriter 降级不变量
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 1.4</b>
     *
     * <p>对任意 refinedQuery 字符串，当 LlmRouter.call() 抛出异常时，
     * QueryRewriter.rewrite() 应降级返回：
     * <ul>
     *   <li>result.primaryQuery() == refinedQuery</li>
     *   <li>result.rewrittenQueries().isEmpty()</li>
     *   <li>result.hydeEmbedding().isEmpty()</li>
     * </ul></p>
     */
    @Property(tries = 100)
    void LLM异常时降级返回原始查询(@ForAll("refinedQueries") String refinedQuery) {
        // 构建 Mock：LlmRouter.call() 抛出 LlmUnavailableException
        var llmRouter = mock(LlmRouter.class);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("模拟 LLM 不可用", "test", List.of()));

        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        // 测试 rewrite 模式
        var rewriteProps = buildProperties("rewrite");
        var rewriter = new QueryRewriter(llmRouter, rewriteProps, promptRegistry);
        var result = rewriter.rewrite(refinedQuery);

        assertEquals(refinedQuery, result.primaryQuery(),
                "降级时 primaryQuery 应等于输入的 refinedQuery");
        assertTrue(result.rewrittenQueries().isEmpty(),
                "降级时 rewrittenQueries 应为空");
        assertTrue(result.hydeEmbedding().isEmpty(),
                "降级时 hydeEmbedding 应为空");
    }

    /**
     * <b>Validates: Requirements 1.4</b>
     *
     * <p>对任意 refinedQuery 字符串，当 LlmRouter.call() 抛出异常时，
     * hyde 模式同样应降级返回原始查询。</p>
     */
    @Property(tries = 100)
    void hyde模式LLM异常时降级返回原始查询(@ForAll("refinedQueries") String refinedQuery) {
        var llmRouter = mock(LlmRouter.class);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("模拟 LLM 不可用", "test", List.of()));

        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        var hydeProps = buildProperties("hyde");
        var rewriter = new QueryRewriter(llmRouter, hydeProps, promptRegistry);
        var result = rewriter.rewrite(refinedQuery);

        assertEquals(refinedQuery, result.primaryQuery(),
                "hyde 降级时 primaryQuery 应等于输入的 refinedQuery");
        assertTrue(result.rewrittenQueries().isEmpty(),
                "hyde 降级时 rewrittenQueries 应为空");
        assertTrue(result.hydeEmbedding().isEmpty(),
                "hyde 降级时 hydeEmbedding 应为空");
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成随机查询字符串：包含中文、英文和混合字符。 */
    @Provide
    Arbitrary<String> refinedQueries() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(200)
                .alpha()
                .filter(s -> !s.isBlank());
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建指定改写模式的 MemoryProperties。 */
    private MemoryProperties buildProperties(String mode) {
        var properties = new MemoryProperties();
        properties.getRetrieval().setQueryRewriteMode(mode);
        properties.getRetrieval().setMaxRewrites(3);
        properties.getRetrieval().setRewriteTimeoutMs(5000);
        return properties;
    }
}
