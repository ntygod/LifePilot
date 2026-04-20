package com.lifepilot.llm.cache;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Locale;

/**
 * Prompt 缓存策略工厂。
 *
 * <p>按 {@link ProviderConfig} 解析出对应的 {@link PromptCacheStrategy}:</p>
 * <ul>
 *   <li>{@link ProviderType#ANTHROPIC} → {@link AnthropicPromptCacheStrategy};</li>
 *   <li>{@link ProviderType#OPENAI_COMPATIBLE} + baseUrl 包含 {@code dashscope} →
 *       {@link DashScopePromptCacheStrategy};</li>
 *   <li>{@link ProviderType#OPENAI_COMPATIBLE} 其它 (OpenAI 官方 / DeepSeek 官方 / Azure /
 *       未知 host) → {@link NoopPromptCacheStrategy} (auto caching 或 pass-through);</li>
 *   <li>{@link ProviderType#OLLAMA} / {@link ProviderType#TEI} → {@link NoopPromptCacheStrategy}
 *       (本地模型不涉及远端缓存)。</li>
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
     * 按 ProviderConfig 选择策略。
     *
     * @param config provider 配置
     * @return 对应的策略实例 (永不返回 {@code null}, 无匹配返回 noop)
     */
    public static PromptCacheStrategy resolve(ProviderConfig config) {
        ProviderType type = config.type();
        if (type == ProviderType.ANTHROPIC) {
            return AnthropicPromptCacheStrategy.INSTANCE;
        }
        if (type == ProviderType.OPENAI_COMPATIBLE && isDashScope(config.apiUrl())) {
            return DashScopePromptCacheStrategy.INSTANCE;
        }
        // OpenAI 官方 / DeepSeek 官方 / Azure 等走 provider 自身的 auto caching;
        // Ollama / TEI 为本地模型无需缓存 — 统一 noop
        log.debug("Prompt 缓存策略: provider id={}, type={}, 使用 noop", config.id(), type);
        return NoopPromptCacheStrategy.INSTANCE;
    }

    /** 判断 apiUrl 是否指向 DashScope (百炼) — 支持国内站与国际站。 */
    private static boolean isDashScope(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(apiUrl).getHost();
        } catch (IllegalArgumentException e) {
            // URI 解析失败 (极少见), 退化到字符串包含判断
            return apiUrl.toLowerCase(Locale.ROOT).contains("dashscope");
        }
        return host != null && host.toLowerCase(Locale.ROOT).contains("dashscope");
    }
}
