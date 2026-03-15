package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.a2ui.A2uiComponentCatalog;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidationException;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeTypeUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct Agent 循环 — 真正的 Thought → Action → Observation 架构。
 *
 * <p>核心循环每次迭代：
 * <ol>
 *   <li>调用 LLM（禁用自动 tool calling）获取原始响应</li>
 *   <li>检查响应是否包含 tool call 请求</li>
 *   <li>若有 tool call → 记录 ToolCall 步骤 → 手动执行工具 → 记录 Observation 步骤 → 继续循环</li>
 *   <li>若无 tool call（纯文本）→ 记录 Answer 步骤 → 循环结束</li>
 * </ol>
 *
 * <p>通过 {@code ChatModel.call(Prompt)} + {@code internalToolExecutionEnabled=false}
 * 实现手动 tool calling 控制，确保每个工具调用都被显式记录到 ReAct 步骤和 Trace 中。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
public class ReactAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";

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
    @Nullable private final MultimodalRouter multimodalRouter; // 预留：多模态流式请求
    @Nullable private final MediaDataExtractor mediaDataExtractor;
    @Nullable private final MediaValidator mediaValidator;
    @Nullable private final MediaProcessor mediaProcessor;
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final ConversationHistoryStore conversationHistoryStore;
    @Nullable private final ConversationViewService conversationViewService;
    @Nullable private final RealtimeExtractor realtimeExtractor;
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final A2uiProperties a2uiProperties;

    // ===== 可选依赖（附件持久化） =====
    @Nullable private final AttachmentRepository attachmentRepository;

    // ===== 运行时状态（volatile） =====
    private volatile A2uiComponentTree lastCollectedA2uiTree;
    private final List<String> lastInjectedEntityIds = List.of();
    private volatile CancellationToken cancellationToken;

    /** 收集本轮工具执行中通过 SSE MEDIA 事件发送的媒体数据，用于流式完成后持久化到附件表。 */
    private final List<MediaDataExtractor.MediaItem> collectedToolMedia = new ArrayList<>();

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
            @Nullable MediaValidator mediaValidator,
            @Nullable MediaProcessor mediaProcessor,
            @Nullable WorkingMemory workingMemory,
            @Nullable ConversationHistoryStore conversationHistoryStore,
            @Nullable ConversationViewService conversationViewService,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
            @Nullable A2uiProperties a2uiProperties,
            @Nullable AttachmentRepository attachmentRepository) {
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
        this.mediaValidator = mediaValidator;
        this.mediaProcessor = mediaProcessor;
        this.workingMemory = workingMemory;
        this.conversationHistoryStore = conversationHistoryStore;
        this.conversationViewService = conversationViewService;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.a2uiProperties = a2uiProperties;
        this.attachmentRepository = attachmentRepository;
    }

    /** 获取当前取消令牌（外部可调用 cancel() 中断循环）。 */
    @Nullable
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /** 获取多模态路由器（预留：多模态流式请求）。 */
    @Nullable
    public MultimodalRouter getMultimodalRouter() {
        return multimodalRouter;
    }

    /**
     * 迭代回调 — 抽象 LLM 调用方式（同步 / 流式）。
     *
     * <p>返回原始 ChatResponse（不自动执行 tool call），
     * 由 coreLoop 负责解析 tool call 并手动执行。</p>
     */
    @FunctionalInterface
    interface IterationCallback {
        /**
         * 调用 LLM 并返回原始响应（不自动执行 tool call）。
         *
         * @param request       原始请求
         * @param messages      Spring AI 消息列表
         * @param toolCallbacks 工具回调列表（用于构建 tool definition，不自动执行）
         * @param traceContext  追踪上下文
         * @return LLM 原始响应（可能包含 tool call 请求）
         */
        ChatResponse callLlm(AgentRequest request,
                             List<Message> messages,
                             List<ToolCallback> toolCallbacks,
                             @Nullable TraceContext traceContext);

        /** 获取本次调用的 Provider ID（用于 Trace 记录）。 */
        default String getProviderId() { return DEFAULT_MODEL_ID; }

        /** 获取本次调用的 Model ID（用于 Trace 记录）。 */
        default String getModelId() { return DEFAULT_MODEL_ID; }
    }

    /**
     * 构建 Spring AI 消息列表。
     *
     * <p>将 AssembledContext 的 systemPrompt / userPrompt 和历史 ReactStep
     * 转换为 Spring AI Message 序列。工具调用历史使用 Spring AI 原生的
     * AssistantMessage（含 toolCalls）+ ToolResponseMessage 格式，
     * 确保 LLM 能正确理解多轮 tool calling 上下文。</p>
     */
    List<Message> buildMessages(AssembledContext ctx, ReactAgentState state) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(ctx.systemPrompt()));

        // 有媒体内容时（用户上传或工具产生），将 MediaContent 转换为 Spring AI Media 嵌入 UserMessage
        if (ctx.mediaContents() != null && !ctx.mediaContents().isEmpty()) {
            var builder = UserMessage.builder().text(ctx.userPrompt());
            for (var mc : ctx.mediaContents()) {
                builder.media(new Media(
                        MimeTypeUtils.parseMimeType(mc.mimeType()),
                        new ByteArrayResource(mc.data())));
            }
            messages.add(builder.build());
        } else {
            messages.add(new UserMessage(ctx.userPrompt()));
        }

        // 历史 ReactStep 转换为 Spring AI 消息
        for (var step : state.steps()) {
            switch (step) {
                case ReactStep.Thought t ->
                        messages.add(new AssistantMessage(t.content()));
                case ReactStep.ToolCall tc -> {
                    // 构建 AssistantMessage 携带 tool call 元数据（使用 Builder API）
                    var toolCall = new AssistantMessage.ToolCall(
                            tc.toolId(), "function", tc.toolId(), tc.inputJson());
                    messages.add(AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(toolCall))
                            .build());
                }
                case ReactStep.Observation obs -> {
                    // 使用 ToolResponseMessage 传递工具执行结果（使用 Builder API）
                    var toolResponse = ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    obs.toolId(), obs.toolId(), obs.output())))
                            .build();
                    messages.add(toolResponse);
                }
                case ReactStep.Answer a ->
                        messages.add(new AssistantMessage(a.content()));
            }
        }
        return messages;
    }

    // ===== 核心 ReAct 循环 =====

    /**
     * ReAct 核心循环 — 真正的 Thought → Action → Observation 架构。
     *
     * <p>每次迭代：
     * <ol>
     *   <li>调用 LLM（禁用自动 tool calling）获取原始响应</li>
     *   <li>检查 AssistantMessage.hasToolCalls()</li>
     *   <li>若有 tool call → 记录 ToolCall 步骤 → 手动执行 → 媒体提取 → 记录 Observation → 继续</li>
     *   <li>若无 tool call → 记录 Answer → done=true</li>
     * </ol>
     *
     * <p>终止条件（任一满足即退出）：
     * <ol>
     *   <li>LLM 返回纯文本（无 tool call）→ 正常完成</li>
     *   <li>Budget 任一维度超限 → 降级响应</li>
     *   <li>CancellationToken 被触发 → 中断</li>
     *   <li>迭代次数达到 maxIterations → 强制终止</li>
     *   <li>连续失败达到 maxConsecutiveFailures → 强制终止</li>
     * </ol>
     */
    ReactAgentState coreLoop(
            ReactAgentState state,
            AgentRequest request,
            @Nullable TraceContext traceContext,
            Instant loopStart,
            IterationCallback callback,
            CancellationToken cancellationToken,
            @Nullable SseSessionManager sseManager,
            @Nullable String streamId) {

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

            // 4. 组装上下文 + 构建消息 + 获取工具回调
            var assembledContext = contextAssembler.assemble(state);
            // 首轮迭代注入用户上传的媒体内容到上下文
            if (state.steps().isEmpty() && hasMultimodalContent(request)) {
                assembledContext = assembledContext.withMediaContents(request.mediaContents());
            }
            // 非首轮迭代：有 pendingMedia 时注入工具产生的媒体
            else if (state.pendingMedia() != null && !state.pendingMedia().isEmpty()) {
                assembledContext = assembledContext.withMediaContents(state.pendingMedia());
            }
            var messages = buildMessages(assembledContext, state);
            var toolCallbacks = agentToolProvider.getToolCallbacks(state, streamId);

            log.debug("ReAct 迭代开始: traceId={}, iteration={}, stepCount={}, toolCount={}",
                    state.traceId(), iteration, state.stepCount(), toolCallbacks.size());

            // 5. 调用 LLM（不自动执行 tool call）
            // 构造有效请求：将当前迭代的媒体内容传递给 callLlm
            var effectiveRequest = assembledContext.mediaContents() != null && !assembledContext.mediaContents().isEmpty()
                    ? new AgentRequest(request.message(), request.sessionId(), request.channel(),
                        request.systemPrompt(), request.budget(), request.parentTraceId(),
                        request.depth(), request.preferredProvider(), request.allowedToolIds(),
                        assembledContext.mediaContents())
                    : request;
            var iterationStart = Instant.now();
            ChatResponse chatResponse;
            try {
                chatResponse = callback.callLlm(effectiveRequest, messages, toolCallbacks, traceContext);
            } catch (Exception e) {
                log.error("LLM 调用异常: traceId={}, iteration={}, error={}",
                        state.traceId(), iteration, e.getMessage());
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, "连续 LLM 调用失败达到上限: " + maxConsecutiveFailures);
                    break;
                }
                state = state.appendStep(new ReactStep.Observation(
                        "llm", false, "LLM 调用失败: " + e.getMessage(), 0));
                continue;
            }
            var iterationDuration = Duration.between(iterationStart, Instant.now());

            // 清除 pendingMedia 缓冲区（已嵌入到本次 messages 中，避免后续迭代重复嵌入）
            if (state.pendingMedia() != null && !state.pendingMedia().isEmpty()) {
                state = state.clearPendingMedia();
            }

            // 6. 解析 LLM 响应
            var assistantMessage = chatResponse.getResult().getOutput();
            int responseTokens = estimateTokens(chatResponse);
            String providerId = callback.getProviderId();
            String modelId = callback.getModelId();

            // 记录 LLM 调用到 Trace（从 ChatResponse 提取真实 Token 用量和完成原因）
            recordLlmStep(traceContext, state.stepCount(), iterationStart,
                    iterationDuration, chatResponse, providerId, modelId);

            // 7. 判断是否有 tool call 请求
            if (assistantMessage.hasToolCalls()) {
                // === ReAct: Action 阶段 — 处理 tool call ===
                var toolCalls = assistantMessage.getToolCalls();

                // 如果 LLM 同时返回了文本（思考内容），记录为 Thought
                String thoughtText = assistantMessage.getText();
                if (thoughtText != null && !thoughtText.isBlank()) {
                    state = state.appendStep(new ReactStep.Thought(thoughtText));
                }

                // 逐个执行 tool call
                for (var tc : toolCalls) {
                    state = executeToolCall(state, tc, toolCallbacks, traceContext,
                            cancellationToken, sseManager, streamId);
                    if (cancellationToken.isCancelled()) break;
                }

                // 扣减 Token 预算
                state = state.toBuilder()
                        .budget(state.budget().deductTokens(responseTokens))
                        .build();
                consecutiveFailures = 0;

            } else {
                // === ReAct: Answer 阶段 — 纯文本响应 ===
                String content = assistantMessage.getText();
                if (content != null && !content.isBlank()) {
                    state = state.appendStep(new ReactStep.Answer(content));
                    state = state.toBuilder()
                            .done(true)
                            .finalOutput(content)
                            .budget(state.budget().deductTokens(responseTokens))
                            .build();
                    consecutiveFailures = 0;

                    log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}",
                            state.traceId(), iteration + 1, state.stepCount(),
                            state.budget().tokensUsed());
                } else {
                    // LLM 返回空内容
                    log.warn("LLM 返回空内容: traceId={}, iteration={}", state.traceId(), iteration);
                    consecutiveFailures++;
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        state = DegradedResponseBuilder.terminateWithReason(
                                state, "连续空响应达到上限: " + maxConsecutiveFailures);
                        break;
                    }
                }
            }

            // 8. 更新 Budget 已用时长
            state = state.toBuilder()
                    .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                    .build();
        }

        return state;
    }

    /**
     * 手动执行单个 tool call，记录 ToolCall + Observation 步骤。
     *
     * <p>流程：
     * <ol>
     *   <li>在 toolCallbacks 中查找匹配的 ToolCallback</li>
     *   <li>记录 ToolCall 步骤</li>
     *   <li>调用 ToolCallback.call(arguments)</li>
     *   <li>媒体数据提取（如有）</li>
     *   <li>记录 Observation 步骤</li>
     *   <li>记录 ToolCallStep 到 Trace</li>
     * </ol>
     *
     * @param state             当前状态
     * @param tc                LLM 请求的 tool call
     * @param toolCallbacks     可用工具回调列表
     * @param traceContext      追踪上下文
     * @param cancellationToken 取消信号
     * @param sseManager        SSE 管理器（流式模式下非 null，用于发送 MEDIA 事件）
     * @param streamId          SSE 流 ID（流式模式下非 null）
     * @return 更新后的状态
     */
    private ReactAgentState executeToolCall(
            ReactAgentState state,
            AssistantMessage.ToolCall tc,
            List<ToolCallback> toolCallbacks,
            @Nullable TraceContext traceContext,
            CancellationToken cancellationToken,
            @Nullable SseSessionManager sseManager,
            @Nullable String streamId) {

        // 取消信号检查 — 避免在已取消的情况下继续执行工具
        if (cancellationToken.isCancelled()) {
            log.info("工具执行前检测到取消信号: toolId={}", tc.name());
            return state;
        }

        String toolId = tc.name();
        String inputJson = tc.arguments();

        // 记录 ToolCall 步骤
        var toolCallStart = Instant.now();
        state = state.appendStep(new ReactStep.ToolCall(toolId, inputJson, 0));

        // 查找匹配的 ToolCallback
        ToolCallback matchedCallback = toolCallbacks.stream()
                .filter(Objects::nonNull)
                .filter(cb -> cb.getToolDefinition().name().equals(toolId))
                .findFirst()
                .orElse(null);

        if (matchedCallback == null) {
            log.warn("未找到工具回调: toolId={}", toolId);
            state = state.appendStep(new ReactStep.Observation(
                    toolId, false, "工具未注册: " + toolId, 0));
            recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                    toolId, inputJson, "工具未注册: " + toolId, false);
            return state;
        }

        // 执行工具
        String rawOutput;
        boolean success;
        try {
            rawOutput = matchedCallback.call(inputJson);
            success = true;
        } catch (Exception e) {
            log.warn("工具执行失败: toolId={}, error={}", toolId, e.getMessage());
            rawOutput = "工具执行异常: " + e.getMessage();
            success = false;
        }
        var toolCallDuration = Duration.between(toolCallStart, Instant.now());

        // 媒体数据提取
        String observationOutput = rawOutput;
        if (success && mediaDataExtractor != null) {
            var extraction = mediaDataExtractor.extract(toolId, rawOutput);
            observationOutput = extraction.sanitizedOutput();

            // 流式模式下发送 MEDIA 事件（字段名对齐前端 SseMediaEvent 类型定义）
            if (sseManager != null && streamId != null && !extraction.mediaItems().isEmpty()) {
                for (var mediaItem : extraction.mediaItems()) {
                    var mediaData = new HashMap<String, Object>();
                    mediaData.put("toolId", toolId);
                    mediaData.put("mimeType", mediaItem.mediaType());
                    mediaData.put("encoding", mediaItem.encoding());
                    mediaData.put("data", mediaItem.data());
                    mediaData.put("field", mediaItem.fieldName());
                    mediaData.put("metadata", mediaItem.metadata());
                    sseManager.sendEvent(streamId, SseEventType.MEDIA, mediaData);
                }
            }

            // 将图片类型的媒体写入 pendingMedia 缓冲区，供下一次迭代 LLM 视觉分析
            if (!extraction.mediaItems().isEmpty()) {
                // 收集到实例级列表，用于流式完成后持久化到附件表
                collectedToolMedia.addAll(extraction.mediaItems());

                for (var mediaItem : extraction.mediaItems()) {
                    if (mediaItem.mediaType().startsWith("image/")) {
                        try {
                            byte[] decoded = Base64.getDecoder().decode(mediaItem.data());
                            var mediaContent = new MediaContent(
                                    UUID.randomUUID().toString(),
                                    mediaItem.mediaType(),
                                    decoded,
                                    toolId + "_" + mediaItem.fieldName(),
                                    decoded.length,
                                    Map.of("toolId", toolId, "fieldName", mediaItem.fieldName())
                            );
                            state = state.appendPendingMedia(mediaContent);
                        } catch (IllegalArgumentException e) {
                            log.warn("Base64 解码失败，跳过媒体数据: toolId={}, field={}",
                                    toolId, mediaItem.fieldName());
                        }
                    }
                }
            }
        }

        // 记录 Observation 步骤
        int obsTokens = estimateTextTokens(observationOutput != null ? observationOutput : "");
        state = state.appendStep(new ReactStep.Observation(
                toolId, success, observationOutput != null ? observationOutput : "", obsTokens));

        // 记录 ToolCallStep 到 Trace
        recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                toolId, inputJson, rawOutput, success);

        log.debug("工具执行完成: toolId={}, success={}, latencyMs={}",
                toolId, success, toolCallDuration.toMillis());

        return state;
    }

    // ===== 辅助方法 =====

    /** 从 ChatResponse 估算 Token 消耗。 */
    private int estimateTokens(ChatResponse chatResponse) {
        if (chatResponse == null) return 0;
        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) return 0;
        return (int) (usage.getPromptTokens() + usage.getCompletionTokens());
    }

    /**
     * 记录 LLM 调用步骤到 Trace。
     *
     * <p>从 ChatResponse 元数据中提取真实的 Token 用量和完成原因，
     * 避免使用硬编码估算值。</p>
     *
     * @param traceContext 追踪上下文
     * @param stepIndex    步骤序号
     * @param timestamp    调用开始时间
     * @param duration     调用耗时
     * @param chatResponse LLM 原始响应（用于提取 Token 用量和完成原因）
     * @param providerId   LLM 提供商 ID
     * @param modelId      模型 ID
     */
    private void recordLlmStep(@Nullable TraceContext traceContext, int stepIndex,
                               Instant timestamp, Duration duration,
                               ChatResponse chatResponse,
                               String providerId, String modelId) {
        if (traceContext == null) return;
        try {
            // 从 ChatResponse 元数据提取真实 Token 用量
            int inputTokens = 0;
            int outputTokens = 0;
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                inputTokens = (int) usage.getPromptTokens();
                outputTokens = (int) usage.getCompletionTokens();
            }

            // 从 Generation 元数据提取完成原因
            var resultMetadata = chatResponse.getResult().getMetadata();
            String finishReason = resultMetadata.getFinishReason();

            var step = new LlmCallStep(
                    stepIndex, timestamp, duration,
                    providerId, modelId,
                    config.getLoop().getLlmScene(),
                    inputTokens, outputTokens,
                    duration,
                    false,  // cacheHit — Spring AI ChatResponse 不提供此信息
                    0.0,    // temperature — ChatResponse 不包含请求侧参数
                    finishReason);
            traceRecorder.recordStep(traceContext, step);
        } catch (Exception e) {
            log.debug("Trace LLM 步骤记录失败: error={}", e.getMessage());
        }
    }

    /** 记录工具调用步骤到 Trace。 */
    private void recordToolCallStep(@Nullable TraceContext traceContext, int stepIndex,
                                    Instant startTime, String toolId, String inputJson,
                                    @Nullable String outputJson, boolean success) {
        if (traceContext == null || traceRecorder == null) return;
        try {
            var duration = Duration.between(startTime, Instant.now());
            var step = new ToolCallStep(
                    stepIndex, Instant.now(), duration,
                    toolId, "execute", inputJson,
                    outputJson != null ? outputJson : "",
                    success, success ? null : outputJson,
                    RiskLevel.LOW);
            traceRecorder.recordStep(traceContext, step);
        } catch (Exception e) {
            log.debug("Trace 工具步骤记录失败: error={}", e.getMessage());
        }
    }

    /** 估算文本 Token 数（中文按 1 字 1 Token，英文按 4 字符 1 Token）。 */
    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    /**
     * 将 LlmResponse 适配为 Spring AI ChatResponse。
     *
     * <p>MultimodalRouter 返回 LlmResponse（content + token 用量），
     * 而 coreLoop 需要 ChatResponse（含 AssistantMessage），此方法完成适配。</p>
     *
     * @param llmResponse 多模态路由返回的 LLM 响应
     * @return 适配后的 ChatResponse
     */
    private ChatResponse adaptToChatResponse(LlmResponse llmResponse) {
        var assistantMessage = new AssistantMessage(llmResponse.content());
        var generation = new Generation(assistantMessage);
        return new ChatResponse(List.of(generation));
    }

    /**
     * 从消息列表中提取第一个 UserMessage 的文本内容。
     *
     * <p>用于构造 MultimodalRequest 时提取用户提示词文本。</p>
     *
     * @param messages Spring AI 消息列表
     * @return 用户消息文本，未找到时返回空字符串
     */
    private String extractUserText(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .findFirst()
                .orElse("");
    }

    /**
     * 将完整消息列表序列化为文本，供多模态路由使用。
     *
     * <p>多模态路由最终调用 adapter.callWithMedia(text, images)，只接受单个 text 参数。
     * 当存在工具调用历史时，仅传递用户原始文本会导致 LLM 丢失上下文。
     * 此方法将 SystemMessage、UserMessage（纯文本部分）、AssistantMessage（含 tool call）
     * 和 ToolResponseMessage 序列化为结构化文本，确保视觉模型拥有完整推理上下文。</p>
     *
     * @param messages Spring AI 消息列表
     * @return 包含完整对话上下文的文本
     */
    private String buildConversationContextText(List<Message> messages) {
        // 如果没有工具调用历史，直接返回用户文本即可
        boolean hasToolHistory = messages.stream().anyMatch(m -> m instanceof ToolResponseMessage);
        if (!hasToolHistory) {
            return extractUserText(messages);
        }

        var sb = new StringBuilder();
        for (var msg : messages) {
            switch (msg) {
                case SystemMessage sm -> {
                    sb.append("[系统指令]\n").append(sm.getText()).append("\n\n");
                }
                case UserMessage um -> {
                    sb.append("[用户消息]\n").append(um.getText()).append("\n\n");
                }
                case AssistantMessage am -> {
                    if (am.hasToolCalls()) {
                        for (var tc : am.getToolCalls()) {
                            sb.append("[工具调用] ").append(tc.name())
                                    .append("\n参数: ").append(tc.arguments()).append("\n\n");
                        }
                    }
                    String text = am.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append("[助手思考]\n").append(text).append("\n\n");
                    }
                }
                case ToolResponseMessage trm -> {
                    for (var resp : trm.getResponses()) {
                        sb.append("[工具结果] ").append(resp.name())
                                .append("\n").append(resp.responseData()).append("\n\n");
                    }
                }
                default -> { /* 忽略其他消息类型 */ }
            }
        }
        return sb.toString().strip();
    }

    // ===== 多模态辅助方法 =====

    /** 判断请求是否包含多模态内容。 */
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }

    /**
     * 从消息列表中提取 Media 对象并转换为 MediaContent 列表。
     *
     * <p>用于工具产生的 pendingMedia 场景：媒体已嵌入到 UserMessage 的 Media 中，
     * 需要提取出来构造 MultimodalRequest。</p>
     */
    private List<MediaContent> extractMediaContentsFromMessages(List<Message> messages) {
        var result = new ArrayList<MediaContent>();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                for (var media : um.getMedia()) {
                    try {
                        // 优先 ByteArrayResource 快速路径，兼容 Spring AI 可能的 Resource 包装
                        var rawData = media.getData();
                        byte[] data;
                        if (rawData instanceof ByteArrayResource bar) {
                            data = bar.getByteArray();
                        } else if (rawData instanceof Resource res) {
                            data = res.getInputStream().readAllBytes();
                        } else if (rawData instanceof byte[] bytes) {
                            data = bytes;
                        } else {
                            log.warn("不支持的 Media 数据类型: {}", rawData.getClass().getName());
                            continue;
                        }
                        result.add(new MediaContent(
                                UUID.randomUUID().toString(),
                                media.getMimeType().toString(),
                                data,
                                null,
                                data.length,
                                Map.of()
                        ));
                    } catch (Exception e) {
                        log.warn("从 UserMessage Media 提取数据失败: {}", e.getMessage());
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 媒体校验与预处理 — 在进入 coreLoop 之前执行。
     *
     * <p>校验通过后对图片执行预处理（压缩、格式转换），返回处理后的媒体内容列表。
     * 校验失败时抛出 {@link MediaValidationException}，由调用方捕获并返回错误响应。</p>
     *
     * @param mediaContents 原始媒体内容列表
     * @return 预处理后的媒体内容列表
     * @throws MediaValidationException 校验失败时抛出
     */
    private List<MediaContent> validateAndPreprocessMedia(List<MediaContent> mediaContents) {
        // 校验所有媒体内容
        mediaValidator.validateAll(mediaContents);

        // 提取图片类型执行预处理
        List<MediaContent> images = mediaContents.stream()
                .filter(mc -> mc.mimeType().startsWith("image/"))
                .toList();
        List<MediaContent> processedImages = mediaProcessor.processAll(images);

        // 合并非图片媒体（视频等由 MultimodalRouter 内部处理）
        List<MediaContent> nonImages = mediaContents.stream()
                .filter(mc -> !mc.mimeType().startsWith("image/"))
                .toList();
        var result = new ArrayList<MediaContent>(processedImages.size() + nonImages.size());
        result.addAll(processedImages);
        result.addAll(nonImages);
        return List.copyOf(result);
    }

    // ===== 同步执行入口 =====

    /**
     * 同步执行 ReAct 循环。
     *
     * <p>完整流程：初始化状态 → L1 写入用户消息 → 持久化用户消息 →
     * 启动 Trace → coreLoop → L1 写入助手响应 → 持久化助手消息 →
     * 异步后处理 → 构建 AgentResponse。</p>
     */
    public AgentResponse run(AgentRequest request) {
        ReactAgentState state = ReactAgentState.init(request);
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;

        var token = new CancellationToken();
        this.cancellationToken = token;

        // 媒体校验与预处理 — 在进入 coreLoop 之前执行
        List<MediaContent> processedMedia = null;
        if (hasMultimodalContent(request)) {
            if (multimodalRouter == null) {
                log.warn("请求包含媒体内容但 MultimodalRouter 未注入，降级为纯文本: sessionId={}",
                        request.sessionId());
            } else if (mediaValidator != null && mediaProcessor != null) {
                try {
                    processedMedia = validateAndPreprocessMedia(request.mediaContents());
                } catch (MediaValidationException e) {
                    log.warn("媒体校验失败: sessionId={}, error={}", request.sessionId(), e.getMessage());
                    return AgentResponse.error(state, e);
                }
            }
        }

        // 如果有预处理后的媒体，替换请求中的 mediaContents
        final AgentRequest effectiveRequest = processedMedia != null
                ? new AgentRequest(request.message(), request.sessionId(), request.channel(),
                        request.systemPrompt(), request.budget(), request.parentTraceId(),
                        request.depth(), request.preferredProvider(), request.allowedToolIds(),
                        processedMedia)
                : request;

        try {
            state = initState(effectiveRequest);
            collectedToolMedia.clear();
            writeUserMessageToL1(state, effectiveRequest.mediaContents());
            persistUserMessage(state);
            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();

            // 核心循环 — 非流式回调
            var callback = new NonStreamingCallback(effectiveRequest);
            state = coreLoop(state, effectiveRequest, traceContext, loopStart, callback, token,
                    null, null);

            // 构建推理概要
            if (state.terminationReason() == null) {
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder().reasoningSummary(summary).build();
            }

            writeAssistantMessageToL1(state);
            String assistantMessageId = persistAssistantMessage(state);
            persistInjectionRecord(assistantMessageId, state.sessionId());
            persistToolMediaAttachments(assistantMessageId, state.sessionId());
            asyncPostProcess(state);

            // 聚合 Token 使用量
            TokenUsage tokenUsage = aggregateTokenUsage(traceContext);

            // 提取 A2UI 组件
            var cachedTree = lastCollectedA2uiTree;
            var a2uiComponents = cachedTree != null
                    ? cachedTree.components() : null;

            return new AgentResponse(
                    state.traceId(),
                    state.sessionId(),
                    state.finalOutput() != null ? state.finalOutput() : "",
                    state.budget().tokensUsed(),
                    state.stepCount(),
                    state.terminationReason(),
                    assistantMessageId,
                    a2uiComponents,
                    tokenUsage
            );

        } catch (Exception e) {
            log.error("ReAct 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            return AgentResponse.error(state, e);
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
     * <p>完整流程与 run() 一致，但通过 SSE 推送 TOKEN / REASONING / MEDIA / DONE 事件。
     * 流式模式下同样支持 tool calling — LLM 返回 tool call 时暂停流式输出，
     * 执行工具后将结果回传 LLM 继续生成。</p>
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

        // 媒体校验与预处理 — 在进入 coreLoop 之前执行
        List<MediaContent> processedMedia = null;
        if (hasMultimodalContent(request)) {
            if (multimodalRouter == null) {
                log.warn("请求包含媒体内容但 MultimodalRouter 未注入，降级为纯文本: sessionId={}",
                        request.sessionId());
            } else if (mediaValidator != null && mediaProcessor != null) {
                try {
                    processedMedia = validateAndPreprocessMedia(request.mediaContents());
                } catch (MediaValidationException e) {
                    log.warn("媒体校验失败: sessionId={}, error={}", request.sessionId(), e.getMessage());
                    sendStreamError(sseManager, streamId, 400,
                            "媒体校验失败: " + e.getMessage(), state.traceId());
                    return;
                }
            }
        }

        // 如果有预处理后的媒体，替换请求中的 mediaContents
        final AgentRequest effectiveRequest = processedMedia != null
                ? new AgentRequest(request.message(), request.sessionId(), request.channel(),
                        request.systemPrompt(), request.budget(), request.parentTraceId(),
                        request.depth(), request.preferredProvider(), request.allowedToolIds(),
                        processedMedia)
                : request;

        try {
            state = initState(effectiveRequest);
            collectedToolMedia.clear();
            writeUserMessageToL1(state, effectiveRequest.mediaContents());

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

            sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                    "AGENT_START", "开始处理请求",
                    "Agent 已接收到用户请求，正在准备上下文与预算。",
                    null, Map.of());

            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();

            // 核心循环 — 流式回调
            var callback = new StreamingCallback(
                    sseManager, streamId, request.sessionId(), tempTurnId, effectiveRequest);
            state = coreLoop(state, effectiveRequest, traceContext, loopStart, callback, token,
                    sseManager, streamId);

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
                if (finalA2uiTree != null) {
                    lastCollectedA2uiTree = finalA2uiTree;
                }

                if (state.terminationReason() == null) {
                    reasoningSummary = buildReasoningSummary(state, traceContext);
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .reasoningSummary(reasoningSummary)
                            .build();
                }

                writeAssistantMessageToL1(state);

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

                persistInjectionRecord(assistantMessageId, state.sessionId());

                // 持久化工具产生的媒体附件（截图等通过 SSE MEDIA 事件发送的数据）
                persistToolMediaAttachments(assistantMessageId, state.sessionId());

                // 持久化用户上传的媒体附件到 message_attachments 表
                if (attachmentRepository != null && assistantMessageId != null
                        && effectiveRequest.mediaContents() != null && !effectiveRequest.mediaContents().isEmpty()) {
                    for (var mc : effectiveRequest.mediaContents()) {
                        try {
                            String fileName = mc.fileName() != null ? mc.fileName()
                                    : "media-" + UUID.randomUUID().toString().substring(0, 8) + "." + guessExtension(mc.mimeType());
                            String dataUri = "data:" + mc.mimeType() + ";base64," + java.util.Base64.getEncoder().encodeToString(mc.data());
                            attachmentRepository.save(assistantMessageId, state.sessionId(),
                                    fileName, "", mc.sizeBytes(), mc.mimeType(), dataUri);
                        } catch (Exception e) {
                            log.warn("媒体附件持久化失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
                        }
                    }
                    log.debug("媒体附件持久化完成: sessionId={}, count={}", state.sessionId(), effectiveRequest.mediaContents().size());
                }

                asyncPostProcess(state);
                finalTokenUsage = aggregateTokenUsage(traceContext);
            }
        } catch (Exception e) {
            log.error("流式 Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
        } finally {
            if (traceRecorder != null && traceContext != null) {
                String finalOutput = state.finalOutput();
                boolean success = error == null && state.terminationReason() == null;
                String errorMessage = error != null ? error.getMessage() : null;
                String terminationReason = error != null
                        ? error.getClass().getSimpleName()
                        : state.terminationReason();
                traceRecorder.endTrace(traceContext, finalOutput, success, errorMessage, terminationReason);
            }

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
     * <p>通过 {@code ChatModel.call(Prompt)} 直接调用 LLM，
     * 设置 {@code internalToolExecutionEnabled=false} 禁用自动 tool calling，
     * 返回原始 ChatResponse 供 coreLoop 解析 tool call 并手动执行。</p>
     */
    private class NonStreamingCallback implements IterationCallback {
        private final AgentRequest request;
        private String providerId = DEFAULT_MODEL_ID;
        private String modelId = DEFAULT_MODEL_ID;

        NonStreamingCallback(AgentRequest request) {
            this.request = request;
        }

        @Override
        public ChatResponse callLlm(AgentRequest req,
                                    List<Message> messages,
                                    List<ToolCallback> toolCallbacks,
                                    @Nullable TraceContext traceContext) {
            String scene = config.getLoop().getLlmScene();

            // 动态路由：检查 messages 中 UserMessage 是否包含 Media 对象
            // 覆盖两种场景：用户上传的媒体（首轮）和工具产生的媒体（后续迭代 pendingMedia）
            boolean messagesHaveMedia = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> (UserMessage) m)
                    .anyMatch(um -> !um.getMedia().isEmpty());

            // 多模态路由：messages 中有 Media 且 MultimodalRouter 可用时走多模态路径
            if (messagesHaveMedia && multimodalRouter != null) {
                // 从 messages 中提取 MediaContent 列表（优先用 request 的，否则从 assembledContext 传递的）
                var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                        ? req.mediaContents()
                        : extractMediaContentsFromMessages(messages);
                var multimodalRequest = new MultimodalRequest(
                        scene,
                        buildConversationContextText(messages),
                        mediaContents,
                        null,
                        req.preferredProvider(),
                        null
                );
                LlmResponse llmResponse = multimodalRouter.call(multimodalRequest);
                this.providerId = llmResponse.providerId();
                this.modelId = llmResponse.modelName();
                log.debug("非流式多模态路由完成: scene={}, provider={}, model={}",
                        scene, this.providerId, this.modelId);
                return adaptToChatResponse(llmResponse);
            }

            if (messagesHaveMedia) {
                log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
            }

            // 纯文本路由：原有 LlmRouter 路径
            var chatModelInfo = llmRouter.getChatModelWithInfo(scene, request.preferredProvider());
            this.providerId = chatModelInfo.providerId();
            this.modelId = chatModelInfo.modelId();

            // 构建 ChatOptions：注入工具定义但禁用自动执行
            var optionsBuilder = DefaultToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false);

            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                var validCallbacks = toolCallbacks.stream()
                        .filter(Objects::nonNull)
                        .toList();
                if (!validCallbacks.isEmpty()) {
                    optionsBuilder.toolCallbacks(validCallbacks);
                }
            }

            // 构建 Prompt 并调用 ChatModel
            var prompt = new Prompt(messages, optionsBuilder.build());
            return chatModelInfo.chatModel().call(prompt);
        }

        @Override public String getProviderId() { return providerId; }
        @Override public String getModelId() { return modelId; }
    }

    // ===== 流式 LLM 回调 =====

    /**
     * 流式迭代回调 — runStreaming() 使用。
     *
     * <p>通过 ChatModel.stream(Prompt) 流式调用 LLM，逐 token 发送 SSE TOKEN 事件。
     * 当 LLM 返回 tool call 请求时，收集完整响应后构造含 tool call 的 ChatResponse
     * 供 coreLoop 手动执行工具。</p>
     */
    private class StreamingCallback implements IterationCallback {

        private final SseSessionManager sseManager;
        private final String streamId;
        private final String sessionId;
        private final String turnId;
        private final AgentRequest request;

        private String providerId = DEFAULT_MODEL_ID;
        private String modelId = DEFAULT_MODEL_ID;
        @Nullable private Exception streamingError;
        @Nullable private String finalContent;

        StreamingCallback(SseSessionManager sseManager, String streamId,
                          String sessionId, String turnId, AgentRequest request) {
            this.sseManager = sseManager;
            this.streamId = streamId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.request = request;
        }

        @Override
        public ChatResponse callLlm(AgentRequest req,
                                    List<Message> messages,
                                    List<ToolCallback> toolCallbacks,
                                    @Nullable TraceContext traceContext) {
            // 推理事件
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "CONTEXT_LOADING", "分析问题与上下文",
                    "正在梳理本轮问题、会话历史与可用记忆。", null, Map.of());
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "MEMORY_RETRIEVAL", "检索相关记忆",
                    "已基于最近对话与知识收集相关记忆，用于本轮推理。", null, Map.of());
            sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                    "ANSWER_DRAFTING", "正在生成回答",
                    "模型正在根据上下文整理最终回答。", null, Map.of());

            String scene = config.getLoop().getLlmScene();

            // 动态路由：检查 messages 中 UserMessage 是否包含 Media 对象
            boolean messagesHaveMedia = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> (UserMessage) m)
                    .anyMatch(um -> um.getMedia() != null && !um.getMedia().isEmpty());

            // 多模态流式路由：messages 中有 Media 且 MultimodalRouter 可用时走多模态路径
            if (messagesHaveMedia && multimodalRouter != null) {
                var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                        ? req.mediaContents()
                        : extractMediaContentsFromMessages(messages);
                var multimodalRequest = new MultimodalRequest(
                        scene,
                        buildConversationContextText(messages),
                        mediaContents,
                        null,
                        req.preferredProvider(),
                        null
                );
                StreamingLlmResponse streamingResponse = multimodalRouter.streamWithInfo(multimodalRequest);
                this.providerId = streamingResponse.providerId();
                this.modelId = streamingResponse.modelId();
                log.debug("流式多模态路由开始: scene={}, provider={}, model={}",
                        scene, this.providerId, this.modelId);

                // 收集流式内容并逐 token 推送 SSE
                var contentBuilder = new StringBuilder();
                streamingResponse.stream()
                        .doOnNext(token -> {
                            contentBuilder.append(token);
                            // 逐 token 推送 SSE TOKEN 事件
                            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                    "sessionId", sessionId, "turnId", turnId,
                                    "content", token, "index", 0));
                        })
                        .doOnError(e -> {
                            log.warn("流式多模态调用异常: scene={}, error={}", scene, e.getMessage());
                            this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e);
                        })
                        .blockLast();

                String collectedContent = contentBuilder.toString();
                this.finalContent = collectedContent;

                // 构造 ChatResponse 返回给 coreLoop
                ChatResponse chatResponse = adaptToChatResponse(
                        new LlmResponse(collectedContent, 0, 0, this.providerId, this.modelId, 0, false));
                recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                        scene, chatResponse, null);
                return chatResponse;
            }

            // 纯文本流式路由：原有 LlmRouter 路径
            if (messagesHaveMedia && multimodalRouter == null) {
                log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
            }
            String preferredProviderId = request.preferredProvider();

            // 提取 system 文本，通过 ContextAssembler 集中增强（流式约束 + A2UI）
            String systemText = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).getText())
                    .findFirst().orElse("");

            String a2uiPrompt = isA2uiEnabled()
                    ? A2uiComponentCatalog.renderPrompt(a2uiProperties.maxComponentsPerTree())
                    : null;
            String streamingSystemPrompt = contextAssembler.enhanceSystemPromptForStreaming(
                    systemText, a2uiPrompt);

            // 先尝试用 ChatModel 做一次非流式调用检测 tool call
            // 如果 LLM 要调用工具，直接返回含 tool call 的 ChatResponse（不流式输出）
            // 如果 LLM 返回纯文本，则走流式路径逐 token 推送
            var chatModelInfo = llmRouter.getChatModelWithInfo(scene, preferredProviderId);
            this.providerId = chatModelInfo.providerId();
            this.modelId = chatModelInfo.modelId();

            // 构建带工具定义但禁用自动执行的 ChatOptions
            var optionsBuilder = DefaultToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false);
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                var validCallbacks = toolCallbacks.stream()
                        .filter(Objects::nonNull)
                        .toList();
                if (!validCallbacks.isEmpty()) {
                    optionsBuilder.toolCallbacks(validCallbacks);
                }
            }

            // 替换 system message 为增强版
            var enhancedMessages = new ArrayList<>(messages);
            if (!enhancedMessages.isEmpty() && enhancedMessages.getFirst() instanceof SystemMessage) {
                enhancedMessages.set(0, new SystemMessage(
                        streamingSystemPrompt != null ? streamingSystemPrompt : systemText));
            }

            // 调试日志 — 记录最终发送给 LLM 的完整消息列表
            logLlmPromptIfEnabled(scene, enhancedMessages, toolCallbacks);

            var prompt = new Prompt(enhancedMessages, optionsBuilder.build());

            // 非流式调用获取完整响应
            ChatResponse chatResponse = chatModelInfo.chatModel().call(prompt);
            var assistantMsg = chatResponse.getResult().getOutput();

            if (assistantMsg.hasToolCalls()) {
                // LLM 要调用工具 — 发送 TOOL_CALLING 推理事件，不流式输出
                for (var tc : assistantMsg.getToolCalls()) {
                    sendReasoningEvent(sseManager, streamId, sessionId, turnId,
                            "TOOL_CALLING", "调用工具: " + tc.name(),
                            "正在执行工具 " + tc.name(), tc.name(), Map.of());
                }
                // 记录流式 LLM Step
                recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                        scene, chatResponse, null);
                return chatResponse;
            }

            // LLM 返回纯文本 — 流式推送 token
            String content = assistantMsg.getText();
            this.finalContent = content;

            // 逐字符模拟流式推送（实际内容已完整获取）
            if (content != null && !content.isBlank()) {
                streamContentToSse(content);
            }

            // 记录流式 LLM Step
            recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                    scene, chatResponse, null);

            return chatResponse;
        }

        /** 将文本内容逐段推送为 SSE TOKEN 事件。 */
        private void streamContentToSse(String content) {
            // A2UI 流式解析
            boolean a2uiEnabled = isA2uiEnabled();
            StreamingA2uiParser a2uiParser = a2uiEnabled ? new StreamingA2uiParser() : null;
            int maxComponents = a2uiEnabled ? a2uiProperties.maxComponentsPerTree() : 0;
            int tokenIndex = 0;

            if (a2uiParser != null) {
                var segments = a2uiParser.feed(content);
                for (var segment : segments) {
                    switch (segment) {
                        case StreamingA2uiParser.Segment.TextSegment(var text) -> {
                            if (!text.isEmpty()) {
                                sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                        "sessionId", sessionId, "turnId", turnId,
                                        "content", text, "index", tokenIndex++));
                            }
                        }
                        case StreamingA2uiParser.Segment.A2uiSegment(var json) -> {
                            var tree = parseAndValidateA2uiTree(json, maxComponents);
                            if (tree != null) {
                                lastCollectedA2uiTree = tree;
                                sseManager.sendEvent(streamId, SseEventType.UI, Map.of(
                                        "sessionId", sessionId, "turnId", turnId,
                                        "components", tree.components()));
                            }
                        }
                    }
                }
                var remaining = a2uiParser.flush();
                for (var seg : remaining) {
                    if (seg instanceof StreamingA2uiParser.Segment.TextSegment(var text)
                            && !text.isEmpty()) {
                        sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                "sessionId", sessionId, "turnId", turnId,
                                "content", text, "index", tokenIndex++));
                    }
                }
            } else {
                // 无 A2UI — 直接推送完整文本
                sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                        "sessionId", sessionId, "turnId", turnId,
                        "content", content, "index", 0));
            }
        }

        boolean hasStreamingError() { return streamingError != null; }
        @Nullable Exception getStreamingError() { return streamingError; }
        @Nullable String getFinalContent() { return finalContent; }

        @Override public String getProviderId() { return providerId; }
        @Override public String getModelId() { return modelId; }
    }

    // ===== 状态初始化 =====

    /** 初始化 ReAct 状态 — 查找已有会话或创建新状态。 */
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

    /** 启动 Trace（如果 TraceRecorder 可用）。 */
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    // ===== L1 工作记忆读写 =====

    /**
     * 将用户消息写入 L1 工作记忆。
     *
     * <p>当请求包含媒体内容时，在消息末尾附加元信息标注（MIME 类型 + 文件名），
     * 不存储原始二进制数据到 WorkingMemory。</p>
     *
     * @param state           当前 Agent 状态
     * @param mediaContents   请求关联的媒体内容列表（可空）
     */
    private void writeUserMessageToL1(ReactAgentState state,
                                      @Nullable List<MediaContent> mediaContents) {
        if (workingMemory == null || state.goal() == null || state.goal().isBlank()) return;
        try {
            String content = state.goal();

            // 附加媒体元信息标注（不含二进制数据）
            if (mediaContents != null && !mediaContents.isEmpty()) {
                String annotation = mediaContents.stream()
                        .map(mc -> mc.mimeType() + ": " + (mc.fileName() != null ? mc.fileName() : "unnamed"))
                        .collect(Collectors.joining(", "));
                content = content + "\n[附件: " + annotation + "]";
            }

            int tokens = estimateTextTokens(content);
            var slot = com.lifepilot.memory.working.ConversationSlot.userMessage(content, tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("用户消息写入 L1 失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /** 将 AI 响应写入 L1 工作记忆。 */
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

    /** 当 L1 为空时，从 ConversationViewService 回灌对话历史。 */
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

    /** 同步写入用户消息到 chat_messages。 */
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

    /** 同步写入助手消息到 chat_messages。 */
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

    /** 持久化注入记录。 */
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

    /**
     * 持久化工具产生的媒体附件到 message_attachments 表。
     *
     * <p>将本轮 ReAct 循环中通过 SSE MEDIA 事件发送的媒体数据（截图等）
     * 写入附件表，确保页面刷新后仍能加载。</p>
     *
     * @param assistantMessageId 助手消息 ID
     * @param sessionId          会话 ID
     */
    private void persistToolMediaAttachments(@Nullable String assistantMessageId,
                                             @Nullable String sessionId) {
        if (attachmentRepository == null || assistantMessageId == null
                || collectedToolMedia.isEmpty()) {
            return;
        }
        for (var mediaItem : collectedToolMedia) {
            try {
                String ext = guessExtension(mediaItem.mediaType());
                String fileName = mediaItem.fieldName() + "." + ext;
                String dataUri = "data:" + mediaItem.mediaType() + ";base64," + mediaItem.data();
                long sizeBytes = Math.round(mediaItem.data().length() * 0.75);
                attachmentRepository.save(assistantMessageId, sessionId,
                        fileName, "", sizeBytes, mediaItem.mediaType(), dataUri);
            } catch (Exception e) {
                log.warn("工具媒体附件持久化失败: field={}, error={}",
                        mediaItem.fieldName(), e.getMessage());
            }
        }
        log.debug("工具媒体附件持久化完成: sessionId={}, count={}",
                sessionId, collectedToolMedia.size());
        collectedToolMedia.clear();
    }

    // ===== 异步后处理 =====

    /** 异步后处理 — 会话快照持久化 + AUDN 实体提取。 */
    private void asyncPostProcess(ReactAgentState finalState) {
        Thread.startVirtualThread(() -> {
            try {
                sessionManager.saveSession(finalState);
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

    /** 构建推理概要字符串（从 TraceContext 提取真实模型信息）。 */
    private String buildReasoningSummary(ReactAgentState state,
                                         @Nullable TraceContext traceContext) {
        int steps = state.stepCount();
        int tokens = state.budget() != null ? state.budget().tokensUsed() : 0;
        String modelId = DEFAULT_MODEL_ID;

        // 从 TraceContext 提取最后一次 LLM 调用的模型 ID
        if (traceContext != null) {
            var traceSteps = traceContext.steps();
            for (int i = traceSteps.size() - 1; i >= 0; i--) {
                if (traceSteps.get(i) instanceof LlmCallStep llmStep) {
                    modelId = llmStep.modelId();
                    tokens = traceContext.totalInputTokens() + traceContext.totalOutputTokens();
                    break;
                }
            }
        }

        return "本轮推理已完成，使用模型 %s，经历 %d 个推理步骤，累计约 %d 个 Token。"
                .formatted(modelId, steps, tokens);
    }

    // ===== Token 聚合 =====

    /** 从 TraceContext 聚合 Token 使用量。 */
    private TokenUsage aggregateTokenUsage(@Nullable TraceContext traceContext) {
        String modelId = DEFAULT_MODEL_ID;
        int promptTokens = 0;
        int completionTokens = 0;
        if (traceContext != null) {
            promptTokens = traceContext.totalInputTokens();
            completionTokens = traceContext.totalOutputTokens();
            var steps = traceContext.steps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                if (steps.get(i) instanceof LlmCallStep llmStep) {
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
     *
     * <p>从 ChatResponse 元数据提取真实 Token 用量和完成原因，
     * 与 {@link #recordLlmStep} 保持一致的数据提取逻辑。</p>
     */
    private void recordStreamingLlmStep(@Nullable TraceContext traceContext,
                                        Instant startTime, String providerId,
                                        String modelId, String scene,
                                        ChatResponse chatResponse,
                                        @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) return;
        Instant end = Instant.now();
        Duration d = Duration.between(startTime, end);

        // 从 ChatResponse 元数据提取真实 Token 用量
        int inputTokens = 0;
        int outputTokens = 0;
        if (chatResponse != null) {
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                inputTokens = (int) usage.getPromptTokens();
                outputTokens = error != null ? 0 : (int) usage.getCompletionTokens();
            }
        }

        // 从 Generation 元数据提取完成原因
        String finishReason;
        if (error != null) {
            finishReason = "error: " + error.getMessage();
        } else if (chatResponse != null) {
            var resultMetadata = chatResponse.getResult().getMetadata();
            finishReason = resultMetadata != null ? resultMetadata.getFinishReason() : null;
        } else {
            finishReason = null;
        }

        int stepIndex = traceContext.steps() != null ? traceContext.steps().size() : 0;
        var step = new LlmCallStep(
                stepIndex, end, d,
                providerId != null ? providerId : DEFAULT_MODEL_ID,
                modelId != null ? modelId : DEFAULT_MODEL_ID,
                scene != null ? scene : "unknown",
                inputTokens, outputTokens, d,
                false,  // cacheHit — Spring AI 不提供
                0.0,    // temperature — ChatResponse 不包含请求侧参数
                finishReason);
        traceRecorder.recordStep(traceContext, step);
    }

    // ===== SSE 推理事件 =====

    /** 发送 REASONING SSE 事件。 */
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

    /** 发送 SSE ERROR 事件并关闭连接。 */
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

    /** 构建 DONE 事件 payload。 */
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
        var cachedA2uiTree = lastCollectedA2uiTree;
        if (cachedA2uiTree != null && !cachedA2uiTree.components().isEmpty()) {
            doneData.put("a2uiComponents", cachedA2uiTree.components());
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

    /** 构建知识库来源摘要。 */
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

    // ===== A2UI 辅助方法 =====

    /** 判断 A2UI 功能是否启用。 */
    private boolean isA2uiEnabled() {
        return a2uiProperties != null && a2uiProperties.enabled();
    }

    /** 从非流式响应中提取 A2UI 内容。 */
    private A2uiPayloadSupport.ParsedA2uiContent extractA2uiContent(@Nullable String content) {
        if (!isA2uiEnabled()) {
            return new A2uiPayloadSupport.ParsedA2uiContent(content != null ? content : "", null);
        }
        return A2uiPayloadSupport.extractContent(content, objectMapper, a2uiProperties.maxComponentsPerTree());
    }

    /** 序列化 A2UI 组件树为 JSON。 */
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

    /** 解析并校验 A2UI JSON 为组件树。 */
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
     * 调试日志 — 打印发送给 LLM 的完整消息列表。
     *
     * <p>记录最终发送的所有 Message（包括 SystemMessage、UserMessage、
     * 历史 AssistantMessage/ToolResponseMessage），确保日志与实际请求一致。</p>
     */
    private void logLlmPromptIfEnabled(String scene, List<Message> messages,
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
        var sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            String type = msg.getClass().getSimpleName();
            String content = switch (msg) {
                case SystemMessage sm -> sm.getText();
                case UserMessage um -> um.getText();
                case AssistantMessage am -> {
                    String text = am.getText() != null ? am.getText() : "";
                    if (am.hasToolCalls()) {
                        text += " [tool_calls=" + am.getToolCalls().size() + "]";
                    }
                    yield text;
                }
                default -> msg.toString();
            };
            sb.append("  [").append(i).append("] ").append(type).append(": ")
                    .append(content != null ? content : "").append("\n");
        }
        log.info("""
                ========== LLM PROMPT ==========
                scene={} traceId=react
                tools={}
                messageCount={}
                -------- MESSAGES --------
                {}========= END PROMPT ==========""",
                scene, toolNames, messages.size(), sb);
    }

    // ===== 流式错误持久化 =====

    /** 根据 MIME 类型猜测文件扩展名。 */
    private static String guessExtension(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    /** 持久化流式系统错误消息到对话历史和 L1。 */
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
