package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 非推理模型的 noop 协议 — 所有操作都不做。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class NoopThinkingProtocol implements ThinkingProtocol {
    @Override public ThinkingProtocolId id() { return ThinkingProtocolId.NONE; }
    @Override public void applyToRequest(RequestBuilder builder, ThinkingMode mode) { }
    @Nullable @Override public String extractReasoning(JsonNode rawResponseChunk) { return null; }
    @Override public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) { }
}
