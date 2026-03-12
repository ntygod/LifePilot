package com.lifepilot.llm.cache;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.config.LlmConfigProperties.CacheConfigEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Bug Condition 探索测试 — SemanticCache 永久降级。
 *
 * <p>Property 3: 当 Embedding Provider 在 SemanticCache 构造时尚未就绪（{@code llmRouter.embed("probe")}
 * 抛出"无可用 EMBEDDING Provider"异常），构造函数中 {@code initVec0Table()} 失败，
 * {@code vecAvailable} 被设为 {@code false} 且为 {@code final}，导致缓存永久降级。</p>
 *
 * <p>本测试编码的是<b>期望行为</b>：Provider 就绪后，{@code vecAvailable} 应通过延迟初始化
 * 变为 {@code true}，{@code lookup()} 应正常执行向量搜索而非直接返回 empty。</p>
 *
 * <p>在未修复代码上，本测试预期<b>失败</b>，因为 {@code vecAvailable} 是 {@code final boolean}，
 * 构造时一旦设为 {@code false} 就无法恢复，确认 bug 存在。</p>
 *
 * <p><b>Validates: Requirements 1.2, 2.2</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SemanticCache_BugCondition_探索测试 {

    /**
     * 构造时 embed 失败后，Provider 就绪时 lookup 应触发延迟初始化恢复。
     *
     * <p>模拟场景：
     * <ol>
     *   <li>构造 SemanticCache 时，{@code llmRouter.embed("probe")} 抛出异常（Provider 未就绪）</li>
     *   <li>构造完成后，Provider 就绪，{@code embed()} 后续调用成功</li>
     *   <li>调用 {@code lookup()}，期望延迟初始化成功，缓存功能恢复</li>
     * </ol>
     *
     * <p>未修复代码上：{@code vecAvailable} 是 {@code final boolean = false}，
     * {@code lookup()} 直接返回 {@code Optional.empty()}，不会尝试重新初始化。</p>
     */
    @Test
    @DisplayName("Property 3: 构造时 embed 失败 → Provider 就绪后 lookup 应触发延迟初始化")
    void 构造时embed失败_Provider就绪后lookup应触发延迟初始化() {
        // Arrange: Mock LlmRouter — 第一次 embed 抛异常，后续成功
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // 第一次调用（构造函数中 initVec0Table 调用 embed("probe")）抛异常
        // 后续调用返回正常向量（模拟 Provider 就绪）
        when(llmRouter.embed(anyString()))
                .thenThrow(new LlmUnavailableException(
                        "无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        // Act 1: 构造 SemanticCache — 构造函数中 initVec0Table 会失败
        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act 2: 模拟 Provider 就绪后调用 lookup
        // 期望行为：lookup 应触发延迟初始化（ensureVecInitialized），而非直接返回 empty
        cache.lookup("test-scene", null, "default", "测试 prompt");

        // Assert: 验证 embed 被调用了至少 2 次
        // 第 1 次：构造函数中 initVec0Table（失败）
        // 第 2 次：lookup 中延迟初始化重试 embed("probe")（期望行为）
        // 在未修复代码上，embed 只被调用 1 次（构造函数中），lookup 直接返回 empty
        verify(llmRouter, atLeast(2)).embed(anyString());
    }

    /**
     * 构造时 embed 失败后，putAsync 也应触发延迟初始化恢复。
     *
     * <p>未修复代码上：{@code vecAvailable} 是 {@code final boolean = false}，
     * {@code putAsync()} 直接 return，不会尝试写入缓存。</p>
     */
    @Test
    @DisplayName("Property 3: 构造时 embed 失败 → Provider 就绪后 putAsync 应触发延迟初始化")
    void 构造时embed失败_Provider就绪后putAsync应触发延迟初始化() {
        // Arrange
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // 第一次 embed 抛异常，后续成功
        when(llmRouter.embed(anyString()))
                .thenThrow(new LlmUnavailableException(
                        "无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        // Act 1: 构造 SemanticCache — initVec0Table 失败
        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act 2: 调用 putAsync — 期望触发延迟初始化
        cache.putAsync("test-scene", null, "default", "测试 prompt", "测试响应", "test-model");

        // 等待异步操作完成
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // Assert: 验证 embed 被调用了至少 2 次
        // 在未修复代码上，putAsync 直接 return（vecAvailable=false），embed 只被调用 1 次
        verify(llmRouter, atLeast(2)).embed(anyString());
    }

    /**
     * vecAvailable 在构造失败后永久为 false — 直接验证缓存降级行为。
     *
     * <p>这是最直接的 bug 确认：构造时 embed 失败后，即使后续 embed 可以成功，
     * lookup 仍然直接返回 empty（因为 vecAvailable 是 final=false）。</p>
     *
     * <p>期望行为（修复后）：lookup 不应因为构造时的临时失败而永久返回 empty，
     * 应该在 Provider 就绪后通过延迟初始化恢复缓存功能。</p>
     */
    @Test
    @DisplayName("Property 3: vecAvailable 构造失败后不应永久为 false")
    void vecAvailable_构造失败后不应永久为false() {
        // Arrange
        LlmRouter llmRouter = mock(LlmRouter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CacheConfigEntry config = new CacheConfigEntry();

        // 第一次 embed 抛异常（构造时），后续成功（Provider 就绪）
        when(llmRouter.embed("probe"))
                .thenThrow(new LlmUnavailableException(
                        "无可用 EMBEDDING Provider", "EMBEDDING", List.of()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        // 为 lookup 中的 embed 调用准备返回值
        when(llmRouter.embed("测试 prompt"))
                .thenReturn(new float[]{0.4f, 0.5f, 0.6f});

        // Act: 构造 SemanticCache（initVec0Table 失败）
        SemanticCache cache = new SemanticCache(config, llmRouter, jdbcTemplate);

        // Act: 调用 lookup — 期望行为是触发延迟初始化并执行向量搜索
        cache.lookup("test-scene", null, "default", "测试 prompt");

        // Assert: 在修复后，lookup 应该尝试延迟初始化并执行向量搜索
        // 即使最终因为数据库为空返回 empty，embed("probe") 应该被再次调用（延迟初始化）
        // 在未修复代码上，embed("probe") 只在构造函数中被调用一次
        verify(llmRouter, times(2)).embed("probe");
    }
}
