package com.lifepilot.llm.cache;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * 无操作 prompt 缓存策略 — pass-through 实现, 不对请求做任何改写。
 *
 * <p>用于以下 provider:</p>
 * <ul>
 *   <li>OpenAI 官方 / DeepSeek 官方 / Azure OpenAI 等 — provider 自动对长 prompt 隐式缓存,
 *       客户端无需显式标注;</li>
 *   <li>Ollama / TEI 等本地模型 — 不涉及远端缓存计费。</li>
 * </ul>
 *
 * <p>单例, 线程安全 (无可变状态)。通过 {@link #INSTANCE} 访问。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class NoopPromptCacheStrategy implements PromptCacheStrategy {

    /** 共享单例。 */
    public static final NoopPromptCacheStrategy INSTANCE = new NoopPromptCacheStrategy();

    private NoopPromptCacheStrategy() {
    }

    @Override
    public String name() {
        return "noop";
    }

    @Override
    @Nullable
    public ClientHttpRequestInterceptor restClientInterceptor() {
        return null;
    }

    @Override
    @Nullable
    public ExchangeFilterFunction webClientFilter() {
        return null;
    }
}
