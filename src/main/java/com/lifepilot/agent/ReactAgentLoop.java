package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;;

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
}
