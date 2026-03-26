package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
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
    void build_应按运行时上下文_非消息标签_历史消息_当前请求顺序组装结构化提示词() {
        var builder = new ProviderMessageBuilder(
                new TranscriptHygieneEngine(new AgentConfigProperties())
        );
        var context = new AssembledContext(
                "system prompt",
                List.of(
                        new AssistantMessage("<workspace_context>workspace</workspace_context>"),
                        new AssistantMessage("<experience_context>experience</experience_context>")
                ),
                List.of(
                        new AssistantMessage("<history_transcript>\n以下消息为历史 transcript，按时间顺序排列。\n</history_transcript>"),
                        new UserMessage("上一轮历史消息"),
                        new AssistantMessage("上一轮助手回复")
                ),
                """
                        <runtime_context>
                        - 当前时间: 2026-03-24T10:00:00+08:00
                        </runtime_context>

                        <current_request>
                        user prompt
                        </current_request>
                        """,
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
                .completionReason(null)
                .reasoningSummary(null)
                .completionMode(CompletionMode.NORMAL)
                .allowedToolIds(null)
                .pendingMedia(null)
                .earlyStopRejectCount(0)
                .suspended(false)
                .suspendReason(null)
                .build();

        var result = builder.build(context, state);

        assertThat(result.messages()).hasSize(5);
        assertThat(result.messages().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(result.messages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(result.messages().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(result.messages().get(3)).isInstanceOf(ToolResponseMessage.class);
        assertThat(result.messages().getLast()).isInstanceOf(AssistantMessage.class);

        String structuredPrompt = ((UserMessage) result.messages().get(1)).getText();
        int runtimeIndex = structuredPrompt.indexOf("<runtime_context>");
        int workspaceIndex = structuredPrompt.indexOf("<workspace_context>");
        int experienceIndex = structuredPrompt.indexOf("<experience_context>");
        int historyIndex = structuredPrompt.indexOf("<history_transcript>");
        int requestIndex = structuredPrompt.indexOf("<current_request>");

        assertThat(runtimeIndex).isGreaterThanOrEqualTo(0);
        assertThat(workspaceIndex).isGreaterThan(runtimeIndex);
        assertThat(experienceIndex).isGreaterThan(workspaceIndex);
        assertThat(historyIndex).isGreaterThan(experienceIndex);
        assertThat(requestIndex).isGreaterThan(historyIndex);
        assertThat(structuredPrompt)
                .contains("[user] 上一轮历史消息")
                .contains("[assistant] 上一轮助手回复")
                .containsOnlyOnce("<history_transcript>")
                .doesNotContain("以下消息为历史 transcript")
                .doesNotContain("UserMessage:")
                .doesNotContain("AssistantMessage:");
        assertThat(result.hygieneReport().droppedEmptyAssistantMessages()).isEqualTo(1);
    }

    @Test
    void serializeForMultimodal_结构化提示词应原样输出而不是套用户消息前缀() {
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
                new SystemMessage("system"),
                new UserMessage("""
                        <runtime_context>
                        - 当前时间: 2026-03-24T10:00:00+08:00
                        </runtime_context>

                        <history_transcript>
                        [user] 历史消息
                        </history_transcript>

                        <current_request>
                        user
                        </current_request>
                        """),
                AssistantMessage.builder().content("thinking").toolCalls(List.of(toolCall)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "tool.search", "tool.search", "{\"summary\":\"ok\"}")))
                        .build()
        ));

        assertThat(serialized)
                .contains("[system]")
                .contains("<runtime_context>")
                .contains("<history_transcript>")
                .contains("<current_request>")
                .contains("[tool_call:tool.search]")
                .contains("[assistant]")
                .contains("[tool_result:tool.search]")
                .doesNotContain("[user] <runtime_context>");
    }

    @Test
    void build_前端进度占位不应重新注入给模型() {
        var builder = new ProviderMessageBuilder(
                new TranscriptHygieneEngine(new AgentConfigProperties())
        );
        var context = new AssembledContext(
                "system prompt",
                List.of(),
                List.of(),
                """
                        <runtime_context>
                        - 当前时间: 2026-03-25T17:30:00+08:00
                        </runtime_context>

                        <current_request>
                        继续执行
                        </current_request>
                        """,
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
                .traceId("trace-progress")
                .sessionId("session-progress")
                .goal("test")
                .channel("web")
                .steps(List.of(
                        new ReactStep.Progress("正在检索相关记忆和知识…"),
                        new ReactStep.Progress("正在思考回答…"),
                        new ReactStep.ToolCall("tool.search", "搜索", "{\"q\":\"novel\"}", 10),
                        new ReactStep.Observation("tool.search", "搜索", true, "{\"summary\":\"ok\"}", 12)
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
                .completionReason(null)
                .reasoningSummary(null)
                .completionMode(CompletionMode.NORMAL)
                .allowedToolIds(null)
                .pendingMedia(null)
                .earlyStopRejectCount(0)
                .suspended(false)
                .suspendReason(null)
                .build();

        var result = builder.build(context, state);

        assertThat(result.messages())
                .noneMatch(message -> message instanceof AssistantMessage assistantMessage
                        && ("正在检索相关记忆和知识…".equals(assistantMessage.getText())
                        || "正在思考回答…".equals(assistantMessage.getText())));
    }
}
