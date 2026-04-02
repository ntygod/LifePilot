package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.IterationCallback;
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
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.llm.multimodal.MediaContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Mock
    private TranscriptStore transcriptStore;

    @Mock
    private CompactionEngine compactionEngine;

    private ReactAgentLoop reactAgentLoop;

    @BeforeEach
    void setUp() {
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);

        reactAgentLoop = new ReactAgentLoop(
                contextAssembler,
                new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                agentToolProvider,
                new AgentConfigProperties(),
                new ObjectMapper(),
                null,
                null,
                transcriptStore,
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
    void 时间预算耗尽时应立即返回降级响应() {
        var budget = baseBudget().toBuilder()
                .maxDuration(Duration.ofSeconds(5))
                .build();
        var request = new AgentRequest("测试超时", "session-timeout", "web", null, null, budget, null, 0, null, null, null, null);
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
        assertThat(result.finalOutput())
                .contains("本轮处理已中断")
                .contains("我已保留当前进度");
        verifyNoInteractions(callback);
    }

    @Test
    void 首轮多模态请求应把媒体注入到Provider消息() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请描述这张图片"));

        var budget = baseBudget();
        var media = new MediaContent(
                "img-1",
                "image/png",
                new byte[]{1, 2, 3},
                "demo.png",
                3,
                Map.of("source", "test")
        );
        var request = new AgentRequest(
                "请描述这张图片",
                "session-media-first-round",
                "web",
                null,
                null,
                ChatTurnAction.SEND,
                null,
                budget,
                null,
                0,
                null,
                null,
                List.of(media),
                null,
                null
        );
        var initialState = ReactAgentState.init(request, budget);

        var capturedMessages = new AtomicReference<List<Message>>();
        var capturedRequest = new AtomicReference<AgentRequest>();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            capturedRequest.set(agentRequest);
            capturedMessages.set(messages);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("图片描述完成"))));
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

        assertThat(result.finalOutput()).isEqualTo("图片描述完成");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().mediaContents()).hasSize(1);
        assertThat(capturedMessages.get()).isNotNull();
        assertThat(capturedMessages.get())
                .filteredOn(message -> message instanceof UserMessage)
                .singleElement()
                .satisfies(message -> assertThat(((UserMessage) message).getMedia()).hasSize(1));
    }

    @Test
    void 步骤预算耗尽时应生成统一降级终止响应() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请执行测试任务"));

        var budget = baseBudget().toBuilder()
                .maxSteps(1)
                .build();
        var request = new AgentRequest("测试步骤预算", "session-steps", "web", null, null, budget, null, 0, null, null, null, null);
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

    @Test
    void 工具调用执行后应写入Transcript的ToolCall与ToolResult() {
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请执行测试任务"));
        when(agentToolProvider.resolveToolDisplayName("todo.create")).thenReturn("创建待办");
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of(new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("todo.create")
                    .description("创建待办")
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
                return "{\"id\":\"todo-1\",\"title\":\"收拾工位\"}";
            }
        }));

        var budget = baseBudget();
        var request = new AgentRequest("帮我创建一个待办", "session-transcript-tool", "web",
                null, null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);

        var llmCallCount = new AtomicInteger();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            if (llmCallCount.getAndIncrement() == 0) {
                var toolCall = new AssistantMessage.ToolCall("call-1", "function",
                        "todo.create", "{\"title\":\"收拾工位\"}");
                var assistantMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("待办已经创建完成"))));
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

        assertThat(result.finalOutput()).isEqualTo("待办已经创建完成");
        verify(transcriptStore).appendToolCall(
                eq("session-transcript-tool"),
                eq(result.traceId()),
                eq(result.traceId()),
                eq("todo.create"),
                eq("call-1"),
                eq("创建待办"),
                eq("{\"title\":\"收拾工位\"}"),
                any()
        );
        verify(transcriptStore).appendToolResult(
                eq("session-transcript-tool"),
                eq(result.traceId()),
                eq(result.traceId()),
                eq("todo.create"),
                eq("call-1"),
                eq(true),
                eq("{\"id\":\"todo-1\",\"title\":\"收拾工位\"}"),
                eq(null),
                eq(true),
                eq(false),
                any()
        );
    }

    @Test
    void 中途压缩命中后应重建上下文而不是复用旧缓存() {
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);
        reactAgentLoop = new ReactAgentLoop(
                contextAssembler,
                new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                agentToolProvider,
                new AgentConfigProperties(),
                new ObjectMapper(),
                null,
                null,
                transcriptStore,
                null,
                null,
                null,
                null,
                null,
                compactionEngine,
                sharedScheduler
        );

        when(contextAssembler.assemble(any())).thenReturn(baseContext("请执行测试任务"));
        when(agentToolProvider.resolveToolDisplayName("todo.create")).thenReturn("创建待办");
        when(compactionEngine.compactIfNeeded(any(), any(), any())).thenReturn(true);
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of(new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("todo.create")
                    .description("创建待办")
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
                return "{\"id\":\"todo-2\",\"title\":\"继续执行\"}";
            }
        }));

        var budget = baseBudget().toBuilder()
                .maxTokens(100)
                .tokensUsed(85)
                .build();
        var request = new AgentRequest("继续执行并在需要时压缩上下文", "session-mid-compact", "web",
                null, null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);

        var llmCallCount = new AtomicInteger();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            if (llmCallCount.getAndIncrement() == 0) {
                var toolCall = new AssistantMessage.ToolCall("call-2", "function",
                        "todo.create", "{\"title\":\"继续执行\"}");
                var assistantMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已继续执行完毕"))));
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

        assertThat(result.finalOutput()).isEqualTo("已继续执行完毕");
        verify(contextAssembler, org.mockito.Mockito.times(2)).assemble(any());
        verify(compactionEngine).compactIfNeeded(
                eq("session-mid-compact"),
                eq(result.traceId()),
                isNull()
        );
    }

    @Test
    void 阶段性执行说明不应直接结束而应等待显式完成() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请执行自动化写代码测试任务"));

        var budget = baseBudget();
        var request = new AgentRequest("请自动写代码并验证流程", "session-exec-complete", "web",
                null, AgentTaskMode.EXECUTION, null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);

        var llmCallCount = new AtomicInteger();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            if (llmCallCount.getAndIncrement() == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "我已经创建了测试目录，接下来继续执行后续步骤"))));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "已完成：测试流程已跑通，编码链路和结束协议验证通过"))));
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

        assertThat(llmCallCount.get()).isEqualTo(2);
        assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
        assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        assertThat(result.finalOutput()).startsWith("已完成：");
        assertThat(result.earlyStopRejectCount()).isEqualTo(1);
    }

    @Test
    void 连续两次未按协议结束应被降级拦截() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请执行自动化写代码测试任务"));

        var budget = baseBudget();
        var request = new AgentRequest("请自动写代码并验证流程", "session-exec-guard", "web",
                null, AgentTaskMode.EXECUTION, null, budget, null, 0, null, null, null, null);
        var initialState = ReactAgentState.init(request, budget);

        var llmCallCount = new AtomicInteger();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            llmCallCount.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "我已经创建了测试目录，后续步骤将继续执行"))));
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

        assertThat(llmCallCount.get()).isEqualTo(2);
        assertThat(result.completionMode()).isEqualTo(CompletionMode.DEGRADED);
        assertThat(result.completionReason()).isEqualTo(CompletionReason.EARLY_STOP_REJECTED);
        assertThat(result.terminationReason()).contains("未满足结束协议");
        assertThat(result.finalOutput()).isNotBlank();
    }

    @Test
    void 解释性回答不应被提前结束守卫误拦截() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("请解释需求拆解和质量保障责任分工"));

        var budget = baseBudget();
        var request = new AgentRequest(
                "这说明任务是可行的，但是你如何保证需求实现的质量呢，需求拆解是你做的还是 codex 做的",
                "session-answer-followup",
                "web",
                null,
                null,
                budget,
                null,
                0,
                null,
                null,
                null,
                null
        );
        var initialState = ReactAgentState.init(request, budget);

        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) ->
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "质量保障由我负责制定验收标准、拆解边界和复核结果，Codex 负责按约束实现与执行测试。"))));

        var result = reactAgentLoop.coreLoop(
                initialState,
                request,
                null,
                Instant.now(),
                callback,
                new CancellationToken(),
                new AgentLoopContext()
        );

        assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
        assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
        assertThat(result.finalOutput()).contains("质量保障由我负责");
        assertThat(result.earlyStopRejectCount()).isZero();
    }

    @Test
    void 恢复执行后用户要求直接结束时应允许自然收尾() {
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class))).thenReturn(List.of());
        when(contextAssembler.assemble(any())).thenReturn(baseContext("你刚才挂起等待用户补充，现在用户要求你只输出 OK 后结束"));

        var budget = baseBudget();
        var request = new AgentRequest(
                "原任务内容\n\n<resume_user_input>\n可以，输出一个 OK，然后结束吧\n</resume_user_input>",
                "session-resume-natural-finish",
                "web",
                null,
                "turn-resume-natural-finish",
                ChatTurnAction.RESUME,
                AgentTaskMode.AUTO,
                null,
                budget,
                null,
                0,
                null,
                null,
                null,
                null,
                null
        );
        var initialState = ReactAgentState.init(request, budget)
                .appendStep(new com.lifepilot.agent.model.ReactStep.Suspend(
                        new com.lifepilot.agent.model.SuspendReason.ExternalDataWait("__await_user_input__", "请补充信息"),
                        Instant.now(),
                        0
                ))
                .appendStep(new com.lifepilot.agent.model.ReactStep.Resume(
                        new ResumePayload.DataReady("__await_user_input__", "可以，输出一个 OK，然后结束吧"),
                        Instant.now(),
                        Duration.ofSeconds(1)
                ));

        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) ->
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "OK<completion_control>done</completion_control>"))));

        var result = reactAgentLoop.coreLoop(
                initialState,
                request,
                null,
                Instant.now(),
                callback,
                new CancellationToken(),
                new AgentLoopContext()
        );

        assertThat(result.completionMode()).isEqualTo(CompletionMode.NORMAL);
        assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        assertThat(result.finalOutput()).isEqualTo("OK");
        assertThat(result.earlyStopRejectCount()).isZero();
    }

    private Budget baseBudget() {
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

    private AssembledContext baseContext(String userPrompt) {
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
}
