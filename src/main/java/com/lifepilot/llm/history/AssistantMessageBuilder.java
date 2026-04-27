package com.lifepilot.llm.history;

import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Assistant 消息构造器 — 用于多轮 history 注入时构造 ProviderMessage。
 *
 * <p>骨架版本，Phase 5 ChatHistoryAssembler 实现时扩展。
 *
 * <p><b>Not thread-safe</b> — 每次构造一条消息使用一个新实例，不要跨线程共享。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AssistantMessageBuilder {

    @Nullable private String content;
    @Nullable private String reasoningContent;
    @Nullable private String reasoningSignature;
    private final Map<String, Object> extras = new HashMap<>();

    public AssistantMessageBuilder content(String content) {
        this.content = content;
        return this;
    }

    public AssistantMessageBuilder reasoningContent(@Nullable String reasoningContent) {
        this.reasoningContent = reasoningContent;
        return this;
    }

    public AssistantMessageBuilder reasoningSignature(@Nullable String signature) {
        this.reasoningSignature = signature;
        return this;
    }

    public AssistantMessageBuilder putExtra(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    @Nullable public String content() { return content; }
    @Nullable public String reasoningContent() { return reasoningContent; }
    @Nullable public String reasoningSignature() { return reasoningSignature; }
    public Map<String, Object> extras() { return Map.copyOf(extras); }
}
