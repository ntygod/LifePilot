package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceItemKind;
import com.lifepilot.memory.workspace.WorkspaceStatus;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 与 ContextEngine 集成测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class ContextAssembler_ContextEngine测试 {

    @Test
    void assemble_应保留运行时上下文与当前请求边界且历史消息不再包含重复包装标签() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var contextEngine = mock(ContextEngine.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");
        when(promptRegistry.render(eq("memory/agentic-tool-guide"))).thenReturn("");
        when(promptRegistry.render(eq("agent/react-user-prompt"), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = invocation.getArgument(1, Map.class);
            return List.of(
                            """
                            <runtime_context>
                            - 当前时间: %s
                            - 当前时区: %s
                            - 当前操作系统: %s %s
                            - 当前通道: %s
                            </runtime_context>
                            """.formatted(
                                    vars.get("currentDateTime"),
                                    vars.get("timezone"),
                                    vars.get("osName"),
                                    vars.get("osVersion"),
                                    vars.get("channel")
                            ).trim(),
                            "<current_request>\n" + vars.get("userGoal") + "\n</current_request>"
                    ).stream()
                    .filter(section -> !section.isBlank())
                    .collect(Collectors.joining("\n\n"));
        });

        when(contextEngine.load(any(ReactAgentState.class), anyInt())).thenReturn(new ContextEngine.ContextSnapshot(
                List.of(
                        new AssistantMessage("<history_summary>\n之前已确认需求范围与约束。\n</history_summary>"),
                        new UserMessage("上一轮用户消息"),
                        AssistantMessage.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-search",
                                        "function",
                                        "tool.search",
                                        "{\"q\":\"方案\"}"
                                )))
                                .build(),
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        "tool.search",
                                        "tool.search",
                                        "命中 3 条结果"
                                )))
                                .build(),
                        new AssistantMessage("上一轮助手回答")
                ),
                List.of(new WorkspaceItem(
                        "workspace-1",
                        "session-1",
                        WorkspaceItemKind.WORKING_SET,
                        "草稿卡片",
                        "上下文里的工作区卡片",
                        null,
                        WorkspaceStatus.ACTIVE,
                        0,
                        null,
                        null,
                        null,
                        Instant.parse("2026-03-23T12:00:04Z"),
                        Instant.parse("2026-03-23T12:00:04Z")
                )),
                "\n最近产物:\n- [report] 方案摘要: 这是最新摘要\n",
                true,
                false,
                42,
                12,
                15,
                Map.of("source", "test")
        ));

        var assembler = new ContextAssembler(
                config,
                promptRegistry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextEngine
        );

        AssembledContext context = assembler.assemble(buildState());

        assertThat(context.userPrompt())
                .contains("<runtime_context>")
                .contains("当前时间")
                .contains("当前时区")
                .contains("当前操作系统")
                .contains("<current_request>")
                .contains("请整理一下方案");
        assertThat(context.systemPrompt())
                .contains("system prompt")
                .doesNotContain("<runtime_context>")
                .doesNotContain("上一轮用户消息")
                .doesNotContain("tool.search")
                .doesNotContain("草稿卡片")
                .doesNotContain("方案摘要");
        assertThat(context.contextMessages()).hasSize(2);
        assertThat(context.contextMessages().getFirst().getText())
                .contains("<workspace_context>")
                .contains("草稿卡片");
        assertThat(context.contextMessages().getLast().getText())
                .contains("<artifact_context>")
                .contains("方案摘要");
        assertThat(context.historyMessages()).hasSize(5);
        assertThat(context.historyMessages().get(0).getText()).contains("<history_summary>");
        assertThat(context.historyMessages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(context.historyMessages().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(context.historyMessages().get(3)).isInstanceOf(ToolResponseMessage.class);
        assertThat(context.historyMessages().get(4)).isInstanceOf(AssistantMessage.class);
        assertThat(context.historyMessages()).allSatisfy(message -> {
            if (message.getText() != null) {
                assertThat(message.getText()).doesNotContain("<history_transcript>");
            }
        });
        assertThat(context.tokenBudget().historyUsed()).isEqualTo(42);
        assertThat(context.tokenBudget().toolResultUsed()).isEqualTo(15);
        assertThat(context.tokenBudget().memoryUsed()).isPositive();
        verify(contextEngine).load(any(ReactAgentState.class), anyInt());
        verify(contextEngine).recordReport(any(), any(), any(), anyInt(), anyInt());
    }

    private AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(4096);
        config.getContext().setOutputReservedTokens(512);
        return config;
    }

    private ReactAgentState buildState() {
        return ReactAgentState.builder()
                .traceId("trace-assembler")
                .sessionId("session-1")
                .goal("请整理一下方案")
                .channel("web")
                .steps(List.of())
                .stepCount(0)
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
    }
}
