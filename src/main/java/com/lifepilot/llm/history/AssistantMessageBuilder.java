package com.lifepilot.llm.history;

import com.lifepilot.llm.ToolCall;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assistant 消息构造器 — 由 {@link ChatHistoryAssembler} 联合 {@code ThinkingProtocol}
 * 装载多轮 history 中的 assistant 消息。
 *
 * <p>承载字段：content / reasoningContent / reasoningSignature / toolCalls / extras。
 * {@link #build()} 输出 {@link ProviderMessage}（role 固定为 "assistant"）。
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
    private final List<ToolCall> toolCalls = new ArrayList<>();
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

    /**
     * 追加一个工具调用。
     *
     * @param toolCall LLM 返回的工具调用元数据
     * @return this 支持链式调用
     */
    public AssistantMessageBuilder addToolCall(ToolCall toolCall) {
        toolCalls.add(toolCall);
        return this;
    }

    public AssistantMessageBuilder putExtra(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    /**
     * 构造 assistant 角色 {@link ProviderMessage}。
     *
     * <p>content 为 null 时输出空字符串；toolCalls / extras 走 {@code copyOf} 防止后续修改穿透。
     *
     * @return assistant 消息
     */
    public ProviderMessage build() {
        return new ProviderMessage(
                "assistant",
                content != null ? content : "",
                reasoningContent,
                reasoningSignature,
                List.copyOf(toolCalls),
                Map.copyOf(extras)
        );
    }

    @Nullable public String content() { return content; }
    @Nullable public String reasoningContent() { return reasoningContent; }
    @Nullable public String reasoningSignature() { return reasoningSignature; }
    public List<ToolCall> toolCalls() { return List.copyOf(toolCalls); }
    public Map<String, Object> extras() { return Map.copyOf(extras); }
}
