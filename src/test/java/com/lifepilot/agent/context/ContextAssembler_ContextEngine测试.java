package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceItemKind;
import com.lifepilot.memory.workspace.WorkspaceStatus;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 与 ContextEngine 集成点测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class ContextAssembler_ContextEngine测试 {

    @Test
    void assemble_会把artifact与toolResult片段注入用户提示词() {
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
            return String.join("\n",
                    vars.get("compactionSection").toString(),
                    vars.get("conversationHistorySection").toString(),
                    vars.get("workspaceSection").toString(),
                    vars.get("artifactSection").toString(),
                    vars.get("toolResultsSection").toString());
        });

        when(contextEngine.load(any(ReactAgentState.class), anyInt())).thenReturn(new ContextEngine.ContextSnapshot(
                List.of(
                        new ConversationTurnView("session-1", "user", "上一轮用户消息", Instant.parse("2026-03-23T12:00:00Z"), null),
                        new ConversationTurnView("session-1", "assistant", "上一轮助手回复", Instant.parse("2026-03-23T12:00:03Z"), null)
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
                "\n历史压缩摘要:\n之前已确认需求范围与约束。\n",
                "\n最近产物:\n- [report] 方案摘要: 这是最新摘要\n",
                "\n最近工具结果:\n- [tool.search] 成功: 命中 3 条结果\n",
                true,
                false,
                15,
                12,
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
                null,
                contextEngine
        );

        AssembledContext context = assembler.assemble(buildState());

        assertThat(context.userPrompt())
                .contains("历史压缩摘要")
                .contains("上一轮用户消息")
                .contains("草稿卡片")
                .contains("最近产物")
                .contains("方案摘要")
                .contains("最近工具结果")
                .contains("tool.search");
        assertThat(context.tokenBudget().toolResultUsed()).isPositive();
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
