package com.lifepilot.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.conversation.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolExecutionCoordinator 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class ToolExecutionCoordinatorTest {

    @Test
    void 工具返回错误Envelope时应标记为失败() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        TranscriptStore transcriptStore = mock(TranscriptStore.class);
        when(agentToolProvider.resolveToolDisplayName("builtin.code.execute")).thenReturn("执行代码");

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                transcriptStore,
                null,
                null,
                null
        );

        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("执行测试代码", "session-1", "web", null, null,
                budget, null, 0, null, null, null, null);
        ReactAgentState state = ReactAgentState.init(request, budget);

        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("builtin.code.execute")
                    .description("执行代码")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"error\":\"代码执行失败: exitCode=9009\",\"status\":\"ERROR\"}";
            }
        };

        var toolCall = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "builtin.code.execute",
                "{\"language\":\"python\",\"code\":\"print('Hello')\"}"
        );

        ReactAgentState result = coordinator.execute(
                state,
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1)).isInstanceOf(ReactStep.Observation.class);
        var observation = (ReactStep.Observation) result.steps().get(1);
        assertThat(observation.success()).isFalse();
        assertThat(observation.output()).contains("exitCode=9009");

        verify(transcriptStore).appendToolResult(
                eq("session-1"),
                nullable(String.class),
                eq(result.traceId()),
                eq("builtin.code.execute"),
                eq("call-1"),
                eq(false),
                eq("{\"error\":\"代码执行失败: exitCode=9009\",\"status\":\"ERROR\"}"),
                nullable(String.class),
                eq(true),
                eq(false),
                nullable(java.time.Instant.class)
        );
    }
}
