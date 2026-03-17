package com.lifepilot.llm;

import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LlmRouter.embedBatch() 单元测试。
 *
 * @author zsg
 * @since 2026-03-17
 */
@ExtendWith(MockitoExtension.class)
class LlmRouter_embedBatch_测试 {

    @Mock
    private ProviderRegistry providerRegistry;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    @Mock
    private SpringAiProviderAdapter providerAdapter;

    private LlmRouter router;

    private static final ProviderConfig EMBEDDING_CONFIG = new ProviderConfig(
            "embed-provider", ProviderType.OPENAI_COMPATIBLE, "https://api.example.com",
            "key", "text-embedding-3", 30, 1,
            List.of(), Set.of(ProviderCapability.EMBEDDING), true,
            0, 0, 8192, 1536, false
    );

    @BeforeEach
    void setUp() {
        router = new LlmRouter(providerRegistry, circuitBreakerManager, null);
    }

    // --- 辅助方法 ---

    private void 配置embedding可用() {
        when(providerRegistry.findByCapability(ProviderCapability.EMBEDDING))
                .thenReturn(List.of(EMBEDDING_CONFIG));
        when(circuitBreakerManager.isCallPermitted("embed-provider", "EMBEDDING"))
                .thenReturn(true);
        when(providerRegistry.getAdapter("embed-provider"))
                .thenReturn(providerAdapter);
    }

    // --- 测试用例 ---

    @Test
    void 正常批量调用_返回正确维度的embedding数组() {
        配置embedding可用();
        when(providerAdapter.embed("你好")).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(providerAdapter.embed("世界")).thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        float[][] results = router.embedBatch(List.of("你好", "世界"));

        assertEquals(2, results.length);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, results[0]);
        assertArrayEquals(new float[]{0.4f, 0.5f, 0.6f}, results[1]);
        verify(providerAdapter, times(2)).embed(anyString());
    }

    @Test
    void 空列表输入_返回空数组() {
        float[][] results = router.embedBatch(List.of());

        assertEquals(0, results.length);
        verifyNoInteractions(providerAdapter);
    }

    @Test
    void null列表输入_返回空数组() {
        float[][] results = router.embedBatch(null);

        assertEquals(0, results.length);
        verifyNoInteractions(providerAdapter);
    }

    @Test
    void 包含null元素的列表_null位置结果为null() {
        配置embedding可用();
        when(providerAdapter.embed("有效文本")).thenReturn(new float[]{1.0f, 2.0f});

        float[][] results = router.embedBatch(List.of("有效文本"));
        // 需要用 ArrayList 支持 null 元素
        var textsWithNull = new java.util.ArrayList<String>();
        textsWithNull.add("有效文本");
        textsWithNull.add(null);
        textsWithNull.add("有效文本");

        results = router.embedBatch(textsWithNull);

        assertEquals(3, results.length);
        assertNotNull(results[0]);
        assertNull(results[1]);
        assertNotNull(results[2]);
        // embed 只被调用 2 次（跳过 null）
        verify(providerAdapter, times(3)).embed("有效文本"); // 1 from first call + 2 from second
    }

    @Test
    void 包含空白字符串的列表_空白位置结果为null() {
        配置embedding可用();
        when(providerAdapter.embed("正常文本")).thenReturn(new float[]{1.0f});

        var texts = new java.util.ArrayList<String>();
        texts.add("正常文本");
        texts.add("   ");
        texts.add("");

        float[][] results = router.embedBatch(texts);

        assertEquals(3, results.length);
        assertNotNull(results[0]);
        assertNull(results[1]);
        assertNull(results[2]);
        verify(providerAdapter, times(1)).embed("正常文本");
    }

    @Test
    void 单条embed失败时_抛出LlmUnavailableException() {
        // 配置 Provider 可用但 embed 调用失败
        when(providerRegistry.findByCapability(ProviderCapability.EMBEDDING))
                .thenReturn(List.of(EMBEDDING_CONFIG));
        when(circuitBreakerManager.isCallPermitted("embed-provider", "EMBEDDING"))
                .thenReturn(true);
        when(providerRegistry.getAdapter("embed-provider"))
                .thenReturn(providerAdapter);
        when(providerAdapter.embed(anyString()))
                .thenThrow(new RuntimeException("Embedding 服务不可用"));

        assertThrows(LlmUnavailableException.class,
                () -> router.embedBatch(List.of("测试文本")));
    }
}
