package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.a2ui.A2uiComponentCatalog;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;;

/**
 * ReAct Agent 循环 — 替代原六阶段状态机 AgentLoop。
 *
 * <p>核心循环：Thought → Action → Observation，直到 LLM 返回纯文本（无 tool call）
 * 或预算耗尽 / 取消信号触发。</p>
 *
 * <p>通过 Spring AI 原生 function calling（ChatClient + ToolCallback）实现工具调用，
 * 移除了原架构的 ActionParser 和显式阶段枚举。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
public class ReactAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);
    private static final String DEFAULT_MODEL_ID = "unknown";

    // ===== 核心依赖（必需） =====
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final TraceRecorder traceRecorder;
    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    private final PromptRegistry promptRegistry;

    // ===== 可选依赖（@Nullable） =====
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaDataExtractor mediaDataExtractor;
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final ConversationHistoryStore conversationHistoryStore;
    @Nullable private final ConversationViewService conversationViewService;
    @Nullable private final RealtimeExtractor realtimeExtractor;
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final A2uiProperties a2uiProperties;

    // ===== 运行时状态（volatile） =====
    private volatile A2uiComponentTree lastCollectedA2uiTree;
    private volatile List<String> lastInjectedEntityIds = List.of();
    private volatile CancellationToken cancellationToken;

    public ReactAgentLoop(
            ContextAssembler contextAssembler,
            LlmRouter llmRouter,
            TraceRecorder traceRecorder,
            ObjectMapper objectMapper,
            SessionManager sessionManager,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            PromptRegistry promptRegistry,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaDataExtractor mediaDataExtractor,
            @Nullable WorkingMemory workingMemory,
            @Nullable ConversationHistoryStore conversationHistoryStore,
            @Nullable ConversationViewService conversationViewService,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
            @Nullable A2uiProperties a2uiProperties) {
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.traceRecorder = traceRecorder;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.multimodalRouter = multimodalRouter;
        this.mediaDataExtractor = mediaDataExtractor;
        this.workingMemory = workingMemory;
        this.conversationHistoryStore = conversationHistoryStore;
        this.conversationViewService = conversationViewService;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.a2uiProperties = a2uiProperties;
    }

    /**
     * 迭代回调 — 抽象 LLM 调用方式（同步 / 流式）。
     */
    @FunctionalInterface
    interface IterationCallback {
        /**
         * 调用 LLM 并返回响应。
         *
         * @param request       原始请求
         * @param messages      Spring AI 消息列表
         * @param toolCallbacks 工具回调列表
         * @param traceContext  追踪上下文
         * @return LLM 响应
         */
        ChatResponse callLlm(AgentRequest request,
                              List<Message> messages,
                              List<ToolCallback> toolCallbacks,
                              @Nullable TraceContext traceContext);
    }

    /**
     * 构建 Spring AI 消息列表。
     *
     * <p>将 AssembledContext 的 systemPrompt / userPrompt 和历史 ReactStep
     * 转换为 Spring AI Message 序列。</p>
     *
     * @param ctx   组装后的上下文
     * @param state 当前状态
     * @return 消息列表（至少 1 条 system + 1 条 user）
     */
    List<Message> buildMessages(AssembledContext ctx, ReactAgentState state) {
        var messages = new ArrayList<Message>();

        // System Prompt（含记忆、知识库等上下文）
        messages.add(new SystemMessage(ctx.systemPrompt()));

        // User Prompt（含用户请求、对话历史等）
        messages.add(new UserMessage(ctx.userPrompt()));

        // 历史 ReactStep 转换为 assistant / tool messages
        // Spring AI ChatClient 在单次 call() 内自动管理 tool call 往返，
        // 跨迭代的历史由我们手动维护
        for (var step : state.steps()) {
            switch (step) {
                case ReactStep.Thought t ->
                    messages.add(new AssistantMessage(t.content()));
                case ReactStep.ToolCall tc ->
                    messages.add(new AssistantMessage(
                            "调用工具: " + tc.toolId() + "，参数: " + tc.inputJson()));
                case ReactStep.Observation obs ->
                    messages.add(new UserMessage(
                            "[工具结果] " + obs.toolId() + "：" + obs.output()));
                case ReactStep.Answer a ->
                    messages.add(new AssistantMessage(a.content()));
            }
        }

        return messages;
    }

    // ===== 核心 ReAct 循环 =====

    /**
     * ReAct 核心循环 — run() 和 runStreaming() 的共享实现。
     *
     * <p>终止条件（任一满足即退出）：
     * <ol>
     *   <li>LLM 返回纯文本（无 tool call）→ 正常完成</li>
     *   <li>Budget 任一维度超限 → 降级响应</li>
     *   <li>CancellationToken 被触发 → 中断</li>
     *   <li>迭代次数达到 maxIterations → 强制终止</li>
     *   <li>连续失败达到 maxConsecutiveFailures → 强制终止</li>
     * </ol></p>
     *
     * @param state             当前状态（done == false）
     * @param request           原始请求
     * @param traceContext      追踪上下文（可为 null）
     * @param loopStart         循环开始时间
     * @param callback          LLM 调用回调（同步 / 流式）
     * @param cancellationToken 取消信号
     * @return 循环结束后的状态（done == true 或被取消）
     */
    ReactAgentState coreLoop(
            ReactAgentState state,
            AgentRequest request,
            @Nullable TraceContext traceContext,
            Instant loopStart,
            IterationCallback callback,
            CancellationToken cancellationToken) {

        int maxIterations = config.getLoop().getMaxIterations();
        int maxConsecutiveFailures = config.getLoop().getMaxConsecutiveFailures();
        int consecutiveFailures = 0;

        for (int iteration = 0; !state.isDone(); iteration++) {
            // 1. 取消信号检查
            if (cancellationToken.isCancelled() || Thread.currentThread().isInterrupted()) {
                log.info("ReAct 循环被取消: traceId={}, iteration={}", state.traceId(), iteration);
                break;
            }

            // 2. 迭代硬限制
            if (iteration >= maxIterations) {
                log.warn("ReAct 循环达到迭代上限: traceId={}, maxIterations={}",
                        state.traceId(), maxIterations);
                state = DegradedResponseBuilder.terminateWithReason(
                        state, "循环次数达到硬限制: " + maxIterations);
                break;
            }

            // 3. 更新 Budget 已用时长 + 检查超限
            state = state.toBuilder()
                    .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                    .build();
            if (state.budget().exceeded()) {
                log.warn("ReAct 循环预算超限: traceId={}, reason={}",
                        state.traceId(), state.budget().exceedReason());
                state = DegradedResponseBuilder.terminateWithReason(
                        state, state.budget().exceedReason());
                break;
            }

            // 4. 组装上下文（临时桥接：转换为旧 AgentState，Task 13.3 后移除）
            var legacyState = toLegacyAgentState(state);
            var assembledContext = contextAssembler.assemble(legacyState);

            // 5. 构建 Spring AI 消息列表 + 获取工具回调
            var messages = buildMessages(assembledContext, state);
            var toolCallbacks = agentToolProvider.getToolCallbacks(legacyState);

            log.debug("ReAct 迭代开始: traceId={}, iteration={}, stepCount={}, toolCount={}",
                    state.traceId(), iteration, state.stepCount(), toolCallbacks.size());

            // 6. 调用 LLM（通过 IterationCallback 抽象同步/流式）
            var iterationStart = Instant.now();
            ChatResponse chatResponse;
            try {
                chatResponse = callback.callLlm(request, messages, toolCallbacks, traceContext);
            } catch (Exception e) {
                log.error("LLM 调用异常: traceId={}, iteration={}, error={}",
                        state.traceId(), iteration, e.getMessage());
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, "连续 LLM 调用失败达到上限: " + maxConsecutiveFailures);
                    break;
                }
                // 记录失败观察并继续下一次迭代
                state = state.appendStep(new ReactStep.Observation(
                        "llm", false, "LLM 调用失败: " + e.getMessage(), 0));
                continue;
            }
            var iterationDuration = Duration.between(iterationStart, Instant.now());

            // 7. 解析响应
            // Spring AI ChatClient.call() 自动执行 function calling 并返回最终文本。
            String content = extractContent(chatResponse);
            int responseTokens = estimateTokens(chatResponse);

            if (content != null && !content.isBlank()) {
                // LLM 返回了文本内容 → 记录为最终回答
                state = state.appendStep(new ReactStep.Answer(content));
                state = state.toBuilder()
                        .done(true)
                        .finalOutput(content)
                        .budget(state.budget().deductTokens(responseTokens))
                        .build();

                // Trace 记录 LLM 调用
                recordLlmStep(traceContext, state.stepCount() - 1, iterationStart,
                        iterationDuration, responseTokens);

                log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}",
                        state.traceId(), iteration + 1, state.stepCount(),
                        state.budget().tokensUsed());
            } else {
                // LLM 返回空内容 — 视为异常
                log.warn("LLM 返回空内容: traceId={}, iteration={}", state.traceId(), iteration);
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, "连续空响应达到上限: " + maxConsecutiveFailures);
                    break;
                }
            }

            // 8. 更新 Budget 已用时长
            state = state.toBuilder()
                    .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                    .build();

            // 连续成功时重置失败计数
            if (content != null && !content.isBlank()) {
                consecutiveFailures = 0;
            }
        }

        return state;
    }

    // ===== 辅助方法 =====

    /**
     * 从 ChatResponse 提取文本内容。
     *
     * @param chatResponse LLM 响应
     * @return 文本内容，无内容时返回 null
     */
    @Nullable
    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null) return null;
        var result = chatResponse.getResult();
        if (result == null) return null;
        var output = result.getOutput();
        if (output == null) return null;
        return output.getText();
    }

    /**
     * 从 ChatResponse 估算 Token 消耗。
     *
     * @param chatResponse LLM 响应
     * @return 估算的 Token 数
     */
    private int estimateTokens(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) return 0;
        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) return 0;
        return (int) (usage.getPromptTokens() + usage.getCompletionTokens());
    }

    /**
     * 记录 LLM 调用步骤到 Trace。
     */
    private void recordLlmStep(@Nullable TraceContext traceContext, int stepIndex,
                                Instant timestamp, Duration duration, int tokens) {
        if (traceContext == null) return;
        try {
            var step = new LlmCallStep(
                    stepIndex, timestamp, duration,
                    "unknown", // providerId — Task 9.2 补充完整
                    "unknown", // modelId — Task 9.2 补充完整
                    config.getLoop().getLlmScene(),
                    tokens / 2, tokens / 2, // 粗略拆分 input/output
                    duration, false, 0.7, null);
            traceRecorder.recordStep(traceContext, step);
        } catch (Exception e) {
            log.debug("Trace 记录失败，降级跳过: error={}", e.getMessage());
        }
    }

    /**
     * 临时桥接 — 将 ReactAgentState 转换为旧 AgentState。
     *
     * <p>供 ContextAssembler.assemble() 和 AgentToolProvider.getToolCallbacks() 使用，
     * 这两个接口在 Task 13 中将改为直接接受 ReactAgentState，届时移除此方法。</p>
     *
     * @param reactState ReAct 状态
     * @return 旧版 AgentState（phase 固定为 UNDERSTANDING，使用完整检索策略）
     */
    private AgentState toLegacyAgentState(ReactAgentState reactState) {
        return AgentState.builder()
                .traceId(reactState.traceId())
                .sessionId(reactState.sessionId())
                .goal(reactState.goal())
                .phase(AgentPhase.UNDERSTANDING)
                .channel(reactState.channel())
                .steps(List.of())
                .stepCount(reactState.stepCount())
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(reactState.shortTermMemory())
                .mentionedEntities(reactState.mentionedEntities())
                .budget(reactState.budget())
                .parentTraceId(reactState.parentTraceId())
                .depth(reactState.depth())
                .done(reactState.done())
                .finalOutput(reactState.finalOutput())
                .terminationReason(reactState.terminationReason())
                .reasoningSummary(reactState.reasoningSummary())
                .allowedToolIds(reactState.allowedToolIds())
                .build();
    }

    // ===== 同步执行入口 =====

    /**
     * 同步执行 ReAct 循环。
     *
     * <p>完整流程：初始化状态 → L1 写入用户消息 → 持久化用户消息 →
     * 启动 Trace → coreLoop → L1 写入助手响应 → 持久化助手消息 →
     * 异步后处理 → 构建 AgentResponse。</p>
     *
     * @param request Agent 请求
     * @return Agent 响应
     */
    public AgentResponse run(AgentRequest request) {
        ReactAgentState state = ReactAgentState.init(request);
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;

        var token = new CancellationToken();
        this.cancellationToken = token;

        try {
            state = initState(request);

            // 用户消息写入 L1（在 assembleContext 之前，确保对话历史完整）
            writeUserMessageToL1(state);

            // 同步写入用户消息到 chat_messages
            persistUserMessage(state);

            traceContext = startTraceIfEnabled(state, request);

            // Trace 启动后重新计时
            loopStart = Instant.now();

            // 核心循环 — 非流式回调
            state = coreLoop(state, request, traceContext, loopStart,
                    new NonStreamingCallback(), token);

            // 构建推理概要
            if (state.terminationReason() == null) {
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder().reasoningSummary(summary).build();
            }

            // AI 响应写入 L1
            writeAssistantMessageToL1(state);

            // 同步写入助手消息到 chat_messages
            String assistantMessageId = persistAssistantMessage(state);

            // 持久化注入记录
            persistInjectionRecord(assistantMessageId, state.sessionId());

            // 异步后处理（会话快照 + AUDN 实体提取）
            asyncPostProcess(state);

            return new AgentResponse(
                    state.traceId(),
                    state.sessionId(),
                    state.finalOutput() != null ? state.finalOutput() : "",
                    state.budget().tokensUsed(),
                    state.stepCount(),
                    state.terminationReason(),
                    assistantMessageId,
                    null, // a2uiComponents — Task 8 补充
                    null  // tokenUsage — Task 9.2 补充
            );

        } catch (Exception e) {
            log.error("ReAct 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            // 使用旧 AgentState 构建错误响应（AgentResponse.error 依赖旧类型）
            return AgentResponse.error(toLegacyAgentState(state), e);
        } finally {
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state.finalOutput();
                boolean success = error == null && state.terminationReason() == null;
                String errorMessage = error != null ? error.getMessage() : null;
                String terminationReason = error != null
                        ? error.getClass().getSimpleName()
                        : state.terminationReason();
                traceRecorder.endTrace(traceContext, finalOutput, success,
                        errorMessage, terminationReason);
            }
        }
    }

    // ===== SSE 流式执行入口 =====

    /**
     * SSE 流式执行 ReAct 循环。
     *
     * <p>完整流程与 run() 一致，但通过 SSE 推送 TOKEN / REASONING / DONE 事件。</p>
     *
     * @param request           Agent 请求
     * @param streamId          SSE 流标识
     * @param sseManager        SSE 会话管理器
     * @param cancellationToken 取消信号
     */
    public void runStreaming(AgentRequest request, String streamId,
                             SseSessionManager sseManager,
                             CancellationToken cancellationToken) {
        ReactAgentState state = ReactAgentState.init(request);
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        String finalContent = "";
        TokenUsage finalTokenUsage = null;
        String reasoningSummary = null;
        String tempTurnId = UUID.randomUUID().toString();
        String userMessageId = null;
        String assistantMessageId = null;

        var token = cancellationToken;
        this.cancellationToken = token;

        try {
            state = initState(request);

            // 用户消息写入 L1
            writeUserMessageToL1(state);

            // 同步写入用户消息到 chat_messages
            if (conversationHistoryStore != null && state.goal() != null && !state.goal().isBlank()) {
                try {
                    userMessageId = conversationHistoryStore.appendUserMessage(
                            state.sessionId(), state.goal(), state.traceId());
                } catch (Exception e) {
                    log.warn("用户消息同步写入失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
                }
            }

            // TRACE_START 事件
            if (state.traceId() != null) {
                var traceStartData = new HashMap<String, Object>();
                traceStartData.put("sessionId", request.sessionId());
                traceStartData.put("turnId", tempTurnId);
                traceStartData.put("traceId", state.traceId());
                traceStartData.put("timestamp", Instant.now().toEpochMilli());
                if (userMessageId != null) {
                    traceStartData.put("userMessageId", userMessageId);
                }
                sseManager.sendEvent(streamId, SseEventType.TRACE_START, traceStartData);
            }

            // AGENT_START 推理事件
            sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                    "AGENT_START", "开始处理请求",
                    "Agent 已接收到用户请求，正在准备上下文与预算。",
                    null, Map.of());

            traceContext = startTraceIfEnabled(state, request);
            loopStart = Instant.now();

            // 核心循环 — 流式回调
            var callback = new StreamingCallback(sseManager, streamId, request.sessionId(), tempTurnId);
            state = coreLoop(state, request, traceContext, loopStart, callback, token);

            // 检查流式错误
            if (callback.hasStreamingError()) {
                error = callback.getStreamingError();
            } else {
                // 归一化最终输出
                String callbackContent = callback.getFinalContent();
                finalContent = state.finalOutput() != null
                        ? state.finalOutput()
                        : (callbackContent != null ? callbackContent : finalContent);
                var extractedFinalContent = extractA2uiContent(finalContent);
                A2uiComponentTree finalA2uiTree = extractedFinalContent.tree();
                finalContent = extractedFinalContent.visibleText();
                lastCollectedA2uiTree = finalA2uiTree != null ? finalA2uiTree : lastCollectedA2uiTree;

                if (state.terminationReason() == null) {
                    reasoningSummary = buildReasoningSummary(state, traceContext);
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .reasoningSummary(reasoningSummary)
                            .build();
                }

                // AI 响应写入 L1
                writeAssistantMessageToL1(state);

                // 同步写入助手消息到 chat_messages
                String a2uiJson = serializeA2uiTree(lastCollectedA2uiTree);
                if (conversationHistoryStore != null
                        && ((finalContent != null && !finalContent.isBlank()) || a2uiJson != null)) {
                    try {
                        assistantMessageId = conversationHistoryStore.appendAssistantMessage(
                                state.sessionId(),
                                finalContent != null ? finalContent : "",
                                reasoningSummary, state.traceId(), a2uiJson);
                    } catch (Exception e) {
                        log.warn("助手消息同步写入失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
                    }
                }

                // 持久化注入记录
                persistInjectionRecord(assistantMessageId, state.sessionId());

                // 异步后处理
                asyncPostProcess(state);

                // 聚合 Token 使用量
                finalTokenUsage = aggregateTokenUsage(traceContext);
            }
        } catch (Exception e) {
            log.error("流式 Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
        } finally {
            // 轨迹记录
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state.finalOutput();
                boolean success = error == null && state.terminationReason() == null;
                String errorMessage = error != null ? error.getMessage() : null;
                String terminationReason = error != null
                        ? error.getClass().getSimpleName()
                        : state.terminationReason();
                traceRecorder.endTrace(traceContext, finalOutput, success, errorMessage, terminationReason);
            }

            // 发送 DONE 或 ERROR 事件
            if (error != null) {
                sendStreamError(sseManager, streamId, 500,
                        "处理失败: " + error.getMessage(), state.traceId());
            } else {
                sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                        "ANSWER_FINALIZED", "回答已生成", "本轮推理与回答已完成。",
                        null, Map.of());
                var doneData = buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        traceContext, reasoningSummary, finalContent, assistantMessageId);
                sseManager.sendEvent(streamId, SseEventType.DONE, doneData);
                lastCollectedA2uiTree = null;
                sseManager.closeEmitter(streamId);
            }
        }
    }

    // ===== 非流式 LLM 回调 =====

    /**
     * 非流式迭代回调 — run() 使用。
     *
     * <p>通过 ChatClient.prompt().system().user().toolCallbacks().call() 同步调用 LLM。
     * Spring AI 自动处理 function calling 并返回最终文本。</p>
     */
    private class NonStreamingCallback implements IterationCallback {
        @Override
        public ChatResponse callLlm(AgentRequest request,
                                     List<Message> messages,
                                     List<ToolCallback> toolCallbacks,
                                     @Nullable TraceContext traceContext) {
            String scene = config.getLoop().getLlmScene();
            var chatClient = llmRouter.getChatClient(scene, request.preferredProvider());

            // 从消息列表提取 system/user 文本
            String systemText = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).getText())
                    .findFirst().orElse("");
            String userText = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> ((UserMessage) m).getText())
                    .findFirst().orElse("");

            var spec = chatClient.prompt();
            if (!systemText.isBlank()) {
                spec = spec.system(systemText);
            }
            spec = spec.user(userText);

            // 注入工具回调
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                ToolCallback[] callbacks = toolCallbacks.stream()
                        .filter(Objects::nonNull)
                        .toArray(ToolCallback[]::new);
                if (callbacks.length > 0) {
                    spec = spec.toolCallbacks(callbacks);
                }
            }

            return spec.call().chatResponse();
        }
    }

    // ===== 流式 LLM 回调 =====

    /**
     * 流式迭代回调 — runStreaming() 使用。
     *
     * <p>通过 Flux&lt;String&gt; 流式调用 LLM，逐 token 发送 SSE TOKEN 事件，
     * 支持 A2UI 流式解析和多模态路径。</p>
     */
    private class StreamingCallback implements IterationCallback {

        private final SseSessionManager sseManager;
        private final String streamId;
        private final String sessionId;
        private final String turnId;

        @Nullable private Exception streamingError;
        @Nullable private String finalContent;

        StreamingCallback(SseSessionManager sseManager, String streamId,
                          String sessionId, String turnId) {
            this.sseManager = sseManager;
            this.streamId = streamId;
            this.sessionId = sessionId;
            this.turnId = turnId;
        }

        @Override
        public ChatResponse callLlm(AgentRequest request,
                                     List<Message> messages,
                                     List<ToolCallback> toolCallbacks,
                                     @Nullable TraceContext traceContext) {
            // CONTEXT_LOADING 推理事件
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "CONTEXT_LOADING", "分析问题与上下文",
                    "正在梳理本轮问题、会话历史与可用记忆。",
                    null, Map.of());

            // MEMORY_RETRIEVAL 推理事件
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "MEMORY_RETRIEVAL", "检索相关记忆",
                    "已基于最近对话与知识收集相关记忆，用于本轮推理。",
                    null, Map.of());

            // ANSWER_DRAFTING 推理事件
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "ANSWER_DRAFTING", "正在生成回答",
                    "模型正在根据上下文整理最终回答。",
                    null, Map.of());

            String scene = config.getLoop().getLlmScene();
            String preferredProviderId = request.preferredProvider();

            // 从消息列表提取 system/user 文本
            String systemText = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).getText())
                    .findFirst().orElse("");
            String userText = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> ((UserMessage) m).getText())
                    .findFirst().orElse("");

            // 追加流式约束提示词
            String streamingSystemPrompt = appendPromptSection(systemText,
                    promptRegistry.render("agent/streaming-constraint"));

            // A2UI 系统提示词注入
            if (isA2uiEnabled()) {
                streamingSystemPrompt = appendPromptSection(streamingSystemPrompt,
                        A2uiComponentCatalog.renderPrompt(a2uiProperties.maxComponentsPerTree()));
            }

            // 调试日志
            logLlmPromptIfEnabled(scene, streamingSystemPrompt, userText, toolCallbacks);

            // 构建完整提示词（用于 trace 记录）
            String fullPrompt = (streamingSystemPrompt != null && !streamingSystemPrompt.isBlank()
                    ? streamingSystemPrompt + "\n\n" : "") + userText;

            // 构建流式响应
            Flux<String> tokenStream;
            String providerId;
            String modelId;

            var mediaList = request.mediaContents();
            if (mediaList != null && !mediaList.isEmpty() && multimodalRouter != null) {
                // 多模态路径
                var mmRequest = new MultimodalRequest(
                        scene, fullPrompt, mediaList, null, preferredProviderId);
                var streaming = multimodalRouter.streamWithInfo(mmRequest);
                tokenStream = streaming.stream();
                providerId = streaming.providerId();
                modelId = streaming.modelId();
            } else {
                // 纯文本路径 — 流式不注入 toolCallbacks，避免不可追踪的 function call
                var clientInfo = llmRouter.getChatClientWithInfo(scene, preferredProviderId);
                var prompt = clientInfo.client().prompt();
                if (streamingSystemPrompt != null && !streamingSystemPrompt.isBlank()) {
                    prompt = prompt.system(streamingSystemPrompt);
                }
                tokenStream = prompt.user(userText).stream().content();
                providerId = clientInfo.providerId();
                modelId = clientInfo.modelId();
            }

            if (tokenStream == null) {
                throw new IllegalStateException("流式响应为 null");
            }

            // A2UI 流式解析器
            boolean a2uiEnabled = isA2uiEnabled();
            StreamingA2uiParser a2uiParser = a2uiEnabled ? new StreamingA2uiParser() : null;
            A2uiComponentTree[] latestA2uiTree = {null};
            int maxComponents = a2uiEnabled ? a2uiProperties.maxComponentsPerTree() : 0;

            StringBuilder contentBuilder = new StringBuilder();
            int[] tokenIndex = {0};
            Instant start = Instant.now();

            // 订阅流式响应
            tokenStream.doOnNext(tok -> {
                contentBuilder.append(tok);
                if (a2uiParser != null) {
                    var segments = a2uiParser.feed(tok);
                    for (var segment : segments) {
                        switch (segment) {
                            case StreamingA2uiParser.Segment.TextSegment(var text) -> {
                                if (!text.isEmpty()) {
                                    sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                            "sessionId", sessionId,
                                            "turnId", turnId,
                                            "content", text,
                                            "index", tokenIndex[0]++));
                                }
                            }
                            case StreamingA2uiParser.Segment.A2uiSegment(var json) -> {
                                if (latestA2uiTree[0] == null) {
                                    var tree = parseAndValidateA2uiTree(json, maxComponents);
                                    if (tree != null) {
                                        latestA2uiTree[0] = tree;
                                        sseManager.sendEvent(streamId, SseEventType.UI, Map.of(
                                                "sessionId", sessionId,
                                                "turnId", turnId,
                                                "components", tree.components()));
                                    }
                                }
                            }
                        }
                    }
                } else {
                    sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                            "sessionId", sessionId,
                            "turnId", turnId,
                            "content", tok,
                            "index", tokenIndex[0]++));
                }
            })
            .doOnComplete(() -> {
                if (a2uiParser != null) {
                    var remaining = a2uiParser.flush();
                    for (var segment : remaining) {
                        if (segment instanceof StreamingA2uiParser.Segment.TextSegment(var text)
                                && !text.isEmpty()) {
                            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                    "sessionId", sessionId,
                                    "turnId", turnId,
                                    "content", text,
                                    "index", tokenIndex[0]++));
                        }
                    }
                }
            })
            .doOnError(err -> log.error("流式 LLM 调用失败: scene={}, error={}", scene, err.getMessage(), err))
            .blockLast();

            String rawContent = contentBuilder.toString();
            this.finalContent = rawContent;

            // 更新 A2UI 树
            if (latestA2uiTree[0] != null) {
                lastCollectedA2uiTree = latestA2uiTree[0];
            }

            // 记录流式 LLM Step
            recordStreamingLlmStep(traceContext, start, providerId, modelId,
                    scene, fullPrompt, rawContent, null);

            // 构造一个合成的 ChatResponse 供 coreLoop 解析
            // 流式模式下 content 已收集完毕，构造一个包含文本的 ChatResponse
            var generation = new org.springframework.ai.chat.model.Generation(
                    new AssistantMessage(rawContent));
            return new ChatResponse(List.of(generation));
        }

        boolean hasStreamingError() { return streamingError != null; }
        @Nullable Exception getStreamingError() { return streamingError; }
        @Nullable String getFinalContent() { return finalContent; }
    }

    // ===== 状态初始化 =====

    /**
     * 初始化 ReAct 状态 — 查找已有会话或创建新状态。
     */
    private ReactAgentState initState(AgentRequest request) {
        var existingSession = sessionManager.findSession(request.sessionId());
        if (existingSession.isPresent()) {
            var snapshot = existingSession.get();
            var state = ReactAgentState.fromSession(snapshot, request);
            hydrateWorkingMemoryFromConversationView(snapshot.sessionId());
            return state;
        }
        return ReactAgentState.init(request);
    }

    /**
     * 启动 Trace（如果 TraceRecorder 可用）。
     */
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    // ===== L1 工作记忆读写 =====

    /**
     * 将用户消息写入 L1 工作记忆。
     */
    private void writeUserMessageToL1(ReactAgentState state) {
        if (workingMemory == null || state.goal() == null || state.goal().isBlank()) return;
        try {
            int tokens = estimateTextTokens(state.goal());
            var slot = com.lifepilot.memory.working.ConversationSlot.userMessage(
                    state.goal(), tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("用户消息写入 L1 失败，降级为无对话历史: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /**
     * 将 AI 响应写入 L1 工作记忆。
     */
    private void writeAssistantMessageToL1(ReactAgentState state) {
        if (workingMemory == null) return;
        String response = state.finalOutput();
        if (response == null || response.isBlank()) return;
        try {
            int tokens = estimateTextTokens(response);
            var slot = com.lifepilot.memory.working.ConversationSlot.assistantMessage(
                    response, tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("AI 响应写入 L1 失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /**
     * 当 L1 为空时，从 ConversationViewService 回灌对话历史。
     */
    private void hydrateWorkingMemoryFromConversationView(String sessionId) {
        if (workingMemory == null || conversationViewService == null
                || sessionId == null || sessionId.isBlank()) return;
        try {
            var existingSlots = workingMemory.getContext(sessionId);
            if (existingSlots != null && !existingSlots.isEmpty()) return;

            var turns = conversationViewService.getRecentTurns(
                    sessionId, config.getSession().getMaxRecentTurns());
            for (var turn : turns) {
                if (turn.content() != null && !turn.content().isBlank()) {
                    if ("user".equalsIgnoreCase(turn.role())) {
                        workingMemory.append(sessionId,
                                com.lifepilot.memory.working.ConversationSlot.userMessage(
                                        turn.content(), estimateTextTokens(turn.content())));
                    } else if ("assistant".equalsIgnoreCase(turn.role())) {
                        workingMemory.append(sessionId,
                                com.lifepilot.memory.working.ConversationSlot.assistantMessage(
                                        turn.content(), estimateTextTokens(turn.content())));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("L1 对话历史回灌失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // ===== 对话历史持久化 =====

    /**
     * 同步写入用户消息到 chat_messages。
     */
    private void persistUserMessage(ReactAgentState state) {
        if (conversationHistoryStore == null
                || state.goal() == null || state.goal().isBlank()) return;
        try {
            conversationHistoryStore.appendUserMessage(
                    state.sessionId(), state.goal(), state.traceId());
        } catch (Exception e) {
            log.warn("用户消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /**
     * 同步写入助手消息到 chat_messages。
     *
     * @return 后端生成的 messageId（可为 null）
     */
    @Nullable
    private String persistAssistantMessage(ReactAgentState state) {
        if (conversationHistoryStore == null) return null;
        String output = state.finalOutput();
        if (output == null || output.isBlank()) return null;
        try {
            return conversationHistoryStore.appendAssistantMessage(
                    state.sessionId(), output, state.reasoningSummary(),
                    state.traceId(), null);
        } catch (Exception e) {
            log.warn("助手消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 持久化注入记录 — 将本次注入的记忆实体 ID 关联到助手消息。
     */
    private void persistInjectionRecord(@Nullable String messageId,
                                         @Nullable String sessionId) {
        var entityIds = lastInjectedEntityIds;
        if (injectionRecordRepository == null || entityIds.isEmpty()
                || messageId == null || messageId.isBlank()) return;
        try {
            injectionRecordRepository.save(messageId, sessionId, entityIds);
            log.debug("注入记录已持久化: messageId={}, entityCount={}", messageId, entityIds.size());
        } catch (Exception e) {
            log.warn("注入记录持久化失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    // ===== 异步后处理 =====

    /**
     * 异步后处理 — 会话快照持久化 + AUDN 实体提取。
     */
    private void asyncPostProcess(ReactAgentState finalState) {
        // 临时桥接：SessionManager.saveSession 目前接受 AgentState
        var legacyState = toLegacyAgentState(finalState);
        Thread.startVirtualThread(() -> {
            try {
                sessionManager.saveSession(legacyState);
            } catch (Exception e) {
                log.warn("会话快照持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            try {
                if (realtimeExtractor != null && finalState.finalOutput() != null) {
                    realtimeExtractor.extractAsync(
                            finalState.sessionId(),
                            finalState.goal(),
                            finalState.finalOutput());
                }
            } catch (Exception e) {
                log.warn("AUDN 实时实体提取失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
    }

    // ===== 推理概要 =====

    /**
     * 构建推理概要字符串。
     */
    private String buildReasoningSummary(ReactAgentState state,
                                          @Nullable TraceContext traceContext) {
        int steps = state.stepCount();
        int tokens = state.budget() != null ? state.budget().tokensUsed() : 0;
        String modelId = "agent";
        // Task 9.2 将从 TraceContext 聚合更精确的 Token 和模型信息
        return "本轮推理已完成，使用模型 %s，经历 %d 个推理步骤，累计约 %d 个 Token。"
                .formatted(modelId, steps, tokens);
    }

    // ===== Token 估算 =====

    /**
     * 估算文本 Token 数（中文按 1 字 1 Token，英文按 4 字符 1 Token）。
     */
    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    // ===== SSE 推理事件 =====

    /**
     * 发送 REASONING SSE 事件。
     */
    private void sendReasoningEvent(SseSessionManager sseManager, String streamId,
                                     String sessionId, String turnId,
                                     String type, String title, String description,
                                     @Nullable String toolName, Map<String, Object> extra) {
        try {
            var eventDetail = new HashMap<String, Object>();
            eventDetail.put("id", UUID.randomUUID().toString());
            eventDetail.put("type", type);
            eventDetail.put("title", title);
            eventDetail.put("description", description);
            eventDetail.put("createdAt", Instant.now().toString());
            eventDetail.put("extra", extra != null ? extra : Map.of());
            if (toolName != null) {
                eventDetail.put("toolName", toolName);
            }
            var eventPayload = new HashMap<String, Object>();
            eventPayload.put("sessionId", sessionId);
            eventPayload.put("turnId", turnId);
            eventPayload.put("event", eventDetail);
            sseManager.sendEvent(streamId, SseEventType.REASONING, eventPayload);
        } catch (Exception e) {
            log.debug("发送 reasoning 事件失败: type={}, error={}", type, e.getMessage());
        }
    }

    /**
     * 发送 SSE ERROR 事件并关闭连接。
     */
    private void sendStreamError(SseSessionManager sseManager, String streamId,
                                  int code, String message, @Nullable String traceId) {
        var errorData = new HashMap<String, Object>();
        errorData.put("code", code);
        errorData.put("message", message);
        if (traceId != null) {
            errorData.put("traceId", traceId);
        }
        sseManager.sendEvent(streamId, SseEventType.ERROR, errorData);
        sseManager.closeEmitter(streamId);
    }

    // ===== DONE 事件构建 =====

    /**
     * 构建 DONE 事件 payload。
     */
    private Map<String, Object> buildDoneEventPayload(AgentRequest request,
                                                       ReactAgentState state,
                                                       String tempTurnId,
                                                       @Nullable TokenUsage finalTokenUsage,
                                                       @Nullable TraceContext traceContext,
                                                       @Nullable String reasoningSummary,
                                                       @Nullable String finalContent,
                                                       @Nullable String assistantMessageId) {
        var doneData = new HashMap<String, Object>();
        doneData.put("messageId", assistantMessageId != null ? assistantMessageId : tempTurnId);
        doneData.put("sessionId", request.sessionId());
        doneData.put("turnId", tempTurnId);

        if (finalTokenUsage != null) {
            var tokenUsageMap = new HashMap<String, Object>();
            tokenUsageMap.put("promptTokens", finalTokenUsage.promptTokens());
            tokenUsageMap.put("completionTokens", finalTokenUsage.completionTokens());
            tokenUsageMap.put("totalTokens", finalTokenUsage.totalTokens());
            tokenUsageMap.put("modelId", finalTokenUsage.modelId());
            doneData.put("tokenUsage", tokenUsageMap);
        }

        // 工具调用摘要
        if (traceContext != null && !traceContext.steps().isEmpty()) {
            var toolSummaries = new ArrayList<Map<String, Object>>();
            for (var step : traceContext.steps()) {
                if (step instanceof ToolCallStep toolStep) {
                    var toolSummary = new HashMap<String, Object>();
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

        // 知识库来源
        var sources = buildKnowledgeSources(request.sessionId());
        if (!sources.isEmpty()) {
            doneData.put("sources", sources);
        }

        doneData.put("timestamp", Instant.now().toEpochMilli());
        if (state.traceId() != null) {
            doneData.put("traceId", state.traceId());
        }
        if (reasoningSummary != null) {
            doneData.put("reasoningSummary", reasoningSummary);
        }
        if (lastCollectedA2uiTree != null && !lastCollectedA2uiTree.components().isEmpty()) {
            doneData.put("a2uiComponents", lastCollectedA2uiTree.components());
        }

        var contents = new ArrayList<Map<String, Object>>();
        if (finalContent != null && !finalContent.isBlank()) {
            var textContent = new HashMap<String, Object>();
            textContent.put("type", "TEXT");
            textContent.put("text", finalContent);
            contents.add(textContent);
        }
        doneData.put("contents", contents);
        return doneData;
    }

    // ===== 知识库来源 =====

    /**
     * 构建知识库来源摘要。
     */
    private List<Map<String, Object>> buildKnowledgeSources(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionKnowledgeBaseRepository == null) {
            return List.of();
        }
        try {
            var kbIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId);
            if (kbIds == null || kbIds.isEmpty()) return List.of();
            var result = new ArrayList<Map<String, Object>>();
            for (String kbId : kbIds) {
                if (kbId == null || kbId.isBlank()) continue;
                String name = kbId;
                if (knowledgeBaseRepository != null) {
                    try {
                        var kbOpt = knowledgeBaseRepository.findById(kbId);
                        if (kbOpt.isPresent() && kbOpt.get().name() != null
                                && !kbOpt.get().name().isBlank()) {
                            name = kbOpt.get().name();
                        }
                    } catch (Exception ignore) { /* 回退为 ID */ }
                }
                var source = new HashMap<String, Object>();
                source.put("type", "knowledgeBase");
                source.put("id", kbId);
                source.put("name", name);
                result.add(source);
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            log.debug("构建知识库来源摘要失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    // ===== Token 聚合 =====

    /**
     * 从 TraceContext 聚合 Token 使用量。
     */
    private TokenUsage aggregateTokenUsage(@Nullable TraceContext traceContext) {
        String modelId = DEFAULT_MODEL_ID;
        int promptTokens = 0;
        int completionTokens = 0;
        if (traceContext != null) {
            promptTokens = traceContext.totalInputTokens();
            completionTokens = traceContext.totalOutputTokens();
            var steps = traceContext.steps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                var step = steps.get(i);
                if (step instanceof LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    break;
                }
            }
        }
        return new TokenUsage(promptTokens, completionTokens,
                promptTokens + completionTokens, modelId);
    }

    // ===== 流式 LLM Trace 记录 =====

    /**
     * 记录流式 LLM 调用步骤到 Trace。
     */
    private void recordStreamingLlmStep(@Nullable TraceContext traceContext,
                                         Instant startTime, String providerId,
                                         String modelId, String scene,
                                         @Nullable String prompt, @Nullable String output,
                                         @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) return;
        Instant end = Instant.now();
        Duration d = Duration.between(startTime, end);
        int inputTokens = estimateTextTokens(prompt != null ? prompt : "");
        int outputTokens = error != null ? 0 : estimateTextTokens(output != null ? output : "");
        String finishReason = error != null ? ("error: " + error.getMessage()) : "stream_complete";
        int stepIndex = traceContext.steps() != null ? traceContext.steps().size() : 0;
        var step = new LlmCallStep(
                stepIndex, end, d,
                providerId != null ? providerId : "unknown",
                modelId != null ? modelId : "unknown",
                scene != null ? scene : "unknown",
                inputTokens, outputTokens, d, false, 0.0d, finishReason);
        traceRecorder.recordStep(traceContext, step);
    }

    // ===== A2UI 辅助方法 =====

    /**
     * 判断 A2UI 功能是否启用。
     */
    private boolean isA2uiEnabled() {
        return a2uiProperties != null && a2uiProperties.enabled();
    }

    /**
     * 从非流式响应中提取 A2UI 内容。
     */
    private A2uiPayloadSupport.ParsedA2uiContent extractA2uiContent(@Nullable String content) {
        if (!isA2uiEnabled()) {
            return new A2uiPayloadSupport.ParsedA2uiContent(content != null ? content : "", null);
        }
        return A2uiPayloadSupport.extractContent(content, objectMapper, a2uiProperties.maxComponentsPerTree());
    }

    /**
     * 序列化 A2UI 组件树为 JSON。
     */
    @Nullable
    private String serializeA2uiTree(@Nullable A2uiComponentTree tree) {
        if (tree == null || tree.components().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            log.warn("A2UI 组件树序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析并校验 A2UI JSON 为组件树。
     */
    @Nullable
    private A2uiComponentTree parseAndValidateA2uiTree(String json, int maxComponents) {
        try {
            var tree = A2uiPayloadSupport.normalizeTree(
                    objectMapper.readValue(json, A2uiComponentTree.class));
            var validation = com.lifepilot.interaction.web.a2ui.A2uiComponentValidator
                    .validate(tree, maxComponents);
            if (!validation.valid()) {
                log.warn("A2UI 载荷校验失败: errors={}", validation.errors());
                return null;
            }
            return validation.truncatedTree() != null ? validation.truncatedTree() : tree;
        } catch (Exception e) {
            log.warn("A2UI 载荷解析失败: error={}", e.getMessage());
            return null;
        }
    }

    // ===== 提示词辅助 =====

    /**
     * 拼接提示词段落。
     */
    @Nullable
    private String appendPromptSection(@Nullable String base, @Nullable String extra) {
        if (extra == null || extra.isBlank()) return base;
        if (base == null || base.isBlank()) return extra;
        return base + "\n" + extra;
    }

    /**
     * 调试日志 — 打印完整 LLM 提示词。
     */
    private void logLlmPromptIfEnabled(String scene, @Nullable String systemPrompt,
                                        @Nullable String userText,
                                        @Nullable List<ToolCallback> toolCallbacks) {
        if (config == null || config.getDebug() == null || !config.getDebug().isLogLlmPrompts()) {
            return;
        }
        List<String> toolNames = new ArrayList<>();
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            toolNames = toolCallbacks.stream()
                    .filter(Objects::nonNull)
                    .map(cb -> cb.getToolDefinition().name())
                    .distinct()
                    .collect(Collectors.toList());
        }
        log.info("""
                ========== LLM PROMPT ==========
                scene={} traceId=react
                tools={}
                -------- SYSTEM --------
                {}
                -------- USER --------
                {}
                ========= END PROMPT ==========""",
                scene, toolNames,
                systemPrompt != null ? systemPrompt : "",
                userText != null ? userText : "");
    }

    // ===== 流式错误持久化 =====

    /**
     * 持久化流式系统错误消息到对话历史和 L1。
     */
    private void persistStreamingSystemError(@Nullable String sessionId,
                                              @Nullable String traceId,
                                              @Nullable Exception e) {
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            String detail = e != null ? e.getMessage() : "unknown";
            String content = "系统提示：模型服务暂时不可用，请稍后重试。\n（错误信息）" + detail;
            if (conversationHistoryStore != null) {
                conversationHistoryStore.appendSystemMessage(sessionId, content, traceId);
            }
            if (workingMemory != null) {
                int tokens = estimateTextTokens(content);
                workingMemory.append(sessionId,
                        com.lifepilot.memory.working.ConversationSlot.systemMessage(content, tokens));
            }
        } catch (Exception ignore) { /* 不影响主流程 */ }
    }
}
