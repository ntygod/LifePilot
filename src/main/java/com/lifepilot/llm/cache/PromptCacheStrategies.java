package com.lifepilot.llm.cache;

import com.lifepilot.llm.profile.PromptCacheStrategyId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt 缓存策略工厂。
 *
 * <p>按 {@link PromptCacheStrategyId} 解析对应的 {@link PromptCacheStrategy}：</p>
 * <ul>
 *   <li>{@code ANTHROPIC_EPHEMERAL} → {@link AnthropicPromptCacheStrategy}（cache_control 注入）；</li>
 *   <li>{@code DASHSCOPE_EXPLICIT} → {@link DashScopePromptCacheStrategy}（DashScope cache_control）；</li>
 *   <li>{@code OPENAI_AUTO} → {@link NoopPromptCacheStrategy}（OpenAI 官方自动缓存，无需注入）；</li>
 *   <li>{@code NOOP} → {@link NoopPromptCacheStrategy}（无远端缓存或本地模型）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class PromptCacheStrategies {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheStrategies.class);

    private PromptCacheStrategies() {
    }

    /**
     * 按策略 ID 选择实现。
     *
     * @param strategyId 缓存策略标识（来自 ProviderProfile.cacheStrategy）
     * @return 对应的策略实例（永不返回 {@code null}）
     */
    public static PromptCacheStrategy resolve(PromptCacheStrategyId strategyId) {
        if (strategyId == null) {
            return NoopPromptCacheStrategy.INSTANCE;
        }
        return switch (strategyId) {
            case ANTHROPIC_EPHEMERAL -> AnthropicPromptCacheStrategy.INSTANCE;
            case DASHSCOPE_EXPLICIT -> DashScopePromptCacheStrategy.INSTANCE;
            // OpenAI 官方走 provider 自身的 auto caching，noop pass-through
            case OPENAI_AUTO, NOOP -> {
                log.debug("Prompt 缓存策略: id={}, 使用 noop", strategyId);
                yield NoopPromptCacheStrategy.INSTANCE;
            }
        };
    }
}
