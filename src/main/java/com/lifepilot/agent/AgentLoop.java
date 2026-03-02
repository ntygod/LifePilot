package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.working.ConversationSlot;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.memory.working.WorkingMemorySlot;
import com.lifepilot.observability.guardrail.GuardrailBlockedException;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 核心控制循环。
 *
 * <p>协调 LLM 调用、状态转换、预算检查、轨迹记录，
 * 循环执行直到终止条件满足。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper TOOL_INPUT_MAPPER = new ObjectMapper();

    private final StateReducer stateReducer;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final MultimodalRouter multimodalRouter;
    private final TraceRecorder traceRecorder;
    private final SessionManager sessionManager;
    @Nullable
    private final ConversationViewService conversationViewService;
    private final ActionParser actionParser;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    @Nullable
    private final WorkingMemory workingMemory;

    public AgentLoop(StateReducer stateReducer,
                     ContextAssembler contextAssembler,
                     LlmRouter llmRouter,
                     MultimodalRouter multimodalRouter,
                     TraceRecorder traceRecorder,
                     SessionManager sessionManager,
                     ConversationViewService conversationViewService,
                     ActionParser actionParser,
                     AgentToolProvider agentToolProvider,
                     AgentConfigProperties config) {
        this(stateReducer, contextAssembler, llmRouter, multimodalRouter, traceRecorder,
                sessionManager, conversationViewService, actionParser, agentToolProvider, config, null);
    }

    public AgentLoop(StateReducer stateReducer,
                     ContextAssembler contextAssembler,
                     LlmRouter llmRouter,
                     MultimodalRouter multimodalRouter,
                     TraceRecorder traceRecorder,
                     SessionManager sessionManager,
                     @Nullable ConversationViewService conversationViewService,
                     ActionParser actionParser,
                     AgentToolProvider agentToolProvider,
                     AgentConfigProperties config,
                     @Nullable WorkingMemory workingMemory) {
        this.stateReducer = stateReducer;
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.multimodalRouter = multimodalRouter;
        this.traceRecorder = traceRecorder;
        this.sessionManager = sessionManager;
        this.conversationViewService = conversationViewService;
        this.actionParser = actionParser;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
        this.workingMemory = workingMemory;
    }

    /**
     * 流式执行 Agent 循环，通过 SSE 发送 token 事件。
     *
     * <p>在 RESPONDING 阶段使用流式 LLM 调用，其他阶段保持同步。
     * 通过 SseSessionManager 发送 token 事件，最后发送 done 事件。</p>
     *
     * @param request       用户请求
     * @param streamId      流式传输标识
     * @param sseManager    SSE 会话管理器
     */
    public void runStreaming(AgentRequest request, String streamId, SseSessionManager sseManager) {
        AgentState state = null;
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        String finalContent = "";
        TokenUsage finalTokenUsage = null;
        // 本轮推理概要，用于 done 事件、/complete 响应以及会话快照 recentTurns
        String reasoningSummary = null;
        // 为本轮推理生成一个临时 turnId，用于前端关联 reasoning/token 事件
        String tempTurnId = UUID.randomUUID().toString();
        
        try {
            state = initState(request);
            // 推理开始事件
            sendReasoningEvent(
                    sseManager,
                    streamId,
                    request.sessionId(),
                    tempTurnId,
                    "AGENT_START",
                    "开始处理请求",
                    "Agent 已接收到用户请求，正在准备上下文与预算。",
                    null,
                    java.util.Map.of()
            );
            traceContext = startTraceIfEnabled(state, request);
            loopStart = Instant.now();
            var limits = LoopLimits.from(config);
            var counters = new LoopCounters();

            // 核心循环
            for (int iteration = 0; !state.isDone(); iteration++) {
                // 1) 迭代硬限制
                Action forcedByIterationLimit = forceTerminateIfIterationLimitReached(iteration, limits);
                if (forcedByIterationLimit != null) {
                    state = reduceAndRecord(state, forcedByIterationLimit, traceContext, loopStart);
                    break;
                }

                // 2) 更新 Budget
                state = updateBudgetElapsed(state, loopStart);
                Action forcedByBudget = forceTerminateIfBudgetExceeded(state);
                if (forcedByBudget != null) {
                    state = reduceAndRecord(state, forcedByBudget, traceContext, loopStart);
                    break;
                }

                // 3) 上下文组装
                // 先发送 CONTEXT_LOADING，提示正在收集上下文与预算信息
                sendReasoningEvent(
                        sseManager,
                        streamId,
                        request.sessionId(),
                        tempTurnId,
                        "CONTEXT_LOADING",
                        "分析问题与上下文",
                        "正在梳理本轮问题、会话历史与可用记忆。",
                        null,
                        java.util.Map.of()
                );
                var assembledContext = assembleContext(request, state);
                // 上下文组装完成后，发送 MEMORY_RETRIEVAL，占位表示已完成记忆检索/上下文拼接（如有）
                sendReasoningEvent(
                        sseManager,
                        streamId,
                        request.sessionId(),
                        tempTurnId,
                        "MEMORY_RETRIEVAL",
                        "检索相关记忆",
                        "已基于最近对话与知识收集相关记忆，用于本轮推理。",
                        null,
                        java.util.Map.of()
                );

                // 4) 调用 LLM / 执行工具（RESPONDING 阶段使用流式）
                Action action;
                if (state.phase() == AgentPhase.RESPONDING) {
                    // 推理阶段：开始生成回答
                    sendReasoningEvent(
                            sseManager,
                            streamId,
                            request.sessionId(),
                            tempTurnId,
                            "ANSWER_DRAFTING",
                            "正在生成回答",
                            "模型正在根据上下文整理最终回答。",
                            null,
                            java.util.Map.of()
                    );
                    action = callLlmStreamingAndParseAction(request, state, assembledContext, streamId, sseManager, tempTurnId);
                    state = reduceAndRecord(state, action, traceContext, loopStart);
                    
                    // 提取最终内容和 Token 使用量
                    if (action instanceof Action.ResponseGenerated responseGenerated) {
                        finalContent = responseGenerated.content();
                    }
                } else if (state.phase() == AgentPhase.EXECUTING) {
                    // 执行阶段：按计划调用工具，并发送 TOOL_CALL_* 推理事件
                    var toolCallbacks = agentToolProvider.getToolCallbacks(state);
                    action = executeNextPlannedToolStep(
                            state,
                            toolCallbacks,
                            sseManager,
                            streamId,
                            request.sessionId(),
                            tempTurnId
                    );
                    state = reduceAndRecord(state, action, traceContext, loopStart);
                } else {
                    action = callLlmAndParseAction(request, state, assembledContext);
                    state = reduceAndRecord(state, action, traceContext, loopStart);
                }

                // 5) 连续异常/阻断保护
                Action forcedByConsecutiveFailures = counters.onAction(action, state, limits);
                if (forcedByConsecutiveFailures != null) {
                    state = reduceAndRecord(state, forcedByConsecutiveFailures, traceContext, loopStart);
                    break;
                }
            }

            // 归一化最终输出与推理概要（供持久化与前端展示使用）
            finalContent = state.finalOutput() != null ? state.finalOutput() : finalContent;
            if (state != null && state.terminationReason() == null) {
                reasoningSummary = buildReasoningSummary(state, traceContext);
                // 将最终输出与推理概要写回状态，便于异步持久化使用
                state = state.toBuilder()
                        .finalOutput(finalContent)
                        .reasoningSummary(reasoningSummary)
                        .build();
            }

            // 异步后处理（会话快照 / 工作记忆）
            asyncPostProcess(state);
            
            // 从 TraceContext 中提取 Token 使用量和模型信息
            String modelId = "agent";
            int promptTokens = 0;
            int completionTokens = 0;
            if (traceContext != null) {
                promptTokens = traceContext.totalInputTokens();
                completionTokens = traceContext.totalOutputTokens();
                // 从最后一个 LlmCallStep 中获取 modelId
                var steps = traceContext.steps();
                for (int i = steps.size() - 1; i >= 0; i--) {
                    var step = steps.get(i);
                    if (step instanceof com.lifepilot.observability.trace.LlmCallStep llmStep) {
                        modelId = llmStep.modelId();
                        break;
                    }
                }
            }
            finalTokenUsage = new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, modelId);

        } catch (Exception e) {
            log.error("流式 Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            var errorState = AgentState.init(request);
            state = errorState;
        } finally {
            // 轨迹记录
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state != null ? state.finalOutput() : null;
                boolean success = error == null && state != null && state.terminationReason() == null;
                String errorType = error != null ? error.getClass().getSimpleName() : null;
                String errorDetail = error != null
                        ? error.getMessage()
                        : (state != null ? state.terminationReason() : null);
                traceRecorder.endTrace(traceContext, finalOutput, success, errorType, errorDetail);
            }

            // 发送 done 事件或 error 事件
            if (error != null) {
                sseManager.sendEvent(streamId, SseEventType.ERROR, Map.of(
                        "code", 500,
                        "message", "处理失败: " + error.getMessage(),
                        "traceId", state != null ? state.traceId() : null
                ));
                sseManager.closeEmitter(streamId);
            } else {
                var doneData = new java.util.HashMap<String, Object>();
                // 会话与回合标识
                doneData.put("sessionId", request.sessionId());
                doneData.put("turnId", tempTurnId);
                // usage 结构，兼容文档中的字段命名
                if (finalTokenUsage != null) {
                    var usage = new java.util.HashMap<String, Object>();
                    usage.put("inputTokens", finalTokenUsage.promptTokens());
                    usage.put("outputTokens", finalTokenUsage.completionTokens());
                    usage.put("totalTokens", finalTokenUsage.totalTokens());
                    doneData.put("usage", usage);
                    // 兼容旧字段
                    doneData.put("tokenUsage", finalTokenUsage);
                }
                // 兼容历史字段：messageId / content / traceId / timestamp
                doneData.put("messageId", UUID.randomUUID().toString());
                doneData.put("content", finalContent);
                doneData.put("timestamp", Instant.now().toEpochMilli());
                if (state != null && state.traceId() != null) {
                    doneData.put("traceId", state.traceId());
                }
                // 推理概要（Phase 1：先返回简单文案）
                if (reasoningSummary != null) {
                    doneData.put("reasoningSummary", reasoningSummary);
                }
                // contents：当前仅返回 TEXT，后续扩展多模态
                var contents = new java.util.ArrayList<java.util.Map<String, Object>>();
                if (finalContent != null && !finalContent.isBlank()) {
                    var textContent = new java.util.HashMap<String, Object>();
                    textContent.put("type", "TEXT");
                    textContent.put("text", finalContent);
                    contents.add(textContent);
                }
                doneData.put("contents", contents);
                // 推理结束事件（在 DONE 之前发送，便于前端时间线展示）
                sendReasoningEvent(
                        sseManager,
                        streamId,
                        request.sessionId(),
                        tempTurnId,
                        "ANSWER_FINALIZED",
                        "回答已生成",
                        "本轮推理与回答已完成。",
                        null,
                        java.util.Map.of()
                );
                sseManager.sendEvent(streamId, SseEventType.DONE, doneData);
                sseManager.closeEmitter(streamId);
            }
        }
    }

    /**
     * 执行 Agent 循环。
     *
     * @param request 用户请求
     * @return Agent 响应
     */
    public AgentResponse run(AgentRequest request) {
        AgentState state = null;
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        try {
            state = initState(request);

            traceContext = startTraceIfEnabled(state, request);

            // Trace 启动后重新计时：Loop 总耗时用于 Budget elapsed、Step elapsed 等。
            loopStart = Instant.now();
            var limits = LoopLimits.from(config);
            var counters = new LoopCounters();

            // 核心循环
            for (int iteration = 0; !state.isDone(); iteration++) {
                // 1) 迭代硬限制（保护性兜底）
                Action forcedByIterationLimit = forceTerminateIfIterationLimitReached(iteration, limits);
                if (forcedByIterationLimit != null) {
                    state = reduceAndRecord(state, forcedByIterationLimit, traceContext, loopStart);
                    break;
                }

                // 2) 更新 Budget 已用时长 + Budget 软硬限制
                state = updateBudgetElapsed(state, loopStart);
                Action forcedByBudget = forceTerminateIfBudgetExceeded(state);
                if (forcedByBudget != null) {
                    state = reduceAndRecord(state, forcedByBudget, traceContext, loopStart);
                    break;
                }

                // 3) 上下文组装（含 SubAgent systemPrompt 覆盖合并）
                var assembledContext = assembleContext(request, state);

                // 4) 调用 LLM → 解析为 Action → 归约状态 → 记录 Trace
                Action action = callLlmAndParseAction(request, state, assembledContext);
                state = reduceAndRecord(state, action, traceContext, loopStart);

                // 5) 连续异常/阻断保护：解析失败与护栏阻断过多会强制终止
                Action forcedByConsecutiveFailures = counters.onAction(action, state, limits);
                if (forcedByConsecutiveFailures != null) {
                    state = reduceAndRecord(state, forcedByConsecutiveFailures, traceContext, loopStart);
                    break;
                }
            }

            // 归一化最终输出与推理概要（非流式）：用于会话快照与 /complete 响应
            if (state != null && state.terminationReason() == null) {
                String finalContent = state.finalOutput();
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder()
                        .finalOutput(finalContent)
                        .reasoningSummary(summary)
                        .build();
            }

            // 异步后处理（Virtual Thread）
            asyncPostProcess(state);

            // state 在此处理论上不为 null，但为防御性起见仍做一次兜底
            if (state == null) {
                var fallback = AgentState.init(request);
                return fallback.toResponse();
            }
            return state.toResponse();

        } catch (Exception e) {
            log.error("Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            // 构建错误状态用于响应
            var errorState = AgentState.init(request);
            state = errorState;
            return AgentResponse.error(errorState, e);
        } finally {
            // 轨迹记录（确保异常路径也能正确结束）
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state != null ? state.finalOutput() : null;
                boolean success = error == null && state != null && state.terminationReason() == null;
                String errorType = error != null ? error.getClass().getSimpleName() : null;
                String errorDetail = error != null
                        ? error.getMessage()
                        : (state != null ? state.terminationReason() : null);
                traceRecorder.endTrace(traceContext, finalOutput, success, errorType, errorDetail);
            }
        }
    }

    /**
     * 构造简要推理概要。
     *
     * <p>后续可以接入专门的摘要模块，当前先基于步骤数与 Token 使用情况生成轻量描述。</p>
     */
    @Nullable
    private String buildReasoningSummary(AgentState state, @Nullable TraceContext traceContext) {
        if (state == null) {
            return null;
        }
        int steps = state.stepCount();
        int tokens = state.budget() != null ? state.budget().tokensUsed() : 0;
        String modelId = "agent";
        if (traceContext != null) {
            var stepsList = traceContext.steps();
            for (int i = stepsList.size() - 1; i >= 0; i--) {
                var step = stepsList.get(i);
                if (step instanceof com.lifepilot.observability.trace.LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    break;
                }
            }
        }
        return "本轮推理已完成，使用模型 %s，经历 %d 个推理步骤，累计约 %d 个 Token。"
                .formatted(modelId, steps, tokens);
    }

    // --- run() 拆分：入口阶段/保护逻辑/上下文组装 ---

    private AgentState initState(AgentRequest request) {
        var existingSession = sessionManager.findSession(request.sessionId());
        if (existingSession.isPresent()) {
            var snapshot = existingSession.get();
            // 先从会话快照恢复 AgentState
            var state = AgentState.fromSession(snapshot, request);
                // 再尝试将会话时间线回灌到 L1 工作记忆（如启用且当前为空）
                hydrateWorkingMemoryFromConversationView(snapshot.sessionId());
            return state;
        }
        return AgentState.init(request);
    }

    private TraceContext startTraceIfEnabled(AgentState state, AgentRequest request) {
        if (traceRecorder == null) {
            return null;
        }
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    private Action forceTerminateIfIterationLimitReached(int iteration, LoopLimits limits) {
        if (iteration < limits.maxIterations()) {
            return null;
        }
        return new Action.BudgetExhausted("循环次数达到硬限制: " + limits.maxIterations());
    }

    private AgentState updateBudgetElapsed(AgentState state, Instant loopStart) {
        Duration elapsed = Duration.between(loopStart, Instant.now());
        return state.toBuilder().budget(state.budget().withElapsed(elapsed)).build();
    }

    private Action forceTerminateIfBudgetExceeded(AgentState state) {
        if (!state.budget().exceeded()) {
            return null;
        }
        return new Action.BudgetExhausted(state.budget().exceedReason());
    }

    private AssembledContext assembleContext(AgentRequest request, AgentState state) {
        var assembled = contextAssembler.assemble(state);

        // SubAgent 场景：注入 Agent 专属 System Prompt（来自 AgentDefinition / 子 Agent 激活）
        String requestSystemPrompt = request.systemPrompt();
        if (requestSystemPrompt != null && !requestSystemPrompt.isBlank()) {
            String contextSystemPrompt = assembled.systemPrompt();
            String merged = requestSystemPrompt + "\n\n"
                    + (contextSystemPrompt != null && !contextSystemPrompt.isBlank()
                    ? contextSystemPrompt
                    : "");
            return assembled.withSystemPrompt(merged);
        }
        return assembled;
    }

    /**
     * 流式调用 LLM 生成回复并解析为 Action（仅 RESPONDING 阶段）。
     *
     * <p>使用 LlmRouter.stream() 获取流式响应，通过 SSE 发送 token 事件，
     * 最后解析为 ResponseGenerated Action。</p>
     */
    private Action callLlmStreamingAndParseAction(AgentRequest request,
                                                  AgentState state,
                                                  AssembledContext assembledContext,
                                                  String streamId,
                                                  SseSessionManager sseManager,
                                                  String tempTurnId) {
        try {
            String scene = request.preferredProvider() != null
                    ? request.preferredProvider()
                    : mapPhaseToScene(state.phase());

            String systemPrompt = assembledContext.systemPrompt();
            String userPrompt = assembledContext.userPrompt();
            String userText = userPrompt != null ? userPrompt : "";

            // 流式调用时，修改 System Prompt：移除 JSON 格式要求，直接要求返回自然语言
            // 因为流式响应是逐字返回的，不适合返回结构化数据
            String streamingSystemPrompt = systemPrompt;
            if (state.phase() == AgentPhase.RESPONDING && systemPrompt != null) {
                // 替换 RESPONDING 阶段的 JSON 格式要求为自然语言要求
                String roleDefinition = "你是 LifePilot，一个智能个人助手，专注于理解用户意图并高效完成任务。";
                String respondingInstruction = """
                        阶段：生成响应
                        
                        任务：
                        1. 基于执行结果生成清晰、有用的回复
                        2. 使用自然语言，避免技术术语
                        3. 提供相关的后续操作建议
                        4. 如执行失败，提供友好的错误说明和解决建议
                        
                        输出要求：
                        - 直接使用自然语言回复，不要使用 JSON 格式
                        - 回复应清晰、有用、友好
                        - 不要包含任何代码块或格式标记
                        """;
                String constraint = "\n\n重要约束：\n- 直接使用自然语言回复，不要使用任何格式标记\n- 回复应简洁、有用、友好";
                streamingSystemPrompt = roleDefinition + "\n\n" + respondingInstruction + constraint;
            }

            // 构建完整提示词（system + user）
            String fullPrompt = (streamingSystemPrompt != null && !streamingSystemPrompt.isBlank()
                    ? streamingSystemPrompt + "\n\n" : "") + userText;

            // 打印完整提示词（便于调试）
            logLlmPromptIfEnabled(scene, state.phase(), state.traceId(), streamingSystemPrompt, userText, null, fullPrompt);

            // 使用流式调用：如当前请求包含媒体内容，则通过 MultimodalRouter 走多模态流式通道
            Flux<String> stream;
            var mediaList = request.mediaContents();
            if (mediaList != null && !mediaList.isEmpty()) {
                MultimodalRequest mmRequest = new MultimodalRequest(
                        scene,
                        fullPrompt,
                        mediaList,
                        null
                );
                stream = multimodalRouter.stream(mmRequest);
            } else {
                stream = llmRouter.stream(scene, fullPrompt);
            }
            
            StringBuilder contentBuilder = new StringBuilder();
            // token 序号，用于前端增量渲染与调试
            final int[] tokenIndex = {0};
            
            // 订阅流式响应，发送 token 事件（同时包含旧字段 content 以兼容现有前端）
            stream.doOnNext(token -> {
                contentBuilder.append(token);
                int index = tokenIndex[0]++;
                sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                        "sessionId", request.sessionId(),
                        "turnId", tempTurnId,
                        "delta", token,
                        "content", token,
                        "index", index
                ));
            })
            .doOnError(error -> {
                log.error("流式 LLM 调用失败: scene={}, error={}", scene, error.getMessage(), error);
                sseManager.sendEvent(streamId, SseEventType.ERROR, Map.of(
                        "code", 500,
                        "message", "LLM 调用失败: " + error.getMessage()
                ));
            })
            .blockLast(); // 等待流完成

            String responseContent = contentBuilder.toString();
            
            // 如果响应内容是 JSON 格式（即使修改了 System Prompt，LLM 仍可能返回 JSON），
            // 尝试解析并提取 content 字段
            if (responseContent != null && responseContent.trim().startsWith("{")) {
                try {
                    // 尝试使用 ActionParser 解析为 ResponseGenerated
                    Action parsed = actionParser.parse(AgentPhase.RESPONDING, responseContent);
                    if (parsed instanceof Action.ResponseGenerated responseGenerated) {
                        // 解析成功，使用解析后的 content
                        responseContent = responseGenerated.content();
                        log.debug("流式响应包含 JSON 格式，已解析并提取 content 字段");
                    }
                    // 如果解析失败（返回 ErrorRecovery），使用原始内容
                } catch (Exception e) {
                    // 解析失败，使用原始内容
                    log.debug("流式响应 JSON 解析失败，使用原始内容: {}", e.getMessage());
                }
            }
            
            // 解析为 ResponseGenerated Action（suggestions 为空列表）
            return new Action.ResponseGenerated(responseContent, List.of());

        } catch (Exception e) {
            log.error("流式 LLM 调用异常: phase={}, error={}", state.phase(), e.getMessage(), e);
            return new Action.ErrorRecovery(
                    AgentErrorType.LLM_UNAVAILABLE,
                    "流式 LLM 调用失败: " + e.getMessage(),
                    true,
                    null
            );
        }
    }

    /**
     * 使用 LLM 生成回复并解析为 Action。
     *
     * <p>优先使用 Spring AI 的 .entity() 方法进行类型安全的解析，
     * 解析失败时降级到 ActionParser 手动解析。</p>
     */
    private Action callLlmAndParseAction(AgentRequest request,
                                         AgentState state,
                                         AssembledContext assembledContext) {
        try {
            // SubAgent 场景：使用偏好 Provider 作为场景名
            String scene = request.preferredProvider() != null
                    ? request.preferredProvider()
                    : mapPhaseToScene(state.phase());

            // 使用 ChatClient，分离 system prompt 和 user prompt
            ChatClient chatClient = llmRouter.getChatClient(scene);
            String systemPrompt = assembledContext.systemPrompt();
            String userPrompt = assembledContext.userPrompt();
            String userText = userPrompt != null ? userPrompt : "";

            // 从 agentToolProvider 获取工具回调列表并注册到 ChatClient
            var toolCallbacks = agentToolProvider.getToolCallbacks(state);
            if (!toolCallbacks.isEmpty()) {
                log.debug("已注册工具回调: count={}, phase={}, traceId={}",
                        toolCallbacks.size(), state.phase(), state.traceId());
            }

            // 打印完整提示词（便于调试）
            logLlmPromptIfEnabled(scene, state.phase(), state.traceId(), systemPrompt, userText, toolCallbacks, null);

            // EXECUTING 阶段：不依赖 LLM 输出 JSON 来“描述工具调用”。
            // 直接按 ExecutionPlan 执行当前步骤的工具，并将结果封装为 Action.ToolResult，
            // 这样可以彻底规避 JSON 解析失败导致的循环中断。
            if (state.phase() == AgentPhase.EXECUTING) {
                return executeNextPlannedToolStep(state, toolCallbacks);
            }
            
            // 优先使用 .entity() 方法进行类型安全解析
            // 对于支持的阶段，直接使用 .entity() 解析
            // 对于不支持的阶段（EXECUTING），使用 .content() + ActionParser
            try {
                // 每次调用都构建新的 PromptSpec，避免“已消费”的 builder 带来的不可预期行为。
                return switch (state.phase()) {
                    case UNDERSTANDING -> {
                        var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                        Action.IntentUnderstood parsed = prompt.user(userText).call()
                                .entity(Action.IntentUnderstood.class);
                        log.debug("LLM entity 解析成功: phase={}, traceId={}", state.phase(), state.traceId());
                        yield parsed;
                    }
                    case PLANNING -> {
                        var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                        Action.PlanGenerated parsed = prompt.user(userText).call()
                                .entity(Action.PlanGenerated.class);
                        log.debug("LLM entity 解析成功: phase={}, traceId={}", state.phase(), state.traceId());
                        yield parsed;
                    }
                    case REFLECTING -> {
                        var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                        Action.ReflectionComplete parsed = prompt.user(userText).call()
                                .entity(Action.ReflectionComplete.class);
                        log.debug("LLM entity 解析成功: phase={}, traceId={}", state.phase(), state.traceId());
                        yield parsed;
                    }
                    case RESPONDING -> {
                        var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                        Action.ResponseGenerated parsed = prompt.user(userText).call()
                                .entity(Action.ResponseGenerated.class);
                        log.debug("LLM entity 解析成功: phase={}, traceId={}", state.phase(), state.traceId());
                        yield parsed;
                    }
                    default -> {
                        var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                        String response = prompt.user(userText).call().content();
                        yield actionParser.parse(state.phase(), response != null ? response : "");
                    }
                };
                
            } catch (Exception e) {
                // .entity() 解析失败，降级到手动解析
                log.warn("entity() 解析失败，降级到手动解析: phase={}, error={}, traceId={}", 
                         state.phase(), e.getMessage(), state.traceId());
                
                // 降级路径：统一走 content + ActionParser
                var prompt = buildPrompt(chatClient, systemPrompt, toolCallbacks);
                String response = prompt.user(userText).call().content();
                return actionParser.parse(state.phase(), response != null ? response : "");
            }
            
        } catch (GuardrailBlockedException e) {
            // 护栏拦截 — 转换为 Blocked Action
            log.warn("护栏拦截: traceId={}, reason={}", state.traceId(), e.getMessage());
            return new Action.Blocked(e.getMessage());
        } catch (LlmUnavailableException e) {
            log.warn("LLM 不可用: error={}", e.getMessage());
            return new Action.BudgetExhausted("LLM 不可用: " + e.getMessage());
        }
    }

    /**
     * EXECUTING 阶段：按 ExecutionPlan 执行下一步工具调用。
     *
     * <p>注意：这里不通过 LLM 解析工具调用协议，而是直接使用计划中的 toolId/params
     * 调用对应的 {@link ToolCallback}。</p>
     */
    private Action executeNextPlannedToolStep(AgentState state, List<ToolCallback> toolCallbacks) {
        // 非流式路径：不发送 TOOL_CALL_* 推理事件
        return executeNextPlannedToolStep(state, toolCallbacks, null, null, null, null);
    }

    /**
     * EXECUTING 阶段：按 ExecutionPlan 执行下一步工具调用。
     *
     * <p>支持在流式场景下发送 TOOL_CALL_START / TOOL_CALL_END 推理事件，非流式场景下
     * 传入 null 即可，仅执行计划中的工具。</p>
     */
    private Action executeNextPlannedToolStep(AgentState state,
                                              List<ToolCallback> toolCallbacks,
                                              @Nullable SseSessionManager sseManager,
                                              @Nullable String streamId,
                                              @Nullable String sessionId,
                                              @Nullable String turnId) {
        ExecutionPlan plan = state.plan();
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return new Action.ErrorRecovery(
                    AgentErrorType.LLM_PARSE_FAILURE,
                    "EXECUTING 阶段缺少可执行的计划（ExecutionPlan 为空）",
                    false,
                    null
            );
        }

        int index = state.planStepIndex();
        if (index < 0 || index >= plan.steps().size()) {
            // 计划索引越界：强制收敛到反思阶段
            return new Action.ToolResult(
                    "plan",
                    true,
                    "计划步骤已全部执行或索引越界，进入反思评估",
                    0,
                    0,
                    false
            );
        }

        PlanStep step = plan.steps().get(index);
        String toolId = step.toolId();
        if (toolId == null || toolId.isBlank()) {
            return new Action.ErrorRecovery(
                    AgentErrorType.LLM_PARSE_FAILURE,
                    "计划步骤缺少 toolId，无法执行",
                    false,
                    null
            );
        }

        ToolCallback callback = toolCallbacks.stream()
                .filter(Objects::nonNull)
                .filter(cb -> cb.getToolDefinition() != null)
                .filter(cb -> toolId.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElse(null);

        if (callback == null) {
            // 这通常是“计划生成了不存在的工具”或“工具未注册/被禁用”
            return new Action.Blocked(
                    "工具不可用或未注册: toolId=" + toolId,
                    RiskLevel.HIGH,
                    toolId
            );
        }

        // 流式场景下：在实际调用工具前发送 TOOL_CALL_START 推理事件
        if (sseManager != null && streamId != null && turnId != null) {
            sendReasoningEvent(
                    sseManager,
                    streamId,
                    sessionId,
                    turnId,
                    "TOOL_CALL_START",
                    "调用工具 " + toolId,
                    "根据当前执行计划调用工具，步骤 #" + (index + 1) + "。",
                    toolId,
                    Map.of(
                            "toolId", toolId,
                            "planStepIndex", index,
                            "phase", state.phase().name()
                    )
            );
        }

        String toolInputJson;
        try {
            toolInputJson = TOOL_INPUT_MAPPER.writeValueAsString(step.params());
        } catch (Exception e) {
            return new Action.ErrorRecovery(
                    AgentErrorType.LLM_PARSE_FAILURE,
                    "工具参数序列化失败: " + e.getMessage(),
                    false,
                    null
            );
        }

        long start = System.nanoTime();
        String output;
        boolean success = true;
        try {
            output = callback.call(Objects.requireNonNull(toolInputJson, "toolInputJson"));
            if (output != null && output.trim().startsWith("{")) {
                // ToolBridgeAgentToolProvider 失败输出为 {"error":"..."}，这里做一个轻量判定
                try {
                    var node = TOOL_INPUT_MAPPER.readTree(output);
                    if (node != null && node.has("error")) {
                        success = false;
                    }
                } catch (Exception ignore) {
                    // 非 JSON 或 JSON 不可读 → 视为普通文本输出
                }
            }
        } catch (Exception e) {
            success = false;
            output = "工具调用异常: " + e.getMessage();
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // 若工具执行失败，优先进入 REFLECTING 评估/调整，而不是盲目继续下一步
        boolean hasMore = success && (index < plan.steps().size() - 1);

        // 流式场景下：工具执行完成后发送 TOOL_CALL_END 事件，包含耗时与结果摘要
        if (sseManager != null && streamId != null && turnId != null) {
            String preview = output != null
                    ? (output.length() > 400 ? output.substring(0, 400) + "..." : output)
                    : "";
            sendReasoningEvent(
                    sseManager,
                    streamId,
                    sessionId,
                    turnId,
                    "TOOL_CALL_END",
                    "工具调用完成",
                    success ? "工具调用完成，正在基于结果继续推理。" : "工具调用失败，Agent 将进入反思或错误恢复路径。",
                    toolId,
                    Map.of(
                            "toolId", toolId,
                            "planStepIndex", index,
                            "phase", state.phase().name(),
                            "latencyMs", latencyMs,
                            "success", success,
                            "hasMoreSteps", hasMore,
                            "outputPreview", preview
                    )
            );
        }

        return new Action.ToolResult(
                toolId,
                success,
                output != null ? output : "",
                0,
                latencyMs,
                hasMore
        );
    }

    private ChatClient.ChatClientRequestSpec buildPrompt(ChatClient chatClient,
                                                         String systemPrompt,
                                                         List<ToolCallback> toolCallbacks) {
        var prompt = chatClient.prompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.system(systemPrompt);
        }
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            // 防御性：确保不会把 null 回调传入 Spring AI（也让静态分析更容易接受）。
            ToolCallback[] callbacks = toolCallbacks.stream()
                    .filter(Objects::nonNull)
                    .toArray(ToolCallback[]::new);
            if (callbacks.length > 0) {
                prompt.toolCallbacks(callbacks);
            }
        }
        return prompt;
    }

    /**
     * 调试日志：打印每次调用 LLM 前发送的完整提示词。
     *
     * <p>默认关闭，通过配置 {@code lifepilot.agent.debug.log-llm-prompts=true} 开启。</p>
     */
    private void logLlmPromptIfEnabled(String scene,
                                       AgentPhase phase,
                                       String traceId,
                                       @Nullable String systemPrompt,
                                       @Nullable String userText,
                                       @Nullable List<ToolCallback> toolCallbacks,
                                       @Nullable String fullPrompt) {
        if (config == null || config.getDebug() == null || !config.getDebug().isLogLlmPrompts()) {
            return;
        }

        List<String> toolNames = new ArrayList<>();
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            toolNames = toolCallbacks.stream()
                    .filter(Objects::nonNull)
                    .map(cb -> cb.getToolDefinition() != null ? cb.getToolDefinition().name() : null)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }

        // fullPrompt 用于流式场景（system+user 已拼接）；非流式则打印 system/user 分块更清晰
        if (fullPrompt != null) {
            log.info("""
                            ========== LLM PROMPT ==========
                            scene={} phase={} traceId={}
                            tools={}
                            -------- FULL --------
                            {}
                            ========= END PROMPT ==========""",
                    scene, phase, traceId, toolNames, fullPrompt);
            return;
        }

        log.info("""
                        ========== LLM PROMPT ==========
                        scene={} phase={} traceId={}
                        tools={}
                        -------- SYSTEM --------
                        {}
                        -------- USER --------
                        {}
                        ========= END PROMPT ==========""",
                scene, phase, traceId, toolNames,
                systemPrompt != null ? systemPrompt : "",
                userText != null ? userText : "");
    }

    /** 执行状态转换并记录轨迹步骤。 */
    private AgentState reduceAndRecord(AgentState state, Action action,
                                        TraceContext traceContext,
                                        Instant startTime) {
        AgentPhase phaseBefore = state.phase();
        AgentState newState = stateReducer.reduce(state, action);

        Instant now = Instant.now();
        var step = new StateTransitionStep(
                newState.stepCount() - 1,
                now,
                Duration.between(startTime, now),
                phaseBefore.name(),
                newState.phase().name(),
                action.getClass().getSimpleName(),
                extractActionSummary(action)
        );

        if (traceRecorder != null && traceContext != null) {
            traceRecorder.recordStep(traceContext, step);
        }
        return newState;
    }

    /** 根据 AgentPhase 映射 LLM 场景。 */
    private String mapPhaseToScene(AgentPhase phase) {
        var mapping = config.getLoop().getSceneMapping();
        String key = phase.name().toLowerCase();
        return mapping.getOrDefault(key, LlmScene.AGENT_REASONING);
    }

    /** 从 Action 中提取摘要信息。 */
    private String extractActionSummary(Action action) {
        return switch (action) {
            case Action.IntentUnderstood a -> "意图理解: " + a.summary();
            case Action.PlanGenerated a -> "计划生成: %d 步".formatted(a.steps().size());
            case Action.ToolResult a -> "工具调用: " + a.toolId();
            case Action.ReflectionComplete a -> "反思完成: " + (a.satisfied() ? "满意" : "需调整");
            case Action.ResponseGenerated _ -> "响应生成";
            case Action.BudgetExhausted a -> "预算耗尽: " + a.reason();
            case Action.Blocked a -> "护栏阻断: " + a.reason();
            case Action.ErrorRecovery a -> "错误恢复: " + a.errorMessage();
            case Action.SubAgentResult a -> "子 Agent: " + a.skillId();
        };
    }

    /** 异步后处理：会话持久化和消息保存。 */
    private void asyncPostProcess(AgentState finalState) {
        // 这里必须"真正异步"：不要在当前线程等待持久化完成（否则会拉长端到端延迟）。
        // Virtual Thread 非常适合这种 I/O 型后处理任务。
        Thread.startVirtualThread(() -> {
            try {
                // 1. 保存会话状态
                sessionManager.saveSession(finalState);
                
                // 2. 如果 WorkingMemory 可用，保存消息到 L1（由 Token 预算与后续策略控制持久化与淘汰）
                if (workingMemory != null) {
                    saveMessagesToMemory(finalState);
                }
            } catch (Exception e) {
                log.warn("会话持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
    }

    /**
     * 将用户消息和 AI 响应保存到 WorkingMemory。
     *
     * @param state Agent 状态
     */
    private void saveMessagesToMemory(AgentState state) {
        WorkingMemory memory = this.workingMemory;
        if (memory == null) {
            return;
        }
        
        try {
            String sessionId = state.sessionId();
            String userMessage = state.goal();
            String assistantResponse = state.finalOutput();

            // 保存用户消息
            if (userMessage != null && !userMessage.isBlank()) {
                int userTokens = estimateTokens(userMessage);
                ConversationSlot userSlot = ConversationSlot.userMessage(userMessage, userTokens);
                memory.append(sessionId, userSlot);
                log.debug("用户消息已保存到工作记忆: sessionId={}, tokens={}",
                        sessionId, userTokens);
            }

            // 保存 AI 响应
            if (assistantResponse != null && !assistantResponse.isBlank()) {
                int assistantTokens = estimateTokens(assistantResponse);
                ConversationSlot assistantSlot = ConversationSlot.assistantMessage(
                        assistantResponse, assistantTokens);
                memory.append(sessionId, assistantSlot);
                log.debug("AI 响应已保存到工作记忆: sessionId={}, tokens={}",
                        sessionId, assistantTokens);
            }

        } catch (Exception e) {
            log.warn("保存消息到记忆系统失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage(), e);
        }
    }

    /**
     * 当存在会话记录且 L1 当前为空时，通过 ConversationViewService 将时间线回灌到 WorkingMemory。
     *
     * <p>仅使用视图层 {@link ConversationTurnView}，避免 AgentLoop 直接依赖会话存储或 EpisodicMemory。</p>
     */
    private void hydrateWorkingMemoryFromConversationView(String sessionId) {
        WorkingMemory memory = this.workingMemory;
        ConversationViewService viewService = this.conversationViewService;
        if (memory == null || viewService == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            // 若当前会话在 L1 中已存在上下文，则不重复回灌
            List<WorkingMemorySlot> existingSlots = memory.getContext(sessionId);
            if (existingSlots != null && !existingSlots.isEmpty()) {
                return;
            }

            List<ConversationTurnView> timeline = viewService.getFullTimeline(sessionId);
            if (timeline == null || timeline.isEmpty()) {
                return;
            }

            for (ConversationTurnView turn : timeline) {
                String content = turn.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                int tokens = estimateTokens(content);
                String role = turn.role();

                ConversationSlot slot;
                if ("USER".equalsIgnoreCase(role) || "user".equalsIgnoreCase(role)) {
                    slot = new ConversationSlot("user", content, tokens, 0.8f, false, null, turn.createdAt());
                } else if ("ASSISTANT".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) {
                    slot = new ConversationSlot("assistant", content, tokens, 0.6f, false, null, turn.createdAt());
                } else {
                    // 其他角色（如 system）作为高重要度 pinned 系统消息
                    slot = new ConversationSlot(role, content, tokens, 0.9f, true, null, turn.createdAt());
                }
                memory.append(sessionId, slot);
            }

            if (log.isDebugEnabled()) {
                List<WorkingMemorySlot> hydrated = memory.getContext(sessionId);
                log.debug("从 ConversationViewService 回灌工作记忆: sessionId={}, turns={}, slots={}",
                        sessionId, timeline.size(), hydrated != null ? hydrated.size() : 0);
            }
        } catch (Exception e) {
            log.warn("从会话视图回灌工作记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 估算文本 Token 数量（中英文混合约 2 字符/Token）。
     *
     * @param text 文本内容
     * @return Token 数量
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 2);
    }

    /**
     * 发送推理过程事件到前端，用于构建 Reasoning Timeline 与状态条。
     *
     * @param sseManager SSE 会话管理器
     * @param streamId   流式标识
     * @param sessionId  会话 ID（可为空）
     * @param turnId     临时轮次 ID（在 DONE 事件前用于前端关联）
     * @param type       事件类型（如 AGENT_START / ANSWER_DRAFTING / ANSWER_FINALIZED）
     * @param title      事件标题
     * @param description 事件描述
     * @param toolName   关联工具名称（可为空）
     * @param extra      额外字段（可为空 Map）
     */
    private void sendReasoningEvent(SseSessionManager sseManager,
                                    String streamId,
                                    String sessionId,
                                    String turnId,
                                    String type,
                                    String title,
                                    String description,
                                    String toolName,
                                    Map<String, Object> extra) {
        try {
            Map<String, Object> eventPayload = Map.of(
                    "sessionId", sessionId,
                    "turnId", turnId,
                    "event", Map.of(
                            "id", UUID.randomUUID().toString(),
                            "type", type,
                            "title", title,
                            "description", description,
                            "toolName", toolName,
                            "createdAt", Instant.now().toString(),
                            "extra", extra != null ? extra : java.util.Map.of()
                    )
            );
            sseManager.sendEvent(streamId, SseEventType.REASONING, eventPayload);
        } catch (Exception e) {
            // 推理事件发送失败不应影响主流程，记录调试日志即可
            log.debug("发送 reasoning 事件失败: type={}, error={}", type, e.getMessage());
        }
    }

    /**
     * Loop 保护限制（来自配置）。
     *
     * <p>注意：maxConsecutiveParseFailures <= 0 时使用安全默认值 3。</p>
     */
    private record LoopLimits(int maxIterations,
                              int maxConsecutiveBlocks,
                              int maxConsecutiveParseFailures) {
        static LoopLimits from(AgentConfigProperties config) {
            int configuredMaxParseFailures = config.getLoop().getMaxConsecutiveParseFailures();
            int maxConsecutiveParseFailures = configuredMaxParseFailures > 0 ? configuredMaxParseFailures : 3;
            return new LoopLimits(
                    config.getLoop().getMaxIterations(),
                    config.getLoop().getMaxConsecutiveBlocks(),
                    maxConsecutiveParseFailures
            );
        }
    }

    /**
     * Loop 内的连续失败计数器。
     *
     * <p>职责：根据本轮 Action 更新 counters，并在超过阈值时返回强制终止的 Action。</p>
     */
    private static final class LoopCounters {
        private int consecutiveBlocks = 0;
        private int consecutiveParseFailures = 0;

        Action onAction(Action action, AgentState state, LoopLimits limits) {
            Action forcedByParseFailures = updateParseFailureCounter(action, state, limits);
            if (forcedByParseFailures != null) {
                return forcedByParseFailures;
            }
            return updateBlockedCounter(action, limits);
        }

        private Action updateParseFailureCounter(Action action, AgentState state, LoopLimits limits) {
            if (action instanceof Action.ErrorRecovery errorRecovery
                    && errorRecovery.errorType() == AgentErrorType.LLM_PARSE_FAILURE) {
                consecutiveParseFailures++;
                if (consecutiveParseFailures >= limits.maxConsecutiveParseFailures()) {
                    log.error("连续解析失败达到上限: count={}, phase={}, lastError={}",
                            consecutiveParseFailures, state.phase(), errorRecovery.errorMessage());
                    return new Action.BudgetExhausted(
                            "LLM 输出解析连续失败 " + consecutiveParseFailures + " 次，无法继续处理。"
                                    + "请检查 LLM Provider 配置或联系技术支持。");
                }
            } else {
                consecutiveParseFailures = 0;
            }
            return null;
        }

        private Action updateBlockedCounter(Action action, LoopLimits limits) {
            if (action instanceof Action.Blocked) {
                consecutiveBlocks++;
                if (consecutiveBlocks >= limits.maxConsecutiveBlocks()) {
                    log.warn("连续护栏阻断达到上限: count={}", consecutiveBlocks);
                    return new Action.BudgetExhausted("连续护栏阻断达到上限: " + limits.maxConsecutiveBlocks());
                }
            } else {
                consecutiveBlocks = 0;
            }
            return null;
        }
    }
}
