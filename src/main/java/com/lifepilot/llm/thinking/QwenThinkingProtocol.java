package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Qwen3 推理模型协议（DashScope）。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：通过 chat_template_kwargs.enable_thinking 控制；</li>
 *   <li>响应：reasoning_content 位置同 DeepSeek；</li>
 *   <li>多轮：保守策略默认回传 reasoning_content，避免 Qwen 后续版本变契约导致 400。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class QwenThinkingProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.QWEN;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> { /* 不下发 */ }
            case ENABLED -> builder.putExtraBody("chat_template_kwargs",
                    Map.of("enable_thinking", true));
            case DISABLED -> builder.putExtraBody("chat_template_kwargs",
                    Map.of("enable_thinking", false));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var choices = rawResponseChunk.path("choices");
        if (!choices.isArray() || choices.size() == 0) return null;
        var first = choices.get(0);
        var deltaReasoning = first.path("delta").path("reasoning_content");
        if (deltaReasoning.isTextual()) return deltaReasoning.asText();
        var messageReasoning = first.path("message").path("reasoning_content");
        if (messageReasoning.isTextual()) return messageReasoning.asText();
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        if (reasoning instanceof String s && !s.isEmpty()) {
            builder.reasoningContent(s);
        } else {
            builder.reasoningContent("");
        }
    }
}
