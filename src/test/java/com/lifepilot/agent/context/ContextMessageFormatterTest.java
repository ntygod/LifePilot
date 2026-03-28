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
    void serializeForDebug_非消息标签应保持原始块顺序且不带序号或消息前缀() {
        String debug = ContextMessageFormatter.serializeForDebug(List.of(
                new AssistantMessage("<user_profile_context>profile</user_profile_context>"),
                new UserMessage("""
                        <runtime_context>
                        - 当前时间: 2026-03-25T11:00:00+08:00
                        </runtime_context>

                        <history_transcript>
                        [user] 历史消息
                        </history_transcript>

                        <current_request>
                        hello
                        </current_request>
                        """),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "memory.create",
                                "memory.create",
                                "{\"summary\":\"ok\"}"
                        )))
                        .build()
        ));

        assertThat(debug)
                .contains("<user_profile_context>profile</user_profile_context>")
                .contains("<runtime_context>")
                .contains("<history_transcript>")
                .contains("<current_request>")
                .contains("ToolResultMessage: memory.create: {\"summary\":\"ok\"}")
                .doesNotContain("[0] <user_profile_context>")
                .doesNotContain("UserMessage: <runtime_context>")
                .doesNotContain("AssistantMessage: <user_profile_context>");
    }
}
