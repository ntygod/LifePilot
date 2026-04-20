package com.lifepilot.llm.cache;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Prompt 缓存策略 — 按 provider 决定如何注入显式缓存标记。
 *
 * <p>不同 provider 对 prompt caching 的触发方式不同:</p>
 * <ul>
 *   <li>DashScope / Anthropic 需要在请求体显式写 {@code cache_control: {type: ephemeral}} 才命中;</li>
 *   <li>OpenAI 官方 / DeepSeek 官方对长 prompt auto caching, 不需要客户端标注;</li>
 *   <li>Ollama / TEI 等本地模型不涉及远端缓存。</li>
 * </ul>
 *
 * <p>实现类按 provider 类型提供 RestClient 拦截器 (非流式) 和 WebClient filter (流式) 两端覆盖。
 * 对原生支持 auto caching 的 provider 或本地模型返回 {@code null}, 走 pass-through。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface PromptCacheStrategy {

    /**
     * 人类可读名称, 仅日志用。
     *
     * @return 策略名称
     */
    String name();

    /**
     * RestClient 路径拦截器 (非流式调用)。
     *
     * @return 拦截器实例; 返回 {@code null} 表示不介入 (pass-through)
     */
    @Nullable
    ClientHttpRequestInterceptor restClientInterceptor();

    /**
     * WebClient 路径 filter (流式调用)。
     *
     * @return filter 实例; 返回 {@code null} 表示不介入 (pass-through)
     */
    @Nullable
    ExchangeFilterFunction webClientFilter();
}
