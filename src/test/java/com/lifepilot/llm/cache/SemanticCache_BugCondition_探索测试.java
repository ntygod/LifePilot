package com.lifepilot.llm.cache;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.config.LlmConfigProperties.CacheConfigEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SemanticCache 探索测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class SemanticCache_BugCondition_探索测试 {

    @Test
    @DisplayName("首次探测失败后，后续 lookup 再次调用可以触发恢复")
    void 首次探测失败后后续lookup再次调用可以触发恢复() {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenThrow(new LlmUnavailableException("无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("测试 prompt", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);
        cache.lookup("test-scene", null, "default", "测试 prompt");
        cache.lookup("test-scene", null, "default", "测试 prompt");

        verify(embeddingRouter, times(2)).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
    }

    @Test
    @DisplayName("首次探测失败后，后续 putAsync 再次调用可以触发恢复")
    void 首次探测失败后后续putAsync再次调用可以触发恢复() throws InterruptedException {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenThrow(new LlmUnavailableException("无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("测试 prompt", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);
        cache.putAsync("test-scene", null, "default", "测试 prompt", "测试响应", "test-model");
        cache.putAsync("test-scene", null, "default", "测试 prompt", "测试响应", "test-model");

        Thread.sleep(500);

        verify(embeddingRouter, times(2)).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
    }

    @Test
    @DisplayName("首次失败后只要 Provider 恢复，后续请求仍可重新初始化")
    void 首次失败后只要Provider恢复后续请求仍可重新初始化() {
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        when(embeddingRouter.embed("probe", EmbeddingUseCase.DEFAULT, null, null))
                .thenThrow(new LlmUnavailableException("无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingRouter.embed("测试 prompt", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        SemanticCache cache = new SemanticCache(config, embeddingRouter, jdbcTemplate);
        cache.lookup("test-scene", null, "default", "测试 prompt");
        cache.lookup("test-scene", null, "default", "测试 prompt");

        verify(embeddingRouter, times(2)).embed("probe", EmbeddingUseCase.DEFAULT, null, null);
    }
}
