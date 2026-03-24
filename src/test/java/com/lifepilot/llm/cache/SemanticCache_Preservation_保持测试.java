package com.lifepilot.llm.cache;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.llm.config.LlmConfigProperties.CacheConfigEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticCache 保持测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class SemanticCache_Preservation_保持测试 {

    @Test
    @DisplayName("构造期探测成功后 lookup 会继续执行向量检索")
    void 构造期探测成功后lookup会继续执行向量检索() {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("测试 prompt", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);

        var result = cache.lookup("test-scene", null, "text", "测试 prompt");

        verify(embeddingRouter).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
        verify(embeddingRouter).embed("测试 prompt", EmbeddingUseCase.DEFAULT, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("构造期探测成功后 putAsync 会继续写入缓存")
    void 构造期探测成功后putAsync会继续写入缓存() throws InterruptedException {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("写入 prompt", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.7f, 0.8f, 0.9f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);
        cache.putAsync("test-scene", "PLANNING", "text", "写入 prompt", "测试响应", "test-model");

        Thread.sleep(500);

        verify(embeddingRouter).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
        verify(embeddingRouter).embed("写入 prompt", EmbeddingUseCase.DEFAULT, null, null);
        verify(jdbcTemplate, times(2)).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    @DisplayName("探测只会在成功初始化后执行一次")
    void 探测只会在成功初始化后执行一次() throws InterruptedException {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("prompt-1", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});
        when(embeddingRouter.embed("prompt-2", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.7f, 0.8f, 0.9f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);

        cache.lookup("scene-1", null, "text", "prompt-1");
        cache.lookup("scene-2", "PLANNING", "text", "prompt-2");
        cache.putAsync("scene-3", null, "text", "prompt-1", "响应", "model");

        Thread.sleep(500);

        verify(embeddingRouter, times(1)).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
    }
}
