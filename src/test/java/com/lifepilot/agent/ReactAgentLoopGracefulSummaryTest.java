package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ProviderMessageBuilder;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.context.TranscriptHygieneEngine;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.observability.guardrail.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * ReactAgentLoop 降级收尾总结测试。
 *
 * @author zsg
 * @since 2026-05-04
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentLoopGracefulSummaryTest {

    @Mock
    private com.lifepilot.agent.context.ContextAssembler contextAssembler;

    @Mock
    private AgentToolProvider agentToolProvider;

    @Mock
    private SharedScheduler sharedScheduler;

    @Mock
    private GenerationRouter generationRouter;

    @Test
    void 降级收尾摘要应包含原始任务和已获取工具结果() {
        var config = new AgentConfigProperties();
        config.getLoop().setMaxIterations(1);

        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);
        when(contextAssembler.assemble(any())).thenReturn(基础上下文("调研今日国内外AI资讯"));
        准备搜索工具();

        var loop = 创建Loop(config);

        var budget = 基础预算();
        var request = new AgentRequest("调研今日国内外AI资讯", "session-summary", "web",
                null, null, budget, null, 0, null, null, null, null);
        var initialState = com.lifepilot.agent.model.ReactAgentState.init(request, budget);

        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            var toolCall = new AssistantMessage.ToolCall(
                    "call-search-1", "function", "web.search", "{\"query\":\"AI news\"}");
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
        };

        var result = loop.coreLoop(
                initialState,
                request,
                null,
                Instant.now(),
                callback,
                new CancellationToken(),
                new AgentLoopContext()
        );

        assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
        assertThat(result.terminationReason()).contains("硬限制");
        assertThat(result.finalOutput())
                .contains("调研今日国内外AI资讯")
                .contains("web.search")
                .contains("OpenAI发布新模型")
                .contains("国内大模型更新");
    }

    @Test
    void 有生成路由时收尾摘要Prompt应携带工具结果摘要() {
        var config = new AgentConfigProperties();
        config.getLoop().setMaxIterations(1);

        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);
        when(contextAssembler.assemble(any())).thenReturn(基础上下文("调研今日国内外AI资讯"));
        准备搜索工具();
        when(generationRouter.call(
                eq("graceful_summary"),
                anyString(),
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                eq(GenerationCapability.CHAT),
                nullable(Duration.class),
                eq(true)
        )).thenReturn(new LlmResponse("模型生成的收尾摘要", null, null, List.of(), Map.of(), 0, 0, null, 0, "mock", "mock-model", 1, false));

        var loop = 创建Loop(config);
        loop.setGenerationRouter(generationRouter);

        var budget = 基础预算();
        var request = new AgentRequest("调研今日国内外AI资讯", "session-summary-router", "web",
                null, null, budget, null, 0, null, null, null, null);
        var initialState = com.lifepilot.agent.model.ReactAgentState.init(request, budget);

        var result = loop.coreLoop(
                initialState,
                request,
                null,
                Instant.now(),
                搜索ToolCall回调(),
                new CancellationToken(),
                new AgentLoopContext()
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationRouter).call(
                eq("graceful_summary"),
                promptCaptor.capture(),
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                eq(GenerationCapability.CHAT),
                nullable(Duration.class),
                eq(true)
        );

        assertThat(result.finalOutput()).isEqualTo("模型生成的收尾摘要");
        assertThat(promptCaptor.getValue())
                .contains("调研今日国内外AI资讯")
                .contains("web.search")
                .contains("OpenAI发布新模型")
                .contains("国内大模型更新");
    }

    private Budget 基础预算() {
        return Budget.builder()
                .maxTokens(32000)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(8)
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ZERO)
                .build();
    }

    private AssembledContext 基础上下文(String userPrompt) {
        return new AssembledContext(
                "你是测试助手",
                List.of(),
                List.of(),
                userPrompt,
                List.of(),
                TokenBudget.allocateDefault(4096),
                0,
                0.0f,
                0,
                false,
                List.of(),
                null
        );
    }

    private ReactAgentLoop 创建Loop(AgentConfigProperties config) {
        return new ReactAgentLoop(
                contextAssembler,
                new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                agentToolProvider,
                config,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sharedScheduler,
                null,
                null
        );
    }

    private void 准备搜索工具() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of(
                创建工具回调("web.search", "互联网搜索",
                        "{\"status\":\"OK\",\"data\":{\"query\":\"AI news\",\"summary\":\"OpenAI发布新模型，国内大模型更新\"}}")
        ));
        when(agentToolProvider.resolveCanonicalToolId(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentToolProvider.resolveToolDisplayName("web.search")).thenReturn("互联网搜索");
        when(agentToolProvider.resolveToolRiskLevel("web.search")).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(anyString(), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.sequential());
    }

    private IterationCallback 搜索ToolCall回调() {
        return (agentRequest, messages, toolCallbacks, traceContext) -> {
            var toolCall = new AssistantMessage.ToolCall(
                    "call-search-1", "function", "web.search", "{\"query\":\"AI news\"}");
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
        };
    }

    private ToolCallback 创建工具回调(String name, String description, String returnValue) {
        return new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("{}")
                    .build();

            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                return returnValue;
            }
        };
    }
}
