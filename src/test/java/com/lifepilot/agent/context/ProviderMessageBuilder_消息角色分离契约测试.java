package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.llm.thinking.ReasoningContentMarker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息角色分离（R10）契约回归测试。
 *
 * <p>锁定 P9 角色分离后多轮 ReAct 的 provider 消息序列不变量，配套真实跨 provider
 * 冒烟验证（DeepSeek thinking 模式）一并覆盖 R10.3 / R10.4：</p>
 * <ul>
 *   <li>稳定 system 前缀：多轮工具调用下有且仅有一条 {@link SystemMessage} 居首，
 *       利于 provider prompt caching（R10.1 / R10.2）；</li>
 *   <li>tool_call 与 tool_response 严格配对：每条带 tool_calls 的 assistant 消息
 *       紧跟一条 callId 匹配的 {@link ToolResponseMessage}（R10.3）；</li>
 *   <li>reasoning_content 多轮契约：thinking 模式（reasoning 非 null，含空串）的
 *       tool_call assistant 消息编码 {@link ReasoningContentMarker}；非 thinking 模式
 *       （reasoning 为 null）不编码（R10.3）；</li>
 *   <li>decision_context 落在 user 段而非 system 前缀（P9 prompt caching 收益前提）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-06-12
 */
class ProviderMessageBuilder_消息角色分离契约测试 {

    private ProviderMessageBuilder newBuilder() {
        return new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties()));
    }

    @Test
    void 多轮工具调用_有且仅有一条system消息居首且角色分离() {
        var result = newBuilder().build(contextWithDecisionSignal(), twoRoundToolCallState());
        List<Message> messages = result.messages();

        long systemCount = messages.stream().filter(m -> m instanceof SystemMessage).count();
        assertThat(systemCount).as("多轮间应保持单一稳定 system 前缀").isEqualTo(1);
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);

        // 系统提示词本身不应承载每轮变化的 decision_context（应在 user 段，利于缓存）
        assertThat(((SystemMessage) messages.getFirst()).getText())
                .doesNotContain("<decision_context>");
        assertThat(((UserMessage) messages.get(1)).getText())
                .contains("<decision_context>");
    }

    @Test
    void 多轮工具调用_toolCall与toolResponse严格配对() {
        var result = newBuilder().build(contextWithDecisionSignal(), twoRoundToolCallState());
        List<Message> messages = result.messages();

        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage am && am.hasToolCalls()) {
                assertThat(i + 1).as("带 tool_calls 的 assistant 后必须有消息").isLessThan(messages.size());
                Message next = messages.get(i + 1);
                assertThat(next).as("tool_calls 后紧跟 tool_response").isInstanceOf(ToolResponseMessage.class);
                String assistantCallId = am.getToolCalls().getFirst().id();
                String responseId = ((ToolResponseMessage) next).getResponses().getFirst().id();
                assertThat(responseId).as("callId 配对").isEqualTo(assistantCallId);
            }
        }
    }

    @Test
    void thinking模式_toolCall_assistant消息编码reasoning_content_marker() {
        var result = newBuilder().build(contextWithDecisionSignal(), twoRoundToolCallState());
        List<Message> messages = result.messages();

        // 第一轮 reasoning 非空、第二轮 reasoning 为空串 —— 两者都属 thinking 模式，均需编码 marker
        List<AssistantMessage> toolCallAssistants = messages.stream()
                .filter(m -> m instanceof AssistantMessage am && am.hasToolCalls())
                .map(m -> (AssistantMessage) m)
                .toList();
        assertThat(toolCallAssistants).hasSize(2);
        assertThat(ReasoningContentMarker.hasMarker(toolCallAssistants.get(0).getText()))
                .as("非空 reasoning 编码 marker").isTrue();
        assertThat(ReasoningContentMarker.hasMarker(toolCallAssistants.get(1).getText()))
                .as("空串 reasoning（thinking 模式短答案）仍编码 marker，DeepSeek 多轮契约要求回传字段").isTrue();
    }

    @Test
    void 非thinking模式_reasoning为null时不编码marker() {
        var state = singleRoundState(null);
        var result = newBuilder().build(contextWithDecisionSignal(), state);

        AssistantMessage toolCallAssistant = result.messages().stream()
                .filter(m -> m instanceof AssistantMessage am && am.hasToolCalls())
                .map(m -> (AssistantMessage) m)
                .findFirst()
                .orElseThrow();
        assertThat(ReasoningContentMarker.hasMarker(toolCallAssistant.getText()))
                .as("非 thinking 模式（reasoning=null）不编码 marker").isFalse();
    }

    // ==================== helpers ====================

    private AssembledContext contextWithDecisionSignal() {
        return new AssembledContext(
                "system prompt（角色/工具协议/catalog 稳定前缀）",
                List.of(new AssistantMessage(
                        "<decision_context>\n历史经验：优先复用既有方案\n</decision_context>")),
                List.of(),
                """
                        <current_request>
                        多轮工具调用契约验证
                        </current_request>
                        """,
                List.of(),
                TokenBudget.allocateDefault(8192),
                0,
                0.0f,
                0,
                false,
                List.of(),
                null
        );
    }

    private ReactAgentState twoRoundToolCallState() {
        return baseState("trace-multi-round", "session-multi-round", List.of(
                new ReactStep.Thought("先搜索资料"),
                new ReactStep.ToolCall("web.search", "搜索", "{\"q\":\"context\"}", 10, "call-1", "我应该先搜索"),
                new ReactStep.Observation("web.search", "搜索", true, "{\"answer\":\"ok\"}", 12, "call-1"),
                new ReactStep.Thought("再读取文件"),
                new ReactStep.ToolCall("file.read", "读取", "{\"path\":\"a.txt\"}", 10, "call-2", ""),
                new ReactStep.Observation("file.read", "读取", true, "{\"content\":\"data\"}", 12, "call-2"),
                new ReactStep.Answer("综合得出最终答案")
        ));
    }

    private ReactAgentState singleRoundState(String reasoning) {
        return baseState("trace-single", "session-single", List.of(
                new ReactStep.Thought("调用工具"),
                new ReactStep.ToolCall("web.search", "搜索", "{\"q\":\"x\"}", 10, "call-x", reasoning),
                new ReactStep.Observation("web.search", "搜索", true, "{\"answer\":\"ok\"}", 12, "call-x"),
                new ReactStep.Answer("答案")
        ));
    }

    private ReactAgentState baseState(String traceId, String sessionId, List<ReactStep> steps) {
        return ReactAgentState.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .goal("test")
                .channel("web")
                .steps(steps)
                .stepCount(steps.size())
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(8000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(20)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(5))
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
    }
}
