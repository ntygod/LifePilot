package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * DeepSeek 推理模型协议（V4 系列）。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：通过 extra_body.thinking.type = enabled/disabled 控制；AUTO 不下发；</li>
 *   <li>响应：reasoning_content 平级于 content，可能在 choices[].message 或 choices[].delta；</li>
 *   <li>多轮：assistant message 必须回传上一轮的 reasoning_content（缺则补 "" 占位）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class DeepSeekThinkingProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.DEEPSEEK;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> {
                // 不下发，使用 provider 默认（DeepSeek 默认 enabled）
            }
            case ENABLED -> builder.putExtraBody("thinking", Map.of("type", "enabled"));
            case DISABLED -> builder.putExtraBody("thinking", Map.of("type", "disabled"));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var choices = rawResponseChunk.path("choices");
        if (!choices.isArray() || choices.size() == 0) return null;
        var first = choices.get(0);
        // 流式：delta.reasoning_content
        var deltaReasoning = first.path("delta").path("reasoning_content");
        if (deltaReasoning.isTextual()) {
            return deltaReasoning.asText();
        }
        // 同步：message.reasoning_content
        var messageReasoning = first.path("message").path("reasoning_content");
        if (messageReasoning.isTextual()) {
            return messageReasoning.asText();
        }
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        if (reasoning instanceof String s && !s.isEmpty()) {
            builder.reasoningContent(s);
        } else {
            // 缺 reasoning_content 时补 dummy 占位防止 DeepSeek 多轮 400
            builder.reasoningContent("");
        }
    }
}
