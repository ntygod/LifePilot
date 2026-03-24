package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProviderMessageBuilder 测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class ProviderMessageBuilderTest {

    @Test
    void build_会按最终消息模型构造Provider消息() {
        var builder = new ProviderMessageBuilder(
                new TranscriptHygieneEngine(new AgentConfigProperties())
        );
        var context = new AssembledContext(
                "system prompt",
                List.of(new AssistantMessage("<synthetic_context type=\"workspace_context\">workspace</synthetic_context>")),
                List.of(new UserMessage("上一轮历史")),
                "user prompt",
                List.of(),
                TokenBudget.allocateDefault(4096),
                0,
                0.0f,
                0,
                false,
                List.of(),
                null
        );
        var state = ReactAgentState.builder()
                .traceId("trace-provider")
                .sessionId("session-provider")
                .goal("test")
                .channel("web")
                .steps(List.of(
                        new ReactStep.Thought(""),
                        new ReactStep.ToolCall("tool.search", "搜索", "{\"q\":\"budget\"}", 10),
                        new ReactStep.Observation("tool.search", "搜索", true, "{\"summary\":\"ok\"}", 12),
                        new ReactStep.Answer("final answer")
                ))
                .stepCount(4)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .parentTraceId(null)
                .depth(0)
                .preferredProvider(null)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .reasoningSummary(null)
                .allowedToolIds(null)
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();

        var result = builder.build(context, state);

        assertThat(result.messages()).hasSize(7);
        assertThat(result.messages().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.messages().get(2)).isInstanceOf(UserMessage.class);
        assertThat(result.messages().get(4)).isInstanceOf(AssistantMessage.class);
        assertThat(result.messages().get(5)).isInstanceOf(ToolResponseMessage.class);
        assertThat(result.messages().getLast()).isInstanceOf(AssistantMessage.class);
        assertThat(result.hygieneReport().droppedEmptyAssistantMessages()).isEqualTo(1);
    }

    @Test
    void serializeForMultimodal_会输出结构化上下文文本() {
        var builder = new ProviderMessageBuilder(
                new TranscriptHygieneEngine(new AgentConfigProperties())
        );
        var toolCall = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "tool.search",
                "{\"q\":\"budget\"}"
        );

        String serialized = builder.serializeForMultimodal(List.of(
                new org.springframework.ai.chat.messages.SystemMessage("system"),
                new AssistantMessage("<synthetic_context type=\"user_profile_context\">profile</synthetic_context>"),
                new UserMessage("user"),
                AssistantMessage.builder().content("thinking").toolCalls(List.of(toolCall)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "tool.search", "tool.search", "{\"summary\":\"ok\"}")))
                        .build()
        ));

        assertThat(serialized)
                .contains("[system]")
                .contains("[context:user_profile_context]")
                .contains("[user]")
                .contains("[tool_call:tool.search]")
                .contains("[assistant]")
                .contains("[tool_result:tool.search]");
    }
}
