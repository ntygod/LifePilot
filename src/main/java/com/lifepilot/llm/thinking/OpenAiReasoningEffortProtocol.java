package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * OpenAI o1 / o3 / GPT-5 系列协议。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：reasoning.effort 参数（none/minimal/low/medium/high）；OpenAI SDK 通过 ChatOptions 暴露；</li>
 *   <li>响应：API 不返回 reasoning，仅返回 content（如需 reasoning 摘要要单独配置 reasoning summary）；</li>
 *   <li>多轮：无需回传 reasoning。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OpenAiReasoningEffortProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.OPENAI_REASONING_EFFORT;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> { /* 不下发 */ }
            case ENABLED -> builder.putChatOption("reasoning_effort", "medium");
            case DISABLED -> builder.putChatOption("reasoning_effort", "none");
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        // OpenAI 不需要回传 reasoning
    }
}
