package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiReasoningEffortProtocol_协议契约测试 {

    private final OpenAiReasoningEffortProtocol protocol = new OpenAiReasoningEffortProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_OPENAI_REASONING_EFFORT() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.OPENAI_REASONING_EFFORT);
    }

    @Test
    void mode_ENABLED_写_chatOption_reasoning_effort_medium() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).containsEntry("reasoning_effort", "medium");
    }

    @Test
    void mode_DISABLED_写_reasoning_effort_none() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.chatOptionsExtras()).containsEntry("reasoning_effort", "none");
    }

    @Test
    void mode_AUTO_不下发() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.chatOptionsExtras()).isEmpty();
    }

    @Test
    void extractReasoning_始终返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_不操作() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("reasoning_content", "应被忽略"));
        assertThat(builder.reasoningContent()).isNull();
    }
}
