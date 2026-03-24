package com.lifepilot.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextMessageFormatter 测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class ContextMessageFormatterTest {

    @Test
    void serializeForDebug_会把syntheticContext显示为独立上下文消息() {
        String debug = ContextMessageFormatter.serializeForDebug(List.of(
                new AssistantMessage("<synthetic_context type=\"user_profile_context\">profile</synthetic_context>"),
                new UserMessage("hello"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "builtin.memory.create",
                                "builtin.memory.create",
                                "{\"summary\":\"ok\"}"
                        )))
                        .build()
        ));

        assertThat(debug)
                .contains("ContextMessage[user_profile_context]")
                .contains("ToolResultMessage: builtin.memory.create: {\"summary\":\"ok\"}")
                .doesNotContain("AssistantMessage: <synthetic_context");
    }
}
