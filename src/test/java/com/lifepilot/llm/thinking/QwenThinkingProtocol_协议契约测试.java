package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QwenThinkingProtocol_协议契约测试 {

    private final QwenThinkingProtocol protocol = new QwenThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_QWEN() { assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.QWEN); }

    @Test
    void mode_ENABLED_注入_chat_template_kwargs_enable_thinking_true() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.extraBodyFields())
                .containsEntry("chat_template_kwargs", Map.of("enable_thinking", true));
    }

    @Test
    void mode_DISABLED_注入_chat_template_kwargs_enable_thinking_false() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.extraBodyFields())
                .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
    }

    @Test
    void mode_AUTO_不下发() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void 提取_reasoning_content_与_DeepSeek_位置一致() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"reasoning_content\":\"qwen 思考\"}}]}");
        assertThat(protocol.extractReasoning(node)).isEqualTo("qwen 思考");
    }

    @Test
    void injectHistoryReasoning_保守策略_缺时补空字符串() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("content", "final"));
        assertThat(builder.reasoningContent()).isEqualTo("");
    }

    @Test
    void injectHistoryReasoning_reasoning_为_null_补空字符串() {
        var builder = new AssistantMessageBuilder().content("final");
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("content", "final");
        payload.put("reasoning_content", null);
        protocol.injectHistoryReasoning(builder, payload);
        assertThat(builder.reasoningContent()).isEqualTo("");
    }

    @Test
    void injectHistoryReasoning_reasoning_为非字符串类型_补空字符串() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, java.util.Map.of(
                "content", "final",
                "reasoning_content", 42  // 非字符串类型应当被忽略
        ));
        assertThat(builder.reasoningContent()).isEqualTo("");
    }
}
