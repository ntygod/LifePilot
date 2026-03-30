package com.lifepilot.agent.callback;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * StreamingToolCallAggregator 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
class StreamingToolCallAggregatorTest {

    @Test
    void 按索引合并增量参数片段() {
        StreamingToolCallAggregator aggregator = new StreamingToolCallAggregator();

        aggregator.merge(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "tool.search", "{\"q\":\"")
        ));
        aggregator.merge(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "tool.search", "alpha\"}")
        ));

        assertEquals(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "tool.search", "{\"q\":\"alpha\"}")
        ), aggregator.toolCalls());
    }

    @Test
    void 累计快照参数不会重复拼接() {
        StreamingToolCallAggregator aggregator = new StreamingToolCallAggregator();

        aggregator.merge(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "tool.search", "{\"q\":\"al")
        ));
        aggregator.merge(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "tool.search", "{\"q\":\"alpha\"}")
        ));

        assertEquals("{\"q\":\"alpha\"}", aggregator.toolCalls().getFirst().arguments());
    }

    @Test
    void 同名工具并行时按索引分别归并() {
        StreamingToolCallAggregator aggregator = new StreamingToolCallAggregator();

        aggregator.merge(List.of(
                new AssistantMessage.ToolCall(null, "function", "tool.search", "{\"q\":\"a"),
                new AssistantMessage.ToolCall(null, "function", "tool.search", "{\"q\":\"b")
        ));
        aggregator.merge(List.of(
                new AssistantMessage.ToolCall("call-a", "function", "tool.search", "lpha\"}"),
                new AssistantMessage.ToolCall("call-b", "function", "tool.search", "eta\"}")
        ));

        assertEquals(List.of(
                new AssistantMessage.ToolCall("call-a", "function", "tool.search", "{\"q\":\"alpha\"}"),
                new AssistantMessage.ToolCall("call-b", "function", "tool.search", "{\"q\":\"beta\"}")
        ), aggregator.toolCalls());
    }
}
