package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicThinkingProtocol_协议契约测试 {

    private final AnthropicThinkingProtocol protocol = new AnthropicThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_ANTHROPIC() { assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.ANTHROPIC); }

    @Test
    @SuppressWarnings("unchecked")
    void mode_ENABLED_写_thinking_对象_含_budget_tokens() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).containsKey("thinking");
        var thinking = (Map<String, Object>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "enabled");
        assertThat(thinking).containsKey("budget_tokens");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mode_DISABLED_写_thinking_disabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        var thinking = (Map<String, Object>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "disabled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mode_AUTO_写_thinking_adaptive() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        var thinking = (Map<String, Object>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "adaptive");
    }

    @Test
    void 从_content_array_thinking_block_提取_thinking() throws Exception {
        var json = """
                {"content":[
                  {"type":"thinking","thinking":"我先想想","signature":"sig123"},
                  {"type":"text","text":"final answer"}
                ]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("我先想想");
        assertThat(protocol.extractReasoningSignature(node)).isEqualTo("sig123");
    }

    @Test
    void 流式_chunk_的_content_block_delta_thinking_提取() throws Exception {
        var json = """
                {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"片段"}}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("片段");
    }

    @Test
    void injectHistoryReasoning_写入_thinking_block_格式_占位标记() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of(
                "content", "final",
                "reasoning_content", "上一轮思考",
                "reasoning_signature", "sig"
        ));
        assertThat(builder.reasoningContent()).isEqualTo("上一轮思考");
        assertThat(builder.reasoningSignature()).isEqualTo("sig");
    }
}
