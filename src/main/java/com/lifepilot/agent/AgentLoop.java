package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.StreamingLlmResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
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

    /** 流式 RESPONDING 阶段追加的自然语言输出约束。 */
    private static final String STREAMING_OUTPUT_CONSTRAINT = """
            
            输出要求（流式）：
            - 直接使用自然语言回复，不要输出 JSON
            - 不要包含任何代码块或格式标记
            - 回复应简洁、有用、友好
            """;

    /** 当 TraceContext 中没有 LlmCallStep 时的默认 modelId。 */
    private static final String DEFAULT_MODEL_ID = "agent";

    private final StateReducer stateReducer;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final MultimodalRouter multimodalRouter;
    private final TraceRecorder traceRecorder;
    @Nullable
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SessionManager sessionManager;
    @Nullable
    private final ConversationViewService conversationViewService;
    private final ActionParser actionParser;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    @Nullable
    private final WorkingMemory workingMemory;
    @Nullable
    private final ConversationHistoryStore conversationHistoryStore;

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
                sessionManager, conversationViewService, actionParser, agentToolProvider, config,
                null, null, null, null);
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
                     @Nullable WorkingMemory workingMemory,
                     @Nullable ConversationHistoryStore conversationHistoryStore,
                     @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                     @Nullable KnowledgeBaseRepository knowledgeBaseRepository) {
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
        this.conversationHistoryStore = conversationHistoryStore;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
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
        AgentState state = AgentState.init(request);
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

            // 用户消息写入 L1（在 assembleContext 之前，确保对话历史完整）
            writeUserMessageToL1(state);

            // TraceId 提前告知前端：便于在流式过程中打开“实时轨迹”视图
            if (state.traceId() != null) {
                sseManager.sendEvent(streamId, SseEventType.TRACE_START, Map.of(
                        "sessionId", request.sessionId(),
                        "turnId", tempTurnId,
                        "traceId", state.traceId(),
                        "timestamp", Instant.now().toEpochMilli()
                ));
            }
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
                    Map.of()
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
                        Map.of()
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
                        Map.of()
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
                            Map.of()
                    );
                    action = callLlmStreamingAndParseAction(request, state, assembledContext, traceContext, streamId, sseManager, tempTurnId);
                    state = reduceAndRecord(state, action, traceContext, loopStart);
                    
                    // 提取最终内容和 Token 使用量
                    if (action instanceof Action.ResponseGenerated responseGenerated) {
                        finalContent = responseGenerated.content();
                    } else if (action instanceof Action.ErrorRecovery errorRecovery
                            && errorRecovery.errorType() == AgentErrorType.LLM_UNAVAILABLE) {
                        // 流式 RESPONDING 阶段如果 LLM 不可用：统一走“错误结束”通道，避免既发 ERROR 又发 DONE
                        error = new RuntimeException(errorRecovery.errorMessage());
                        break;
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
            if (state.terminationReason() == null) {
                reasoningSummary = buildReasoningSummary(state, traceContext);
                // 将最终输出与推理概要写回状态，便于异步持久化使用
                state = state.toBuilder()
                        .finalOutput(finalContent)
                        .reasoningSummary(reasoningSummary)
                        .build();
            }

            // AI 响应写入 L1（在 asyncPostProcess 之前）
            writeAssistantMessageToL1(state);

            // 异步后处理（会话快照 / L2 flush）
            asyncPostProcess(state);
            
            // 从 TraceContext 中聚合 Token 使用量和模型信息
            finalTokenUsage = aggregateTokenUsage(traceContext);

        } catch (Exception e) {
            log.error("流式 Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            state = AgentState.init(request);
        } finally {
            // 轨迹记录
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state.finalOutput();
                boolean success = error == null && state.terminationReason() == null;
                String errorType = error != null ? error.getClass().getSimpleName() : null;
                String errorDetail = error != null
                        ? error.getMessage()
                        : state.terminationReason();
                traceRecorder.endTrace(traceContext, finalOutput, success, errorType, errorDetail);
            }

            // 发送 done 事件或 error 事件
            if (error != null) {
                sendStreamError(sseManager, streamId, 500,
                        "处理失败: " + error.getMessage(), state.traceId());
            } else {
                // 推理结束事件（在 DONE 之前发送，便于前端时间线展示）
                sendReasoningEvent(
                        sseManager, streamId, request.sessionId(), tempTurnId,
                        "ANSWER_FINALIZED", "回答已生成", "本轮推理与回答已完成。",
                        null, java.util.Map.of()
                );
                var doneData = buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        traceContext, reasoningSummary, finalContent);
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
        AgentState state = AgentState.init(request);
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        try {
            state = initState(request);

            // 用户消息写入 L1（在 assembleContext 之前，确保对话历史完整）
            writeUserMessageToL1(state);

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
            if (state.terminationReason() == null) {
                String finalContent = state.finalOutput();
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder()
                        .finalOutput(finalContent)
                        .reasoningSummary(summary)
                        .build();
            }

            // AI 响应写入 L1（在 asyncPostProcess 之前）
            writeAssistantMessageToL1(state);

            // 异步后处理（Virtual Thread）
            asyncPostProcess(state);
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
                String finalOutput = state.finalOutput();
                boolean success = error == null && state.terminationReason() == null;
                String errorType = error != null ? error.getClass().getSimpleName() : null;
                String errorDetail = error != null
                        ? error.getMessage()
                        : state.terminationReason();
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
        int tokens = 0;
        String modelId = "agent";
        if (traceContext != null) {
            // 使用 TraceContext 中累计的 Token 与最后一次 LLM 调用的模型 ID
            tokens = traceContext.totalInputTokens() + traceContext.totalOutputTokens();
            var stepsList = traceContext.steps();
            for (int i = stepsList.size() - 1; i >= 0; i--) {
                var step = stepsList.get(i);
                if (step instanceof com.lifepilot.observability.trace.LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    break;
                }
            }
        } else if (state.budget() != null) {
            // 回退：使用预算中粗略的 tokensUsed
            tokens = state.budget().tokensUsed();
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
                                                  TraceContext traceContext,
                                                  String streamId,
                                                  SseSessionManager sseManager,
                                                  String tempTurnId) {
        try {
            String scene = mapPhaseToScene(state.phase());

            String systemPrompt = assembledContext.systemPrompt();
            String userPrompt = assembledContext.userPrompt();
            String userText = userPrompt != null ? userPrompt : "";

            // 流式 RESPONDING：保留原有 systemPrompt，仅追加"自然语言输出"约束
            String streamingSystemPrompt = systemPrompt;
            if (state.phase() == AgentPhase.RESPONDING) {
                if (streamingSystemPrompt == null || streamingSystemPrompt.isBlank()) {
                    streamingSystemPrompt = STREAMING_OUTPUT_CONSTRAINT.trim();
                } else {
                    streamingSystemPrompt = streamingSystemPrompt + "\n" + STREAMING_OUTPUT_CONSTRAINT;
                }
            }

            // 获取工具回调（与非流式路径一致，让 LLM 看到可用工具）
            var toolCallbacks = agentToolProvider.getToolCallbacks(state);
            if (!toolCallbacks.isEmpty()) {
                log.debug("流式调用已注册工具回调: count={}, phase={}, traceId={}",
                        toolCallbacks.size(), state.phase(), state.traceId());
            }

            // 构建完整提示词（仅用于日志和 trace 记录）
            String fullPrompt = (streamingSystemPrompt != null && !streamingSystemPrompt.isBlank()
                    ? streamingSystemPrompt + "\n\n" : "") + userText;

            // 打印完整提示词（便于调试）
            logLlmPromptIfEnabled(scene, state.phase(), state.traceId(), streamingSystemPrompt, userText, toolCallbacks, fullPrompt);

            // 构建流式响应 Flux 和 Provider 元信息
            reactor.core.publisher.Flux<String> tokenStream;
            String providerId;
            String modelId;

            var mediaList = request.mediaContents();
            if (mediaList != null && !mediaList.isEmpty()) {
                // 多模态路径：暂沿用 MultimodalRouter（ChatClient 多模态流式支持后续接入）
                MultimodalRequest mmRequest = new MultimodalRequest(
                        scene, fullPrompt, mediaList, null);
                StreamingLlmResponse streaming = multimodalRouter.streamWithInfo(mmRequest);
                tokenStream = streaming.stream();
                providerId = streaming.providerId();
                modelId = streaming.modelId();
            } else {
                // 文本路径：通过 ChatClient 注入 toolCallbacks，走 function calling 协议
                var clientInfo = llmRouter.getChatClientWithInfo(scene);
                var prompt = buildPrompt(clientInfo.client(), streamingSystemPrompt, toolCallbacks);
                tokenStream = prompt.user(userText).stream().content();
                providerId = clientInfo.providerId();
                modelId = clientInfo.modelId();
            }

            StringBuilder contentBuilder = new StringBuilder();
            final int[] tokenIndex = {0};
            Instant start = Instant.now();

            if (tokenStream == null) {
                throw new IllegalStateException("streaming response is null");
            }

            // 订阅流式响应，发送 token 事件
            tokenStream.doOnNext(token -> {
                contentBuilder.append(token);
                int index = tokenIndex[0]++;
                sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                        "sessionId", request.sessionId(),
                        "turnId", tempTurnId,
                        "content", token,
                        "index", index
                ));
            })
            .doOnError(error -> {
                log.error("流式 LLM 调用失败: scene={}, error={}", scene, error.getMessage(), error);
            })
            .blockLast();

            String responseContent = contentBuilder.toString();

            // 如果响应内容是 JSON 格式（LLM 仍可能返回 JSON），尝试解析并提取 content 字段
            if (responseContent.trim().startsWith("{")) {
                try {
                    Action parsed = actionParser.parse(AgentPhase.RESPONDING, responseContent);
                    if (parsed instanceof Action.ResponseGenerated responseGenerated) {
                        responseContent = responseGenerated.content();
                        log.debug("流式响应包含 JSON 格式，已解析并提取 content 字段");
                    }
                } catch (Exception e) {
                    log.debug("流式响应 JSON 解析失败，使用原始内容: {}", e.getMessage());
                }
            }

            // 记录流式 LLM Step（补齐 modelId/token usage）
            recordStreamingLlmStep(traceContext,
                    start,
                    providerId,
                    modelId,
                    scene,
                    fullPrompt,
                    responseContent,
                    null);

            // 解析为 ResponseGenerated Action（suggestions 为空列表）
            return new Action.ResponseGenerated(responseContent, List.of());

        } catch (Exception e) {
            log.error("流式 LLM 调用异常: phase={}, error={}", state.phase(), e.getMessage(), e);
            // 记录流式失败到 Trace（保证 usage/modelId 能反映这次调用）
            try {
                String scene = mapPhaseToScene(state.phase());
                String systemPrompt = assembledContext.systemPrompt();
                String userText = assembledContext.userPrompt() != null ? assembledContext.userPrompt() : "";
                String fullPrompt = (systemPrompt != null && !systemPrompt.isBlank()
                        ? systemPrompt + "\n\n" : "") + userText;
                // model/provider 不一定可得，使用 unknown 占位
                recordStreamingLlmStep(traceContext,
                        Instant.now(),
                        "unknown",
                        "unknown",
                        scene,
                        fullPrompt,
                        "",
                        e);
            } catch (Exception ignore) {
                // trace 记录失败不影响主流程
            }

            // 落一条可读的系统错误消息（DB + L1 WorkingMemory），便于前端展示/审计
            persistStreamingSystemError(state.sessionId(), state.traceId(), e);
            return new Action.ErrorRecovery(
                    AgentErrorType.LLM_UNAVAILABLE,
                    "流式 LLM 调用失败: " + e.getMessage(),
                    true,
                    null
            );
        }
    }

    /**
     * 将流式 LLM 调用记录为 LlmCallStep（用于 TraceContext 汇总 token/model）。
     *
     * <p>由于 stream 通道缺少原生 usage 元信息，这里采用粗略估算的 token 计数。</p>
     */
    private void recordStreamingLlmStep(@Nullable TraceContext traceContext,
                                        Instant startTime,
                                        String providerId,
                                        String modelId,
                                        String scene,
                                        @Nullable String prompt,
                                        @Nullable String output,
                                        @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) {
            return;
        }
        Instant end = Instant.now();
        Duration d = Duration.between(startTime, end);
        int inputTokens = estimateTokens(prompt != null ? prompt : "");
        int outputTokens = estimateTokens(output != null ? output : "");
        if (error != null) {
            outputTokens = 0;
        }
        String finishReason = error != null ? ("error: " + error.getMessage()) : "stream_complete";

        int stepIndex = traceContext.steps() != null ? traceContext.steps().size() : 0;
        var step = new com.lifepilot.observability.trace.LlmCallStep(
                stepIndex,
                end,
                d,
                providerId != null ? providerId : "unknown",
                modelId != null ? modelId : "unknown",
                scene != null ? scene : "unknown",
                inputTokens,
                outputTokens,
                d,
                false,
                0.0d,
                finishReason
        );
        traceRecorder.recordStep(traceContext, step);
    }

    /**
     * 流式失败时落一条 system 消息：写 Web 对话历史 + 写 WorkingMemory(L1)。
     *
     * <p>该方法不抛异常，避免影响主流程的 SSE ERROR 关闭。</p>
     */
    private void persistStreamingSystemError(@Nullable String sessionId,
                                             @Nullable String traceId,
                                             Exception e) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            String detail = e != null ? e.getMessage() : "unknown";
            String content = "系统提示：模型服务暂时不可用，请稍后重试。\n"
                    + "（错误信息）" + detail;

            if (conversationHistoryStore != null) {
                conversationHistoryStore.appendSystemMessage(sessionId, content, traceId);
            }
            WorkingMemory memory = this.workingMemory;
            if (memory != null) {
                int tokens = estimateTokens(content);
                memory.append(sessionId, com.lifepilot.memory.working.ConversationSlot.systemMessage(content, tokens));
            }
        } catch (Exception ignore) {
            // ignore
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
            // 场景统一由 AgentPhase 映射，避免将 preferredProvider 误用为场景名
            // 这样 ProviderConfig.scenes 只需维护标准场景常量（如 intent_understanding、agent-reasoning 等）
            String scene = mapPhaseToScene(state.phase());

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
                    .map(cb -> {
                        cb.getToolDefinition();
                        return cb.getToolDefinition().name();
                    })
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
        AgentState newState = Objects.requireNonNull(
                stateReducer.reduce(state, action),
                "stateReducer.reduce returned null");

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

    /** 异步后处理：会话快照持久化和 L2 flush。 */
    private void asyncPostProcess(AgentState finalState) {
        // 这里必须"真正异步"：不要在当前线程等待持久化完成（否则会拉长端到端延迟）。
        // Virtual Thread 非常适合这种 I/O 型后处理任务。
        Thread.startVirtualThread(() -> {
            try {
                // 1. 保存会话状态
                sessionManager.saveSession(finalState);
                
                // 2. 保存对话历史（与记忆系统解耦）
                if (conversationHistoryStore != null) {
                    conversationHistoryStore.appendTurn(
                            finalState.sessionId(),
                            finalState.goal(),
                            finalState.finalOutput(),
                            finalState.reasoningSummary(),
                            finalState.traceId()
                    );
                }
                
            } catch (Exception e) {
                log.warn("会话持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
    }

    /**
     * 在核心循环开始前，将用户消息写入 L1 工作记忆。
     * 失败时记录警告日志并继续（降级为无对话历史模式）。
     */
    private void writeUserMessageToL1(AgentState state) {
        if (workingMemory == null || state.goal() == null || state.goal().isBlank()) return;
        try {
            int tokens = estimateTokens(state.goal());
            var slot = ConversationSlot.userMessage(state.goal(), tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("用户消息写入 L1 失败，降级为无对话历史: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /**
     * LLM 响应生成后，将 AI 响应写入 L1 工作记忆。
     */
    private void writeAssistantMessageToL1(AgentState state) {
        if (workingMemory == null) return;
        String response = state.finalOutput();
        if (response == null || response.isBlank()) return;
        try {
            int tokens = estimateTokens(response);
            var slot = ConversationSlot.assistantMessage(response, tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("AI 响应写入 L1 失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
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
     * 构建知识库来源摘要列表。
     *
     * <p>当前实现基于会话-知识库关联表，仅返回本轮会话绑定的知识库列表，
     * 用于前端轻量展示“本轮使用到的知识库来源”。后续如需精确到文档/分块，
     * 可在记忆检索与文档检索链路中将命中信息写入 Trace 或单独的收集器。</p>
     *
     * @param sessionId 会话 ID
     * @return sources 数组（与前端 ChatResponse.sources 结构对齐）
     */
    private java.util.List<java.util.Map<String, Object>> buildKnowledgeSources(String sessionId) {
        SessionKnowledgeBaseRepository repo = this.sessionKnowledgeBaseRepository;
        if (sessionId == null || sessionId.isBlank() || repo == null) {
            return java.util.List.of();
        }
        try {
            var kbIds = repo.findKnowledgeBaseIdsBySessionId(sessionId);
            if (kbIds == null || kbIds.isEmpty()) {
                return java.util.List.of();
            }
            var result = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (String kbId : kbIds) {
                if (kbId == null || kbId.isBlank()) {
                    continue;
                }
                String name = kbId;
                if (knowledgeBaseRepository != null) {
                    try {
                        var kbOpt = knowledgeBaseRepository.findById(kbId);
                        if (kbOpt.isPresent() && kbOpt.get().name() != null && !kbOpt.get().name().isBlank()) {
                            name = kbOpt.get().name();
                        }
                    } catch (Exception ignore) {
                        // 知识库名称查询失败不影响主流程，回退为 ID
                    }
                }
                var source = new java.util.HashMap<String, Object>();
                source.put("type", "knowledgeBase");
                source.put("id", kbId);
                source.put("name", name);
                result.add(source);
            }
            return java.util.Collections.unmodifiableList(result);
        } catch (Exception e) {
            log.debug("构建知识库来源摘要失败: sessionId={}, error={}", sessionId, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * 从 TraceContext 中聚合 Token 使用量和模型信息。
     *
     * @param traceContext 追踪上下文（可为 null）
     * @return Token 使用量统计
     */
    private TokenUsage aggregateTokenUsage(TraceContext traceContext) {
        String modelId = DEFAULT_MODEL_ID;
        int promptTokens = 0;
        int completionTokens = 0;
        if (traceContext != null) {
            promptTokens = traceContext.totalInputTokens();
            completionTokens = traceContext.totalOutputTokens();
            var steps = traceContext.steps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                var step = steps.get(i);
                if (step instanceof com.lifepilot.observability.trace.LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    break;
                }
            }
        }
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, modelId);
    }

    /**
     * 构建 SSE done 事件的 payload。
     *
     * @param request           用户请求
     * @param state             Agent 状态
     * @param tempTurnId        临时轮次 ID
     * @param finalTokenUsage   Token 使用量
     * @param traceContext      追踪上下文（可为 null）
     * @param reasoningSummary  推理概要（可为 null）
     * @param finalContent      最终输出内容
     * @return done 事件 payload Map
     */
    private Map<String, Object> buildDoneEventPayload(AgentRequest request,
                                                       AgentState state,
                                                       String tempTurnId,
                                                       TokenUsage finalTokenUsage,
                                                       TraceContext traceContext,
                                                       String reasoningSummary,
                                                       String finalContent) {
        var doneData = new java.util.HashMap<String, Object>();
        // 会话与回合标识
        doneData.put("sessionId", request.sessionId());
        doneData.put("turnId", tempTurnId);
        // usage
        if (finalTokenUsage != null) {
            var usage = new java.util.HashMap<String, Object>();
            usage.put("inputTokens", finalTokenUsage.promptTokens());
            usage.put("outputTokens", finalTokenUsage.completionTokens());
            usage.put("totalTokens", finalTokenUsage.totalTokens());
            doneData.put("usage", usage);
        }
        // 工具调用摘要：从 TraceContext 的 ToolCallStep 提取
        if (traceContext != null && !traceContext.steps().isEmpty()) {
            var toolSummaries = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var step : traceContext.steps()) {
                if (step instanceof com.lifepilot.observability.trace.ToolCallStep toolStep) {
                    var toolSummary = new java.util.HashMap<String, Object>();
                    toolSummary.put("toolId", toolStep.toolId());
                    toolSummary.put("action", toolStep.toolAction());
                    toolSummary.put("success", toolStep.success());
                    toolSummary.put("latencyMs", toolStep.duration().toMillis());
                    toolSummaries.add(toolSummary);
                }
            }
            if (!toolSummaries.isEmpty()) {
                doneData.put("toolsSummary", toolSummaries);
            }
        }
        // 知识库来源摘要
        var sources = buildKnowledgeSources(request.sessionId());
        if (!sources.isEmpty()) {
            doneData.put("sources", sources);
        }
        // 基础字段
        doneData.put("timestamp", Instant.now().toEpochMilli());
        if (state.traceId() != null) {
            doneData.put("traceId", state.traceId());
        }
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
        return doneData;
    }

    /**
     * 发送 SSE 错误事件并关闭 emitter。
     *
     * @param sseManager SSE 会话管理器
     * @param streamId   流式标识
     * @param code       错误码
     * @param message    错误消息
     * @param traceId    追踪 ID（可为 null）
     */
    private void sendStreamError(SseSessionManager sseManager, String streamId,
                                 int code, String message, String traceId) {
        var errorData = new java.util.HashMap<String, Object>();
        errorData.put("code", code);
        errorData.put("message", message);
        if (traceId != null) {
            errorData.put("traceId", traceId);
        }
        sseManager.sendEvent(streamId, SseEventType.ERROR, errorData);
        sseManager.closeEmitter(streamId);
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
            var eventDetail = new java.util.HashMap<String, Object>();
            eventDetail.put("id", UUID.randomUUID().toString());
            eventDetail.put("type", type);
            eventDetail.put("title", title);
            eventDetail.put("description", description);
            eventDetail.put("createdAt", Instant.now().toString());
            eventDetail.put("extra", extra != null ? extra : java.util.Map.of());
            if (toolName != null) {
                eventDetail.put("toolName", toolName);
            }

            var eventPayload = new java.util.HashMap<String, Object>();
            eventPayload.put("sessionId", sessionId);
            eventPayload.put("turnId", turnId);
            eventPayload.put("event", eventDetail);

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
