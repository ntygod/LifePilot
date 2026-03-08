package com.lifepilot.llm.cache;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.config.LlmConfigProperties.CacheConfigEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Preservation 保持测试 — SemanticCache 正常使用行为不变。
 *
 * <p>Property 4: 当 {@code LlmRouter.embed("probe")} 在构造时成功，
 * {@code vecAvailable = true}，lookup/putAsync 正常工作。
 * 这些测试验证修复 Bug 2（延迟初始化）后，正常路径的行为不受影响。</p>
 *
 * <p>所有测试必须在<b>未修复代码</b>上通过 — 它们测试的是已有的正确行为。</p>
 *
 * <p><b>Validates: Requirements 3.3</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SemanticCache_Preservation_保持测试 {

    /**
     * 构造时 embed 成功 → vecAvailable = true → lookup 正常执行向量搜索。
     *
     * <p>验证正常路径：构造函数中 {@code initVec0Table()} 成功后，
     * {@code lookup()} 会调用 {@code llmRouter.embed(prompt)} 执行向量搜索，
     * 而非直接返回 empty。</p>
     */
    @Test
    @DisplayName("Property 4: 构造时 embed 成功 → lookup 正常执行向量搜索")
    void 构造时embed成功_lookup正常执行向量搜索() {
        // Arrange
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // embed("probe") 成功 → initVec0Table 返回 true → vecAvailable = true
        when(llmRouter.embed("probe")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        // lookup 中 embed(prompt) 返回向量
        when(llmRouter.embed("测试 prompt")).thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act: 调用 lookup
        var result = cache.lookup("test-scene", null, "测试 prompt");

        // Assert: embed 被调用了 2 次 — 构造时 embed("probe") + lookup 中 embed("测试 prompt")
        verify(llmRouter).embed("probe");
        verify(llmRouter).embed("测试 prompt");
        // lookup 返回 empty（因为 mock 的 jdbcTemplate.query 返回 null/empty），但关键是它执行了向量搜索
        assertThat(result).isEmpty();
    }

    /**
     * 构造时 embed 成功 → vecAvailable = true → putAsync 正常执行缓存写入。
     *
     * <p>验证正常路径：构造函数中 {@code initVec0Table()} 成功后，
     * {@code putAsync()} 会异步调用 {@code llmRouter.embed(prompt)} 写入缓存，
     * 而非直接 return。</p>
     */
    @Test
    @DisplayName("Property 4: 构造时 embed 成功 → putAsync 正常执行缓存写入")
    void 构造时embed成功_putAsync正常执行缓存写入() throws InterruptedException {
        // Arrange
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // embed("probe") 成功
        when(llmRouter.embed("probe")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        // putAsync 中 embed(prompt) 返回向量
        when(llmRouter.embed("写入 prompt")).thenReturn(new float[]{0.7f, 0.8f, 0.9f});

        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act: 调用 putAsync
        cache.putAsync("test-scene", "PLANNING", "写入 prompt", "测试响应", "test-model");

        // 等待异步操作完成
        Thread.sleep(500);

        // Assert: embed 被调用了 2 次 — 构造时 embed("probe") + putAsync 中 embed("写入 prompt")
        verify(llmRouter).embed("probe");
        verify(llmRouter).embed("写入 prompt");
        // 验证 jdbcTemplate.update 被调用（写入 semantic_cache 和 semantic_cache_vec）
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    /**
     * 验证 initVec0Table 只在构造时调用一次 — embed("probe") 只被调用一次。
     *
     * <p>当构造时 embed 成功，{@code vecAvailable = true}，后续 lookup/putAsync
     * 不应再次调用 embed("probe") 进行初始化探测。</p>
     */
    @Test
    @DisplayName("Property 4: initVec0Table 只在构造时调用一次 — embed(\"probe\") 只调用一次")
    void initVec0Table只在构造时调用一次() throws InterruptedException {
        // Arrange
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // embed("probe") 成功
        when(llmRouter.embed("probe")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        // 其他 embed 调用返回向量
        when(llmRouter.embed("prompt-1")).thenReturn(new float[]{0.4f, 0.5f, 0.6f});
        when(llmRouter.embed("prompt-2")).thenReturn(new float[]{0.7f, 0.8f, 0.9f});

        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act: 多次调用 lookup 和 putAsync
        cache.lookup("scene-1", null, "prompt-1");
        cache.lookup("scene-2", "PLANNING", "prompt-2");
        cache.putAsync("scene-3", null, "prompt-1", "响应", "model");

        // 等待异步操作完成
        Thread.sleep(500);

        // Assert: embed("probe") 只在构造时被调用一次
        verify(llmRouter, times(1)).embed("probe");
    }
}
