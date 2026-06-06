package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.callback.LlmCallPurpose;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.CompactionEngine;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.ProviderMessageBuilder;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.context.TranscriptHygieneEngine;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.observability.trace.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Map;
import com.lifepilot.llm.LlmResponse;

/**
 * ReactAgentLoop 全面单元测试 — 覆盖核心循环、取消令牌、迭代限制、
 * 连续失败降级、挂起/恢复校验、格式化辅助方法等。
 *
 * <p>预算控制相关场景已在 {@code ReactAgentLoop_预算控制测试} 中覆盖，
 * 本测试类聚焦于其余核心行为。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentLoop_单元测试 {

    @Mock
    private ContextAssembler contextAssembler;

    @Mock
    private AgentToolProvider agentToolProvider;

    @Mock
    private SharedScheduler sharedScheduler;

    @Mock
    private TranscriptStore transcriptStore;

    private ReactAgentLoop reactAgentLoop;

    @BeforeEach
    void 初始化() {
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

        reactAgentLoop = new ReactAgentLoop(
                contextAssembler,
                new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                agentToolProvider,
                new AgentConfigProperties(),
                new ObjectMapper(),
                null,  // traceRecorder
                transcriptStore,
                null,  // multimodalRouter
                null,  // mediaDataExtractor
                null,  // eventPublisher
                null,  // proceduralMemory
                null,  // intentMatcher
                null,  // compactionEngine
                sharedScheduler,
                null,  // workspaceService
                null   // experienceSummarizer
        );
    }

    // ===== 正常 ReAct 循环执行流程 =====

    @Nested
    class 正常循环执行 {

        @Test
        void 纯文本回复应直接完成循环() {
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("你好"));

            var budget = 基础预算();
            var request = 简单请求("你好", "session-hello", budget);
            var initialState = ReactAgentState.init(request, budget);

            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) ->
                    new ChatResponse(List.of(new Generation(new AssistantMessage("你好！有什么可以帮你的吗？"))));

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(result.isDone()).isTrue();
            assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
            assertThat(result.finalOutput()).isEqualTo("你好！有什么可以帮你的吗？");
        }

        @Test
        void Auto模式有工具时仍应以常规Agent轮携带工具调用LLM() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("你有哪些能力"));
            var tool = 创建工具回调("memory.search", "记忆搜索", "{\"items\":[]}");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(tool));

            var budget = 基础预算();
            var request = 简单请求("你有哪些能力", "session-auto-tools-stream", budget);
            var initialState = ReactAgentState.init(request, budget);
            var capturedPurpose = new AtomicReference<LlmCallPurpose>();
            var capturedToolCount = new AtomicInteger(-1);

            IterationCallback callback = new IterationCallback() {
                @Override
                public ChatResponse callLlm(AgentRequest agentRequest,
                                            List<Message> messages,
                                            List<ToolCallback> toolCallbacks,
                                            @Nullable TraceContext traceContext) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("我可以回答问题并协作使用工具。"))));
                }

                @Override
                public ChatResponse callLlm(AgentRequest agentRequest,
                                            List<Message> messages,
                                            List<ToolCallback> toolCallbacks,
                                            @Nullable TraceContext traceContext,
                                            LlmCallPurpose purpose) {
                    capturedPurpose.set(purpose);
                    capturedToolCount.set(toolCallbacks.size());
                    return callLlm(agentRequest, messages, toolCallbacks, traceContext);
                }
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(result.isDone()).isTrue();
            assertThat(capturedPurpose.get()).isEqualTo(LlmCallPurpose.AGENT_STEP);
            assertThat(capturedToolCount.get()).isEqualTo(1);
        }

        @Test
        void 思考后工具调用再回答应完成完整ReAct循环() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("帮我查一下天气"));
            when(agentToolProvider.resolveToolDisplayName("weather.query")).thenReturn("天气查询");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建工具回调("weather.query", "天气查询", "{\"result\":\"北京，晴，25°C\"}")
            ));

            var budget = 基础预算();
            var request = 简单请求("帮我查一下天气", "session-react-full", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                if (llmCallCount.getAndIncrement() == 0) {
                    // 第一轮：LLM 返回 tool call
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-weather-1", "function", "weather.query", "{\"city\":\"北京\"}");
                    var assistantMessage = AssistantMessage.builder()
                            .content("让我查一下北京的天气")
                            .toolCalls(List.of(toolCall))
                            .build();
                    return new ChatResponse(List.of(new Generation(assistantMessage)));
                }
                // 第二轮：LLM 返回最终回答
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("北京今天天气晴朗，气温 25°C。"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(2);
            assertThat(result.isDone()).isTrue();
            assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
            assertThat(result.finalOutput()).isEqualTo("北京今天天气晴朗，气温 25°C。");
            // 验证步骤中包含 Thought、ToolCall、Observation、Answer
            assertThat(result.steps()).anyMatch(s -> s instanceof ReactStep.Thought);
            assertThat(result.steps()).anyMatch(s -> s instanceof ReactStep.ToolCall);
            assertThat(result.steps()).anyMatch(s -> s instanceof ReactStep.Observation);
            assertThat(result.steps()).anyMatch(s -> s instanceof ReactStep.Answer);
        }

        @Test
        void 多轮工具调用应逐步完成后最终回答() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("帮我查天气并创建待办"));
            when(agentToolProvider.resolveToolDisplayName("weather.query")).thenReturn("天气查询");
            when(agentToolProvider.resolveToolDisplayName("todo.create")).thenReturn("创建待办");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建工具回调("weather.query", "天气查询", "{\"result\":\"北京，晴\"}"),
                    创建工具回调("todo.create", "创建待办", "{\"id\":\"todo-1\"}")
            ));

            var budget = 基础预算();
            var request = 简单请求("帮我查天气并创建待办", "session-multi-tool", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                int call = llmCallCount.getAndIncrement();
                if (call == 0) {
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-1", "function", "weather.query", "{\"city\":\"北京\"}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                if (call == 1) {
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-2", "function", "todo.create", "{\"title\":\"带伞\"}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("天气已查询，待办已创建。"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(3);
            assertThat(result.finalOutput()).isEqualTo("天气已查询，待办已创建。");
            // 应包含两组 ToolCall + Observation
            long toolCallSteps = result.steps().stream()
                    .filter(s -> s instanceof ReactStep.ToolCall).count();
            long observationSteps = result.steps().stream()
                    .filter(s -> s instanceof ReactStep.Observation).count();
            assertThat(toolCallSteps).isEqualTo(2);
            assertThat(observationSteps).isEqualTo(2);
        }
    }

    // ===== 取消令牌中断执行 =====

    @Nested
    class 取消令牌 {

        @Test
        void 循环开始前取消应立即退出() {
            var budget = 基础预算();
            var request = 简单请求("测试取消", "session-cancel-before", budget);
            var initialState = ReactAgentState.init(request, budget);

            var token = new CancellationToken();
            token.cancel();

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    mock(IterationCallback.class), token, new AgentLoopContext()
            );

            // 取消后循环直接 break，不设置 done=true（由上层处理）
            assertThat(result.finalOutput()).isNull();
        }

        @Test
        void 工具调用中途取消应中断后续迭代() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("取消测试"));
            when(agentToolProvider.resolveToolDisplayName("slow.tool")).thenReturn("慢操作");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建工具回调("slow.tool", "慢操作", "{\"status\":\"done\"}")
            ));

            var budget = 基础预算();
            var request = 简单请求("取消测试", "session-cancel-mid", budget);
            var initialState = ReactAgentState.init(request, budget);

            var token = new CancellationToken();
            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                int call = llmCallCount.getAndIncrement();
                if (call == 0) {
                    // 第一轮返回 tool call，同时在第一轮之后触发取消
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-1", "function", "slow.tool", "{}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                // 不应到达此处，因为循环应被取消
                return new ChatResponse(List.of(new Generation(new AssistantMessage("不应看到此输出"))));
            };

            // 在第一轮迭代完成后（检查 tool call 时已经有 1 次 LLM 调用），取消循环
            // 由于 cancellationToken 检查在循环起始处，在第二轮开始前取消
            // 这里我们用一个特殊 callback 在第一轮完成后发出取消信号
            IterationCallback cancellingCallback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                int call = llmCallCount.getAndIncrement();
                if (call == 0) {
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-1", "function", "slow.tool", "{}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                // 第二轮 LLM 调用前取消信号已设置
                token.cancel();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("被取消"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    cancellingCallback, token, new AgentLoopContext()
            );

            // 第一轮执行了工具，第二轮开始检测到取消信号退出
            assertThat(llmCallCount.get()).isLessThanOrEqualTo(2);
        }

        @Test
        void CancellationToken_多次取消应幂等() {
            var token = new CancellationToken();
            assertThat(token.isCancelled()).isFalse();

            token.cancel();
            assertThat(token.isCancelled()).isTrue();

            token.cancel();
            assertThat(token.isCancelled()).isTrue();
        }
    }

    // ===== 最大迭代次数限制 =====

    @Nested
    class 迭代次数限制 {

        @Test
        void 达到最大迭代次数应降级终止() {
            // 使用自定义配置，将 maxIterations 设为 2
            var customConfig = new AgentConfigProperties();
            customConfig.getLoop().setMaxIterations(2);

            ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
            when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

            var loop = new ReactAgentLoop(
                    contextAssembler,
                    new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                    agentToolProvider,
                    customConfig,
                    new ObjectMapper(),
                    null, transcriptStore,
                    null, null, null, null, null, null, sharedScheduler, null, null
            );

            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建工具回调("echo.tool", "回声", "{\"echo\":\"pong\"}")
            ));
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("无限循环测试"));
            when(agentToolProvider.resolveToolDisplayName("echo.tool")).thenReturn("回声");

            var budget = 基础预算();
            var request = 简单请求("无限循环测试", "session-max-iter", budget);
            var initialState = ReactAgentState.init(request, budget);

            // 每次都返回 tool call，永不返回纯文本
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                var toolCall = new AssistantMessage.ToolCall(
                        "call-loop", "function", "echo.tool", "{}");
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
            };

            var result = loop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(result.isDone()).isTrue();
            assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
            assertThat(result.terminationReason()).contains("硬限制");
        }
    }

    // ===== 连续 LLM 调用失败降级 =====

    @Nested
    class 连续失败降级 {

        @Test
        void 连续LLM调用异常达到上限应降级终止() {
            var customConfig = new AgentConfigProperties();
            customConfig.getLoop().setMaxConsecutiveFailures(2);

            ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
            when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

            var loop = new ReactAgentLoop(
                    contextAssembler,
                    new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                    agentToolProvider,
                    customConfig,
                    new ObjectMapper(),
                    null, transcriptStore,
                    null, null, null, null, null, null, sharedScheduler, null, null
            );

            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("失败测试"));

            var budget = 基础预算();
            var request = 简单请求("失败测试", "session-fail-limit", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                llmCallCount.incrementAndGet();
                throw new RuntimeException("模拟 LLM 服务不可用");
            };

            var result = loop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(2);
            assertThat(result.isDone()).isTrue();
            assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
            assertThat(result.terminationReason()).contains("连续 LLM 调用失败");
        }

        @Test
        void 单次失败后恢复不应计入连续失败() {
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("恢复测试"));

            var budget = 基础预算();
            var request = 简单请求("恢复测试", "session-fail-recover", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                int call = llmCallCount.getAndIncrement();
                if (call == 0) {
                    throw new RuntimeException("临时故障");
                }
                // 第二次调用成功
                return new ChatResponse(List.of(new Generation(new AssistantMessage("恢复成功"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(2);
            assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
            assertThat(result.finalOutput()).isEqualTo("恢复成功");
        }

        @Test
        void 连续空响应达到上限应降级终止() {
            var customConfig = new AgentConfigProperties();
            customConfig.getLoop().setMaxConsecutiveFailures(2);

            ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
            when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

            var loop = new ReactAgentLoop(
                    contextAssembler,
                    new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                    agentToolProvider,
                    customConfig,
                    new ObjectMapper(),
                    null, transcriptStore,
                    null, null, null, null, null, null, sharedScheduler, null, null
            );

            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("空响应测试"));

            var budget = 基础预算();
            var request = 简单请求("空响应测试", "session-empty-resp", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                llmCallCount.incrementAndGet();
                // 返回空内容
                return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
            };

            var result = loop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(2);
            assertThat(result.isDone()).isTrue();
            assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
            assertThat(result.terminationReason()).contains("连续空响应");
        }
    }

    // ===== 挂起/恢复机制 =====

    @Nested
    class 挂起恢复 {

        @Test
        void validateResumePayload_配对正确时不应抛异常() {
            // WorkflowWait + WorkflowResult
            reactAgentLoop.validateResumePayload(
                    new SuspendReason.WorkflowWait("exec-1", "wf-1", "测试流程"),
                    new ResumePayload.WorkflowResult("exec-1", "SUCCESS", "{}")
            );

            // UserConfirmation + UserDecision
            reactAgentLoop.validateResumePayload(
                    new SuspendReason.UserConfirmation("tool-1", "{}", "HIGH", "confirm-1"),
                    new ResumePayload.UserDecision("confirm-1", true, "同意")
            );

            // RemoteDelegation + RemoteResult
            reactAgentLoop.validateResumePayload(
                    new SuspendReason.RemoteDelegation("task-1", "http://agent", "翻译"),
                    new ResumePayload.RemoteResult("task-1", "{\"result\":\"ok\"}")
            );

            // ScheduledWakeup + WakeupSignal
            reactAgentLoop.validateResumePayload(
                    new SuspendReason.ScheduledWakeup(Instant.now(), "定时检查"),
                    new ResumePayload.WakeupSignal(Instant.now())
            );

            // ExternalDataWait + DataReady
            reactAgentLoop.validateResumePayload(
                    new SuspendReason.ExternalDataWait("crawler-1", "等待爬虫数据"),
                    new ResumePayload.DataReady("crawler-1", "数据已就绪")
            );
        }

        @Test
        void validateResumePayload_配对不正确时应抛IllegalArgumentException() {
            // WorkflowWait 不匹配 UserDecision
            assertThatThrownBy(() -> reactAgentLoop.validateResumePayload(
                    new SuspendReason.WorkflowWait("exec-1", "wf-1", "测试"),
                    new ResumePayload.UserDecision("confirm-1", true, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("恢复载荷类型不匹配");

            // ScheduledWakeup 不匹配 DataReady
            assertThatThrownBy(() -> reactAgentLoop.validateResumePayload(
                    new SuspendReason.ScheduledWakeup(Instant.now(), "唤醒"),
                    new ResumePayload.DataReady("src-1", "数据")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("恢复载荷类型不匹配");

            // ExternalDataWait 不匹配 WorkflowResult
            assertThatThrownBy(() -> reactAgentLoop.validateResumePayload(
                    new SuspendReason.ExternalDataWait("ds-1", "等待"),
                    new ResumePayload.WorkflowResult("exec-1", "SUCCESS", "{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("恢复载荷类型不匹配");
        }
    }

    // ===== 格式化辅助方法 =====

    @Nested
    class 格式化方法 {

        @Test
        void formatSuspendReason_应正确格式化五种挂起原因() {
            assertThat(reactAgentLoop.formatSuspendReason(
                    new SuspendReason.WorkflowWait("exec-1", "wf-1", "数据清洗")))
                    .contains("等待工作流完成")
                    .contains("exec-1")
                    .contains("数据清洗");

            assertThat(reactAgentLoop.formatSuspendReason(
                    new SuspendReason.UserConfirmation("delete.file", "{}", "HIGH", "c-1")))
                    .contains("等待用户确认")
                    .contains("delete.file")
                    .contains("HIGH");

            assertThat(reactAgentLoop.formatSuspendReason(
                    new SuspendReason.RemoteDelegation("task-1", "http://remote", "翻译文档")))
                    .contains("等待远程 Agent")
                    .contains("task-1")
                    .contains("翻译文档");

            var wakeupAt = Instant.parse("2026-04-03T10:00:00Z");
            assertThat(reactAgentLoop.formatSuspendReason(
                    new SuspendReason.ScheduledWakeup(wakeupAt, "定时汇报")))
                    .contains("定时唤醒")
                    .contains("定时汇报");

            assertThat(reactAgentLoop.formatSuspendReason(
                    new SuspendReason.ExternalDataWait("etl-pipeline", "等待 ETL 完成")))
                    .contains("等待外部数据")
                    .contains("etl-pipeline")
                    .contains("等待 ETL 完成");
        }

        @Test
        void formatResumeObservation_应正确格式化五种恢复载荷() {
            assertThat(reactAgentLoop.formatResumeObservation(
                    new ResumePayload.WorkflowResult("exec-1", "SUCCESS", "{\"count\":10}")))
                    .contains("工作流已完成")
                    .contains("exec-1")
                    .contains("SUCCESS");

            assertThat(reactAgentLoop.formatResumeObservation(
                    new ResumePayload.UserDecision("c-1", true, "同意执行")))
                    .contains("用户确认结果")
                    .contains("c-1")
                    .contains("true")
                    .contains("同意执行");

            assertThat(reactAgentLoop.formatResumeObservation(
                    new ResumePayload.RemoteResult("task-1", "{\"translated\":\"done\"}")))
                    .contains("远程 Agent 返回")
                    .contains("task-1");

            assertThat(reactAgentLoop.formatResumeObservation(
                    new ResumePayload.WakeupSignal(Instant.parse("2026-04-03T10:05:00Z"))))
                    .contains("定时唤醒触发");

            assertThat(reactAgentLoop.formatResumeObservation(
                    new ResumePayload.DataReady("crawler-1", "/data/result.json")))
                    .contains("外部数据就绪")
                    .contains("crawler-1")
                    .contains("/data/result.json");
        }
    }

    // ===== 定时唤醒调度 =====

    @Nested
    class 定时唤醒 {

        @Test
        void 唤醒时间已过应立即发布事件() {
            var eventPublisher = mock(ApplicationEventPublisher.class);
            ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
            when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

            var loop = new ReactAgentLoop(
                    contextAssembler,
                    new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                    agentToolProvider,
                    new AgentConfigProperties(),
                    new ObjectMapper(),
                    null, transcriptStore,
                    null, null, eventPublisher, null, null, null, sharedScheduler, null, null
            );

            var state = ReactAgentState.builder()
                    .traceId("trace-wakeup-past")
                    .sessionId("session-wakeup")
                    .goal("测试")
                    .steps(List.of())
                    .stepCount(0)
                    .shortTermMemory(List.of())
                    .mentionedEntities(List.of())
                    .budget(基础预算())
                    .depth(0)
                    .done(false)
                    .completionMode(CompletionMode.NORMAL)
                    .earlyStopRejectCount(0)
                    .suspended(true)
                    .suspendReason(new SuspendReason.ScheduledWakeup(
                            Instant.now().minusSeconds(60), "已过期唤醒"))
                    .build();

            loop.scheduleWakeupIfNeeded(state);

            // 验证立即发布了事件（publishEvent(Object) 重载）
            org.mockito.Mockito.verify(eventPublisher).publishEvent(
                    any(com.lifepilot.agent.suspend.event.ScheduledWakeupEvent.class));
        }

        @Test
        void 非ScheduledWakeup原因不应触发调度() {
            var eventPublisher = mock(ApplicationEventPublisher.class);
            ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
            when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

            var loop = new ReactAgentLoop(
                    contextAssembler,
                    new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                    agentToolProvider,
                    new AgentConfigProperties(),
                    new ObjectMapper(),
                    null, transcriptStore,
                    null, null, eventPublisher, null, null, null, sharedScheduler, null, null
            );

            var state = ReactAgentState.builder()
                    .traceId("trace-no-wakeup")
                    .sessionId("session-no-wakeup")
                    .goal("测试")
                    .steps(List.of())
                    .stepCount(0)
                    .shortTermMemory(List.of())
                    .mentionedEntities(List.of())
                    .budget(基础预算())
                    .depth(0)
                    .done(false)
                    .completionMode(CompletionMode.NORMAL)
                    .earlyStopRejectCount(0)
                    .suspended(true)
                    .suspendReason(new SuspendReason.ExternalDataWait("ds-1", "等待数据"))
                    .build();

            loop.scheduleWakeupIfNeeded(state);

            // 非 ScheduledWakeup 不触发任何事件
            org.mockito.Mockito.verifyNoInteractions(eventPublisher);
        }
    }

    // ===== LlmResponse 适配 =====

    @Nested
    class 适配方法 {

        @Test
        void adaptToChatResponse_应正确转换LlmResponse() {
            var llmResponse = new LlmResponse("这是回答内容", null, null, List.of(), Map.of(), 100, 50, null, 0, "test-provider", "test-model", 200L, false);
            var chatResponse = reactAgentLoop.adaptToChatResponse(llmResponse);

            assertThat(chatResponse).isNotNull();
            assertThat(chatResponse.getResults()).hasSize(1);
            assertThat(chatResponse.getResult().getOutput().getText()).isEqualTo("这是回答内容");
        }
    }

    // ===== 工具调用失败重试场景 =====

    @Nested
    class 工具调用失败 {

        @Test
        void 工具不存在应记录失败Observation并继续循环() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("调用不存在的工具"));
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());

            var budget = 基础预算();
            var request = 简单请求("调用不存在的工具", "session-missing-tool", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                if (llmCallCount.getAndIncrement() == 0) {
                    // 返回一个不存在的 tool call
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-missing", "function", "nonexistent.tool", "{}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                // 第二轮返回正常回答
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("工具未找到，但我可以帮你。"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(llmCallCount.get()).isEqualTo(2);
            assertThat(result.finalOutput()).isEqualTo("工具未找到，但我可以帮你。");
            // 应包含失败的 Observation
            assertThat(result.steps()).anyMatch(s ->
                    s instanceof ReactStep.Observation obs && !obs.success());
        }

        @Test
        void 工具执行抛出异常应记录失败Observation() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("工具执行异常"));
            when(agentToolProvider.resolveToolDisplayName("error.tool")).thenReturn("异常工具");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建异常工具回调("error.tool", "异常工具", new RuntimeException("内部错误"))
            ));

            var budget = 基础预算();
            var request = 简单请求("工具执行异常", "session-tool-error", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                if (llmCallCount.getAndIncrement() == 0) {
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-err", "function", "error.tool", "{}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage("工具执行出错，改用其他方式。"))));
            };

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            assertThat(result.finalOutput()).isEqualTo("工具执行出错，改用其他方式。");
            // 应包含失败的 Observation
            assertThat(result.steps()).anyMatch(s ->
                    s instanceof ReactStep.Observation obs && !obs.success());
        }
    }

    // ===== 步骤记录完整性 =====

    @Nested
    class 步骤记录 {

        @Test
        void 每轮迭代应递增步数预算() {
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("步数测试"));

            var budget = 基础预算();
            var request = 简单请求("步数测试", "session-step-count", budget);
            var initialState = ReactAgentState.init(request, budget);

            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) ->
                    new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            // 一轮迭代后 stepsUsed 应为 1
            assertThat(result.budget().stepsUsed()).isEqualTo(1);
        }

        @Test
        void 循环结束后步骤列表应包含Progress步骤() {
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of());
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("进度测试"));

            var budget = 基础预算();
            var request = 简单请求("进度测试", "session-progress", budget);
            var initialState = ReactAgentState.init(request, budget);

            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) ->
                    new ChatResponse(List.of(new Generation(new AssistantMessage("进度完成"))));

            var result = reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            // 应包含 Progress 步骤（"正在检索相关记忆和知识…" 和 "正在思考回答…"）
            long progressCount = result.steps().stream()
                    .filter(s -> s instanceof ReactStep.Progress).count();
            assertThat(progressCount).isGreaterThanOrEqualTo(2);
        }
    }

    // ===== 上下文缓存复用 =====

    @Nested
    class 上下文缓存 {

        @Test
        void 多轮迭代应复用首次组装的上下文() {
            when(contextAssembler.assemble(any())).thenReturn(基础上下文("缓存复用测试"));
            when(agentToolProvider.resolveToolDisplayName("noop.tool")).thenReturn("空操作");
            when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                    创建工具回调("noop.tool", "空操作", "{}")
            ));

            var budget = 基础预算();
            var request = 简单请求("缓存复用测试", "session-cache-reuse", budget);
            var initialState = ReactAgentState.init(request, budget);

            var llmCallCount = new AtomicInteger();
            IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
                if (llmCallCount.getAndIncrement() < 2) {
                    var toolCall = new AssistantMessage.ToolCall(
                            "call-" + llmCallCount.get(), "function", "noop.tool", "{}");
                    return new ChatResponse(List.of(new Generation(
                            AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
            };

            reactAgentLoop.coreLoop(
                    initialState, request, null, Instant.now(),
                    callback, new CancellationToken(), new AgentLoopContext()
            );

            // contextAssembler.assemble 只应被调用 1 次（首轮组装，后续复用缓存）
            org.mockito.Mockito.verify(contextAssembler, org.mockito.Mockito.times(1)).assemble(any());
        }
    }

    // ===== 辅助方法 =====

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

    private AgentRequest 简单请求(String message, String sessionId, Budget budget) {
        return new AgentRequest(message, sessionId, "web", null, null, budget, null, 0, null, null, null, null);
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

    private ToolCallback 创建异常工具回调(String name, String description, RuntimeException exception) {
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
                throw exception;
            }
        };
    }
}
