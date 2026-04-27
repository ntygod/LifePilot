package com.lifepilot.llm.history;

import com.lifepilot.llm.ToolCall;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 适配器内部消息类型 — {@link ChatHistoryAssembler} 输出，Adapter 消费。
 *
 * <p>承载多轮 history 装载所需的全部字段：reasoning_content / reasoning_signature /
 * tool_calls / providerExtras（厂商私有字段，例如 DeepSeek extra_body）。
 *
 * @param role             消息角色：system / user / assistant / tool
 * @param content          消息文本
 * @param reasoningContent 推理过程文本，仅 assistant 角色有效，可空
 * @param reasoningSignature  推理签名，仅 Anthropic 有效，可空
 * @param toolCalls        工具调用列表，仅 assistant 角色有效（不可空，缺省 List.of()）
 * @param providerExtras   厂商私有字段（多轮回传需要的 extra_body 等，不可空，缺省 Map.of()）
 * @author zsg
 * @since 2026-04-27
 */
public record ProviderMessage(
        String role,
        String content,
        @Nullable String reasoningContent,
        @Nullable String reasoningSignature,
        List<ToolCall> toolCalls,
        Map<String, Object> providerExtras
) {

    public ProviderMessage {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        providerExtras = providerExtras != null ? Map.copyOf(providerExtras) : Map.of();
    }

    /**
     * 构造 user 角色消息。
     *
     * @param content 用户输入文本
     * @return user 消息
     */
    public static ProviderMessage user(String content) {
        return new ProviderMessage("user", content, null, null, List.of(), Map.of());
    }

    /**
     * 构造 system 角色消息。
     *
     * @param content 系统提示文本
     * @return system 消息
     */
    public static ProviderMessage system(String content) {
        return new ProviderMessage("system", content, null, null, List.of(), Map.of());
    }
}
