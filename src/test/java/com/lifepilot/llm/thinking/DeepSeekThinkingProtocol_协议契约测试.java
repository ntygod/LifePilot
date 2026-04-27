package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekThinkingProtocol_协议契约测试 {

    private final DeepSeekThinkingProtocol protocol = new DeepSeekThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_DEEPSEEK() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.DEEPSEEK);
    }

    @Test
    void mode_AUTO_不下发_thinking_字段() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void mode_ENABLED_注入_thinking_enabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.extraBodyFields()).containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void mode_DISABLED_注入_thinking_disabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.extraBodyFields()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void 从同步响应_choices_message_reasoning_content_提取() throws Exception {
        var json = """
                {"choices":[{"message":{"content":"final","reasoning_content":"我先思考"}}]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("我先思考");
    }

    @Test
    void 从流式_chunk_choices_delta_reasoning_content_提取() throws Exception {
        var json = """
                {"choices":[{"delta":{"reasoning_content":"片段思考"}}]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("片段思考");
    }

    @Test
    void 响应不含_reasoning_content_返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_有_reasoning_时写入_builder() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of(
                "content", "final",
                "reasoning_content", "上一轮思考"
        ));
        assertThat(builder.reasoningContent()).isEqualTo("上一轮思考");
    }

    @Test
    void injectHistoryReasoning_缺_reasoning_时补_dummy_占位() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("content", "final"));
        assertThat(builder.reasoningContent()).isEqualTo("");
    }
}
