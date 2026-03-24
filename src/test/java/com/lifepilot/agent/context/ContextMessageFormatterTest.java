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
    void serializeForDebug_注入上下文显示为原始Xml而不是ContextMessage前缀() {
        String debug = ContextMessageFormatter.serializeForDebug(List.of(
                new AssistantMessage("<user_profile_context>profile</user_profile_context>"),
                new UserMessage("<current_request>hello</current_request>"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "builtin.memory.create",
                                "builtin.memory.create",
                                "{\"summary\":\"ok\"}"
                        )))
                        .build()
        ));

        assertThat(debug)
                .contains("[0] <user_profile_context>profile</user_profile_context>")
                .contains("UserMessage: <current_request>hello</current_request>")
                .contains("ToolResultMessage: builtin.memory.create: {\"summary\":\"ok\"}")
                .doesNotContain("ContextMessage[")
                .doesNotContain("AssistantMessage: <user_profile_context>");
    }
}
