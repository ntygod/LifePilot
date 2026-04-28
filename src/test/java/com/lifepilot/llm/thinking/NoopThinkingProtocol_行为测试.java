package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoopThinkingProtocol_行为测试 {

    private final NoopThinkingProtocol protocol = new NoopThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_NONE() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.NONE);
    }

    @Test
    void applyToRequest_不写任何字段() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).isEmpty();
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void extractReasoning_始终返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_不修改_builder() {
        var builder = new AssistantMessageBuilder().content("hi");
        protocol.injectHistoryReasoning(builder, Map.of("reasoning_content", "应被忽略"));
        assertThat(builder.reasoningContent()).isNull();
    }
}
