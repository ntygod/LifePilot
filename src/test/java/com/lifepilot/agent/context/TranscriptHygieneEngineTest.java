package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TranscriptHygieneEngine 测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class TranscriptHygieneEngineTest {

    @Test
    void clean_会丢弃空助手消息与孤立工具结果() {
        var engine = new TranscriptHygieneEngine(new AgentConfigProperties());
        List<Message> raw = List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                new AssistantMessage(""),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "tool.search", "tool.search", "{\"summary\":\"ignored\"}")))
                        .build(),
                new AssistantMessage("final")
        );

        var result = engine.clean(raw);

        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages())
                .extracting(message -> message.getClass().getSimpleName())
                .containsExactly("SystemMessage", "UserMessage", "AssistantMessage");
        assertThat(result.report().droppedEmptyAssistantMessages()).isEqualTo(1);
        assertThat(result.report().droppedOrphanToolResponses()).isEqualTo(1);
        assertThat(result.report().hasRepairs()).isTrue();
    }

    @Test
    void clean_会保留合法的工具调用与工具结果序列() {
        var engine = new TranscriptHygieneEngine(new AgentConfigProperties());
        var toolCall = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "tool.search",
                "{\"q\":\"budget\"}"
        );

        List<Message> raw = List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                AssistantMessage.builder().toolCalls(List.of(toolCall)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1", "tool.search", "{\"summary\":\"ok\"}")))
                        .build()
        );

        var result = engine.clean(raw);

        assertThat(result.messages()).hasSize(4);
        assertThat(((AssistantMessage) result.messages().get(2)).getText()).isNull();
        assertThat(result.report().hasRepairs()).isFalse();
    }

    @Test
    void clean_同名工具并行时应优先按callId匹配工具结果() {
        var engine = new TranscriptHygieneEngine(new AgentConfigProperties());

        List<Message> raw = List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "tool.search", "{\"q\":\"alpha\"}"),
                        new AssistantMessage.ToolCall("call-2", "function", "tool.search", "{\"q\":\"beta\"}")
                )).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-2", "tool.search", "{\"summary\":\"beta\"}")))
                        .build()
        );

        var result = engine.clean(raw);

        assertThat(result.messages()).hasSize(4);
        var toolResponse = (ToolResponseMessage) result.messages().get(3);
        assertThat(toolResponse.getResponses()).singleElement()
                .extracting(ToolResponseMessage.ToolResponse::id)
                .isEqualTo("call-2");
    }
}
