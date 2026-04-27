package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Anthropic Claude 4.x Extended Thinking 协议。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：thinking.type = enabled/disabled/adaptive；ENABLED 带 budget_tokens；
 *       Claude 4.7 仅支持 adaptive，旧版降级（本协议默认 adaptive）；</li>
 *   <li>响应：content array 中 type=="thinking" 块包含 thinking + signature 两字段；
 *       流式时为 content_block_delta 事件中 delta.type=="thinking_delta"；</li>
 *   <li>多轮：thinking block 整体回传到 content array 第一块（含 signature）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AnthropicThinkingProtocol implements ThinkingProtocol {

    private static final int DEFAULT_BUDGET_TOKENS = 8192;

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.ANTHROPIC;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> builder.putChatOption("thinking", Map.of("type", "adaptive"));
            case ENABLED -> builder.putChatOption("thinking",
                    Map.of("type", "enabled", "budget_tokens", DEFAULT_BUDGET_TOKENS));
            case DISABLED -> builder.putChatOption("thinking", Map.of("type", "disabled"));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        // 流式：content_block_delta + delta.type==thinking_delta
        var deltaType = rawResponseChunk.path("delta").path("type").asText("");
        if ("thinking_delta".equals(deltaType)) {
            var thinking = rawResponseChunk.path("delta").path("thinking");
            if (thinking.isTextual()) return thinking.asText();
        }
        // 同步：content array 找 type==thinking 的块
        var content = rawResponseChunk.path("content");
        if (content.isArray()) {
            for (var block : content) {
                if ("thinking".equals(block.path("type").asText(""))) {
                    var thinking = block.path("thinking");
                    if (thinking.isTextual()) return thinking.asText();
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public String extractReasoningSignature(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var content = rawResponseChunk.path("content");
        if (content.isArray()) {
            for (var block : content) {
                if ("thinking".equals(block.path("type").asText(""))) {
                    var sig = block.path("signature");
                    if (sig.isTextual()) return sig.asText();
                }
            }
        }
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        Object signature = prevPayload.get("reasoning_signature");
        if (reasoning instanceof String r && !r.isEmpty()) {
            builder.reasoningContent(r);
            if (signature instanceof String s && !s.isEmpty()) {
                builder.reasoningSignature(s);
            }
        }
    }
}
