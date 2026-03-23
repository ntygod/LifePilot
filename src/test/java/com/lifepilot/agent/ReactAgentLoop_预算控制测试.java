package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.config.threadpool.SharedScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ReactAgentLoop 预算控制测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentLoop_预算控制测试 {

    @Mock
    private ContextAssembler contextAssembler;

    @Mock
    private AgentToolProvider agentToolProvider;

    @Mock
    private SharedScheduler sharedScheduler;

    private ReactAgentLoop reactAgentLoop;

    @BeforeEach
    void setUp() {
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

        reactAgentLoop = new ReactAgentLoop(
                contextAssembler,
                agentToolProvider,
                new AgentConfigProperties(),
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sharedScheduler
        );
    }

    @Test
    void 时间预算耗尽时_立即返回降级响应() {
        var budget = Budget.builder()
                .maxTokens(32000).tokensUsed(0).tokensReserved(0)
                .maxSteps(10).stepsUsed(0)
                .maxDuration(Duration.ofSeconds(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("测试超时", "session-timeout", "web", null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);
        var callback = mock(IterationCallback.class);

        var result = reactAgentLoop.coreLoop(
                initialState,
                request,
                null,
                Instant.now().minusSeconds(5),
                callback,
                new CancellationToken(),
                new AgentLoopContext()
        );

        assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
        assertThat(result.terminationReason()).contains("时间预算耗尽");
        assertThat(result.finalOutput()).isNotBlank();
        verifyNoInteractions(callback);
    }

    @Test
    void 步骤预算耗尽时_生成统一降级终止响应() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(new AssembledContext(
                "你是测试助手",
                "请执行测试任务",
                List.of(),
                TokenBudget.allocateDefault(4096),
                0,
                0.0f,
                0,
                false,
                List.of(),
                null
        ));

        var budget = Budget.builder()
                .maxTokens(32000).tokensUsed(0).tokensReserved(0)
                .maxSteps(1).stepsUsed(0)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("测试步骤预算", "session-steps", "web", null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);

        var llmCallCount = new AtomicInteger();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            llmCallCount.incrementAndGet();
            var toolCall = new AssistantMessage.ToolCall("missing-tool", "function", "missing-tool", "{}");
            var assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        };

        var result = reactAgentLoop.coreLoop(
                initialState,
                request,
                null,
                Instant.now(),
                callback,
                new CancellationToken(),
                new AgentLoopContext()
        );

        assertThat(llmCallCount.get()).isEqualTo(1);
        assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
        assertThat(result.terminationReason()).contains("步骤预算耗尽");
        assertThat(result.finalOutput()).isNotBlank();
        assertThat(result.budget().stepsUsed()).isEqualTo(1);
    }
}
