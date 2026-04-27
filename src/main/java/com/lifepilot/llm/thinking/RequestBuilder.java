package com.lifepilot.llm.thinking;

import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 适配器侧可变请求构造抽象。
 *
 * <p>ThinkingProtocol 实现按需写入合适分支：
 * <ul>
 *   <li>原生字段（OpenAI reasoning.effort / Anthropic thinking）→ 通过 ChatOptions builder API
 *       由 Adapter 在构造 ChatOptions 时合并 chatOptionsExtras 字段；</li>
 *   <li>私有字段（DeepSeek extra_body / Qwen chat_template_kwargs）→ 塞进 extraBodyFields，
 *       由 Adapter 通过 RestClient 拦截器（复用 AbstractJsonBodyRewritingStrategy 模式）
 *       在出站 HTTP 请求 JSON body 合并。</li>
 * </ul>
 *
 * <p><b>Not thread-safe</b> — 每次请求实例化一个新的 RequestBuilder，不要跨线程共享。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class RequestBuilder {

    /** ChatOptions 原生字段扩展（Adapter 构造 ChatOptions 时读取） */
    private final Map<String, Object> chatOptionsExtras = new HashMap<>();

    /** 厂商私有 extra_body / chat_template_kwargs 字段（HTTP 拦截器读取并合并到 JSON body） */
    private final Map<String, Object> extraBodyFields = new HashMap<>();

    public void putChatOption(String key, Object value) {
        chatOptionsExtras.put(key, value);
    }

    public void putExtraBody(String key, Object value) {
        extraBodyFields.put(key, value);
    }

    public Map<String, Object> chatOptionsExtras() {
        return Map.copyOf(chatOptionsExtras);
    }

    public Map<String, Object> extraBodyFields() {
        return Map.copyOf(extraBodyFields);
    }

    /**
     * 直接读取单个 chatOption 字段（主要用于测试断言）。
     *
     * <p>生产代码应优先用 {@link #chatOptionsExtras()} 拿不可变快照后迭代消费，
     * 避免依赖 mutable 视图。
     *
     * @param key chatOption 键
     * @return 对应值，未设置返回 null
     */
    @Nullable
    public Object getChatOption(String key) {
        return chatOptionsExtras.get(key);
    }
}
