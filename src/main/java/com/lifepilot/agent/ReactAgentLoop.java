package com.lifepilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.CallbackHelper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.event.ScheduledWakeupEvent;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
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
public class ReactAgentLoop implements CallbackHelper {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";

    // ===== 核心依赖 =====
    private final ContextAssembler contextAssembler;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;
    @Nullable private final TraceRecorder traceRecorder;
    @Nullable private final A2uiProperties a2uiProperties;

    // ===== 可选依赖（多模态） =====
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaDataExtractor mediaDataExtractor;

    // ===== 可选依赖（挂起-恢复） =====
    @Nullable private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final java.util.concurrent.ScheduledExecutorService suspendScheduler;

    // ===== 可选依赖（L4 反馈闭环） =====
    @Nullable private final ProceduralMemory proceduralMemory;
    @Nullable private final IntentMatcher intentMatcher;

    public ReactAgentLoop(
            ContextAssembler contextAssembler,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            @Nullable TraceRecorder traceRecorder,
            @Nullable A2uiProperties a2uiProperties,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaDataExtractor mediaDataExtractor,
            @Nullable org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Nullable ProceduralMemory proceduralMemory,
            @Nullable IntentMatcher intentMatcher,
            com.lifepilot.config.threadpool.SharedScheduler sharedScheduler) {
        this.contextAssembler = contextAssembler;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.a2uiProperties = a2uiProperties;
        this.multimodalRouter = multimodalRouter;
        this.mediaDataExtractor = mediaDataExtractor;
        this.eventPublisher = eventPublisher;
        this.proceduralMemory = proceduralMemory;
        this.intentMatcher = intentMatcher;
        this.suspendScheduler = sharedScheduler.cleanup();
    }

    /** 获取多模态路由器（预留：多模态流式请求）。 */
    @Nullable
    public MultimodalRouter getMultimodalRouter() {
        return multimodalRouter;
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
                case ReactStep.Suspend s ->
                        messages.add(new AssistantMessage(
                                "Agent 已挂起，等待恢复信号。挂起原因: " + formatSuspendReason(s.reason())));
                case ReactStep.Resume r -> {
                    // 恢复步骤转换为 ToolResponseMessage，使 LLM 能看到恢复载荷作为工具结果
                    var resumeToolId = "resume:" + r.payload().getClass().getSimpleName();
                    var toolResponse = ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    resumeToolId, resumeToolId,
                                    "Agent 已从挂起态恢复，挂起时长: " + r.suspendDuration()
                                            + "，恢复载荷: " + r.payload())))
                            .build();
                    messages.add(toolResponse);
                }
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
    public ReactAgentState coreLoop(
            ReactAgentState state,
            AgentRequest request,
            @Nullable TraceContext traceContext,
            Instant loopStart,
            IterationCallback callback,
            CancellationToken cancellationToken,
            AgentLoopContext loopContext) {

        int maxIterations = config.getLoop().getMaxIterations();
        int maxConsecutiveFailures = config.getLoop().getMaxConsecutiveFailures();
        int consecutiveFailures = 0;
        // 缓存首次组装的上下文 — 记忆检索结果和预算分配在迭代间不变
        AssembledContext cachedContext = null;

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

            // 3. 更新 Budget 已用时长 + 渐进式降级 + 超限检查
            state = state.toBuilder()
                    .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                    .build();

            // 渐进式降级：根据 Token 使用率逐步裁剪上下文
            var degradation = state.budget().degradationLevel();
            switch (degradation) {
                case COMPRESS_HISTORY, TRIM_TOOLS, SKIP_MEMORY -> {
                    // 清空缓存上下文，下一轮 assemble 时 ContextAssembler 会基于剩余预算自动裁剪
                    if (cachedContext != null) {
                        log.info("预算渐进式降级: traceId={}, level={}, tokenUtilization={}%",
                                state.traceId(), degradation,
                                (int) (state.budget().tokenUtilization() * 100));
                        cachedContext = null;
                    }
                }
                case TERMINATE -> {
                    log.warn("ReAct 循环预算超限: traceId={}, reason={}",
                            state.traceId(), state.budget().exceedReason());
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, state.budget().exceedReason());
                }
                default -> {}
            }
            if (state.budget().exceeded()) {
                break;
            }

            // 4. 组装上下文 + 构建消息 + 获取工具回调
            // 首轮组装完整上下文，后续迭代复用缓存（记忆检索和预算分配不变）
            if (cachedContext == null) {
                // 首轮迭代：推送上下文组装 Thought 步骤
                state = state.appendStep(new ReactStep.Thought("正在检索相关记忆和知识…"));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

                cachedContext = contextAssembler.assemble(state);
                // 传播经验注入 ID 到请求作用域上下文
                if (!cachedContext.injectedEntityIds().isEmpty()) {
                    loopContext.addInjectedEntityIds(cachedContext.injectedEntityIds());
                }
            }
            var assembledContext = cachedContext;
            // 首轮迭代注入用户上传的媒体内容到上下文
            if (state.steps().isEmpty() && hasMultimodalContent(request)) {
                assembledContext = assembledContext.withMediaContents(request.mediaContents());
            }
            // 非首轮迭代：有 pendingMedia 时注入工具产生的媒体
            else if (state.pendingMedia() != null && !state.pendingMedia().isEmpty()) {
                assembledContext = assembledContext.withMediaContents(state.pendingMedia());
            }
            var messages = buildMessages(assembledContext, state);
            var toolCallbacks = agentToolProvider.getToolCallbacks(state, loopContext.getStreamId());

            log.debug("ReAct 迭代开始: traceId={}, iteration={}, stepCount={}, toolCount={}",
                    state.traceId(), iteration, state.stepCount(), toolCallbacks.size());

            // 5. 调用 LLM（不自动执行 tool call）
            // 推送思考中 Thought 步骤
            state = state.appendStep(new ReactStep.Thought("正在思考回答…"));
            pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

            // 构造有效请求：将当前迭代的媒体内容传递给 callLlm
            var effectiveRequest = assembledContext.mediaContents() != null && !assembledContext.mediaContents().isEmpty()
                    ? new AgentRequest(request.message(), request.sessionId(), request.channel(),
                        request.systemPrompt(), request.budget(), request.parentTraceId(),
                        request.depth(), request.preferredProvider(), request.allowedToolIds(),
                        assembledContext.mediaContents(), request.temperature())
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
                        "llm", null, false, "LLM 调用失败: " + e.getMessage(), 0));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
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

            // 记录 LLM 调用到 Trace — 流式回调已在内部记录，跳过避免重复
            if (!callback.recordsLlmStep()) {
                recordLlmStep(traceContext, state.stepCount(), iterationStart,
                        iterationDuration, chatResponse, providerId, modelId);
            }

            // 7. 判断是否有 tool call 请求
            if (assistantMessage.hasToolCalls()) {
                // === ReAct: Action 阶段 — 处理 tool call ===
                var toolCalls = assistantMessage.getToolCalls();

                // 如果 LLM 同时返回了文本（思考内容），记录为 Thought
                String thoughtText = assistantMessage.getText();
                if (thoughtText != null && !thoughtText.isBlank()) {
                    state = state.appendStep(new ReactStep.Thought(thoughtText));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                }

                // 逐个执行 tool call
                for (var tc : toolCalls) {
                    state = executeToolCall(state, tc, toolCallbacks, traceContext,
                            cancellationToken, loopContext);

                    // ★ 通用挂起检测 — 仅检查 suspended 布尔标志，不引用具体工具名或 SuspendReason 子类型
                    if (state.suspended()) {
                        log.info("Agent 进入挂起态: traceId={}, reason={}", state.traceId(), state.suspendReason());
                        state = state.appendStep(new ReactStep.Suspend(
                                state.suspendReason(), Instant.now(), state.stepCount()));
                        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                        // 冻结 Budget elapsed 到当前时间点
                        state = state.toBuilder()
                                .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                                .build();
                        break;
                    }

                    if (cancellationToken.isCancelled()) break;
                }

                // ★ 外层循环挂起检测 — 挂起后跳出主迭代循环
                if (state.suspended()) break;

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
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
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
     * @return 更新后的状态
     */
    private ReactAgentState executeToolCall(
            ReactAgentState state,
            AssistantMessage.ToolCall tc,
            List<ToolCallback> toolCallbacks,
            @Nullable TraceContext traceContext,
            CancellationToken cancellationToken,
            AgentLoopContext loopContext) {

        // 取消信号检查 — 避免在已取消的情况下继续执行工具
        if (cancellationToken.isCancelled()) {
            log.info("工具执行前检测到取消信号: toolId={}", tc.name());
            return state;
        }

        String toolId = tc.name();
        String inputJson = tc.arguments();
        // 解析工具显示名称
        String toolDisplayName = agentToolProvider.resolveToolDisplayName(toolId);

        // 记录 ToolCall 步骤
        var toolCallStart = Instant.now();
        state = state.appendStep(new ReactStep.ToolCall(toolId, toolDisplayName, inputJson, 0));
        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

        // 查找匹配的 ToolCallback
        ToolCallback matchedCallback = toolCallbacks.stream()
                .filter(Objects::nonNull)
                .filter(cb -> cb.getToolDefinition().name().equals(toolId))
                .findFirst()
                .orElse(null);

        if (matchedCallback == null) {
            log.warn("未找到工具回调: toolId={}", toolId);
            state = state.appendStep(new ReactStep.Observation(
                    toolId, toolDisplayName, false, "工具未注册: " + toolId, 0));
            pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
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

        // ★ 挂起信号检测 — 工具执行成功后解析返回 JSON 中的 _suspend 标记
        if (success) {
            var suspendReason = parseSuspendReasonFromOutput(rawOutput);
            if (suspendReason != null) {
                log.info("工具请求挂起: toolId={}, reason={}", toolId, suspendReason);
                state = state.suspend(suspendReason);
                state = state.appendStep(new ReactStep.Observation(
                        toolId, toolDisplayName, true, "工具请求挂起: " + suspendReason, 0));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                        toolId, inputJson, rawOutput, true);
                return state;
            }
        }

        // 媒体数据提取
        String observationOutput = rawOutput;
        if (success && mediaDataExtractor != null) {
            var extraction = mediaDataExtractor.extract(toolId, rawOutput);
            observationOutput = extraction.sanitizedOutput();

            // 流式模式下发送 MEDIA 事件（字段名对齐前端 SseMediaEvent 类型定义）
            if (loopContext.getSseManager() != null && loopContext.getStreamId() != null && !extraction.mediaItems().isEmpty()) {
                for (var mediaItem : extraction.mediaItems()) {
                    var mediaData = new HashMap<String, Object>();
                    mediaData.put("toolId", toolId);
                    mediaData.put("mimeType", mediaItem.mediaType());
                    mediaData.put("encoding", mediaItem.encoding());
                    mediaData.put("data", mediaItem.data());
                    mediaData.put("field", mediaItem.fieldName());
                    mediaData.put("metadata", mediaItem.metadata());
                    loopContext.getSseManager().sendEvent(loopContext.getStreamId(), SseEventType.MEDIA, mediaData);
                }
            }

            // 将图片类型的媒体写入 pendingMedia 缓冲区，供下一次迭代 LLM 视觉分析
            if (!extraction.mediaItems().isEmpty()) {
                // 收集到请求作用域上下文，用于流式完成后持久化到附件表
                loopContext.addAllToolMedia(extraction.mediaItems());

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
                toolId, toolDisplayName, success, observationOutput != null ? observationOutput : "", obsTokens));
        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

        // L4 反馈闭环 — 工具执行成功后记录操作模板执行结果
        if (success && proceduralMemory != null && intentMatcher != null) {
            try {
                var match = intentMatcher.match(toolId + " " + inputJson);
                match.ifPresent(m -> proceduralMemory.recordExecution(
                        m.template().templateId(), true));
            } catch (Exception e) {
                log.warn("L4 执行结果记录失败: toolId={}, error={}", toolId, e.getMessage());
            }
        }

        // 记录 ToolCallStep 到 Trace
        recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                toolId, inputJson, rawOutput, success);

        log.debug("工具执行完成: toolId={}, success={}, latencyMs={}",
                toolId, success, toolCallDuration.toMillis());

        return state;
    }

    // ===== 辅助方法 =====

    /**
     * 从工具返回的 JSON 字符串中解析挂起原因。
     *
     * <p>工具通过在返回 JSON 中包含 {@code "_suspend": true} 和
     * {@code "_suspendReason": {...}} 字段来请求 Agent 挂起。
     * {@code _suspendReason} 的 {@code "type"} 字段对应 SuspendReason 子类型名。</p>
     *
     * @param output 工具返回的原始 JSON 字符串
     * @return 解析出的 SuspendReason，若不包含挂起标记则返回 null
     */
    @Nullable
    private SuspendReason parseSuspendReasonFromOutput(String output) {
        if (output == null || output.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(output);
            if (!root.isObject()) return null;
            JsonNode suspendNode = root.get("_suspend");
            if (suspendNode == null || !suspendNode.asBoolean(false)) return null;

            JsonNode reasonNode = root.get("_suspendReason");
            if (reasonNode == null || !reasonNode.isObject()) {
                log.warn("工具返回 _suspend=true 但缺少 _suspendReason 对象");
                return null;
            }

            String type = reasonNode.has("type") ? reasonNode.get("type").asText() : "";
            return switch (type) {
                case "WorkflowWait" -> new SuspendReason.WorkflowWait(
                        reasonNode.path("executionId").asText(""),
                        reasonNode.path("workflowId").asText(""),
                        reasonNode.path("workflowName").asText(""));
                case "UserConfirmation" -> new SuspendReason.UserConfirmation(
                        reasonNode.path("toolId").asText(""),
                        reasonNode.path("inputJson").asText(""),
                        reasonNode.path("riskLevel").asText(""),
                        reasonNode.path("confirmationId").asText(""));
                case "RemoteDelegation" -> new SuspendReason.RemoteDelegation(
                        reasonNode.path("remoteTaskId").asText(""),
                        reasonNode.path("remoteAgentUrl").asText(""),
                        reasonNode.path("delegatedGoal").asText(""));
                case "ScheduledWakeup" -> new SuspendReason.ScheduledWakeup(
                        Instant.parse(reasonNode.path("wakeupAt").asText(Instant.now().toString())),
                        reasonNode.path("reason").asText(""));
                case "ExternalDataWait" -> new SuspendReason.ExternalDataWait(
                        reasonNode.path("dataSourceId").asText(""),
                        reasonNode.path("description").asText(""));
                default -> {
                    log.warn("未知的 SuspendReason 类型: type={}", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            // 非 JSON 格式或解析失败 — 不是挂起信号，正常返回 null
            log.debug("工具输出非挂起信号 JSON: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 若挂起原因为 ScheduledWakeup，注册延迟任务到时发布 ScheduledWakeupEvent。
     *
     * @param state 当前挂起状态
     */
    public void scheduleWakeupIfNeeded(ReactAgentState state) {
        if (state.suspendReason() instanceof SuspendReason.ScheduledWakeup sw && eventPublisher != null) {
            var delay = Duration.between(Instant.now(), sw.wakeupAt());
            if (delay.isNegative() || delay.isZero()) {
                // 唤醒时间已过，立即发布
                eventPublisher.publishEvent(new ScheduledWakeupEvent(state.traceId(), Instant.now()));
                log.info("ScheduledWakeup 唤醒时间已过，立即发布恢复事件: traceId={}", state.traceId());
            } else {
                String traceId = state.traceId();
                suspendScheduler.schedule(() -> {
                    eventPublisher.publishEvent(new ScheduledWakeupEvent(traceId, Instant.now()));
                    log.info("ScheduledWakeup 延迟任务触发: traceId={}", traceId);
                }, delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                log.info("ScheduledWakeup 延迟任务已注册: traceId={}, wakeupAt={}, delayMs={}",
                        state.traceId(), sw.wakeupAt(), delay.toMillis());
            }
        }
    }

    /**
     * 将 SuspendReason 格式化为人类可读的描述文本。
     *
     * @param reason 挂起原因
     * @return 格式化后的描述文本
     */
    public String formatSuspendReason(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait w ->
                    "等待工作流完成 [%s] %s".formatted(w.executionId(), w.workflowName());
            case SuspendReason.UserConfirmation u ->
                    "等待用户确认工具执行 [%s] 风险等级: %s".formatted(u.toolId(), u.riskLevel());
            case SuspendReason.RemoteDelegation r ->
                    "等待远程 Agent 返回 [%s] 目标: %s".formatted(r.remoteTaskId(), r.delegatedGoal());
            case SuspendReason.ScheduledWakeup s ->
                    "定时唤醒 [%s] 原因: %s".formatted(s.wakeupAt(), s.reason());
            case SuspendReason.ExternalDataWait e ->
                    "等待外部数据就绪 [%s] %s".formatted(e.dataSourceId(), e.description());
        };
    }

    // ===== 挂起-恢复：通用恢复入口 =====

    /**
     * 校验 ResumePayload 与 SuspendReason 的配对正确性。
     *
     * <p>使用 pattern matching switch 穷举 5 对类型匹配，
     * 不匹配时抛出 IllegalArgumentException。</p>
     *
     * @param reason  挂起原因
     * @param payload 恢复载荷
     * @throws IllegalArgumentException 类型不匹配时抛出
     */
    public void validateResumePayload(SuspendReason reason, ResumePayload payload) {
        boolean valid = switch (reason) {
            case SuspendReason.WorkflowWait _ -> payload instanceof ResumePayload.WorkflowResult;
            case SuspendReason.UserConfirmation _ -> payload instanceof ResumePayload.UserDecision;
            case SuspendReason.RemoteDelegation _ -> payload instanceof ResumePayload.RemoteResult;
            case SuspendReason.ScheduledWakeup _ -> payload instanceof ResumePayload.WakeupSignal;
            case SuspendReason.ExternalDataWait _ -> payload instanceof ResumePayload.DataReady;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "恢复载荷类型不匹配: reason=%s, payload=%s".formatted(
                            reason.getClass().getSimpleName(), payload.getClass().getSimpleName()));
        }
    }

    /**
     * 将 ResumePayload 转换为人类可读的 Observation 文本。
     *
     * @param payload 恢复载荷
     * @return 格式化后的恢复描述文本
     */
    public String formatResumeObservation(ResumePayload payload) {
        return switch (payload) {
            case ResumePayload.WorkflowResult r ->
                    "工作流已完成: executionId=%s, status=%s, output=%s".formatted(
                            r.executionId(), r.status(), r.outputJson());
            case ResumePayload.UserDecision d ->
                    "用户确认结果: confirmationId=%s, approved=%s, reason=%s".formatted(
                            d.confirmationId(), d.approved(), d.reason());
            case ResumePayload.RemoteResult r ->
                    "远程 Agent 返回: remoteTaskId=%s, result=%s".formatted(
                            r.remoteTaskId(), r.resultJson());
            case ResumePayload.WakeupSignal s ->
                    "定时唤醒触发: actualWakeupAt=%s".formatted(s.actualWakeupAt());
            case ResumePayload.DataReady d ->
                    "外部数据就绪: dataSourceId=%s, data=%s".formatted(
                            d.dataSourceId(), d.dataLocationOrContent());
        };
    }

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

            // 兜底估算：当 Provider 未返回 usage 时，基于响应文本估算
            String responseText = chatResponse.getResult().getOutput().getText();
            // outputTokens 兜底：基于响应文本估算
            if (outputTokens == 0 && responseText != null && !responseText.isEmpty()) {
                outputTokens = estimateTextTokens(responseText);
            }
            // inputTokens 兜底：Provider 未返回 promptTokens 时按经验比例估算
            if (inputTokens == 0 && outputTokens > 0) {
                inputTokens = outputTokens * 4;
                log.debug("Token 兜底估算: inputTokens={} (基于 outputTokens={} × 4)",
                        inputTokens, outputTokens);
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

    /** 判断请求是否包含多模态内容。 */
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }

    /**
     * 从消息列表中提取 MediaContent。
     *
     * <p>遍历 UserMessage 中的 Media 对象，转换为 MediaContent 列表。
     * 用于多模态路由回退场景（工具产生的媒体嵌入到 messages 中后需要重新提取）。</p>
     */
    @Override
    public List<MediaContent> extractMediaContentsFromMessages(List<Message> messages) {
        var result = new ArrayList<MediaContent>();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                for (var media : um.getMedia()) {
                    try {
                        Object rawData = media.getData();
                        byte[] data;
                        if (rawData instanceof Resource resource) {
                            data = resource.getContentAsByteArray();
                        } else if (rawData instanceof byte[] bytes) {
                            data = bytes;
                        } else {
                            log.warn("不支持的媒体数据类型: type={}", rawData.getClass().getSimpleName());
                            continue;
                        }
                        result.add(new MediaContent(
                                UUID.randomUUID().toString(),
                                media.getMimeType().toString(),
                                data,
                                "extracted",
                                data.length,
                                Map.of()));
                    } catch (Exception e) {
                        log.warn("从消息中提取媒体数据失败: error={}", e.getMessage());
                    }
                }
            }
        }
        return result;
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
    @Override
    public ChatResponse adaptToChatResponse(LlmResponse llmResponse) {
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
    @Override
    public String buildConversationContextText(List<Message> messages) {
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

    // ===== 流式 LLM Trace 记录 =====

    /**
     * 记录流式 LLM 调用步骤到 Trace。
     *
     * <p>从 ChatResponse 元数据提取真实 Token 用量和完成原因，
     * 与 {@link #recordLlmStep} 保持一致的数据提取逻辑。</p>
     */
    @Override
    public void recordStreamingLlmStep(@Nullable TraceContext traceContext,
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

            // 兜底估算：当 Provider 未返回 usage 时，基于响应文本估算
            if (error == null) {
                String responseText = chatResponse.getResult().getOutput().getText();
                // outputTokens 兜底：基于响应文本估算
                if (outputTokens == 0 && responseText != null && !responseText.isEmpty()) {
                    outputTokens = estimateTextTokens(responseText);
                }
                // inputTokens 兜底：流式调用中 Provider 经常不返回 promptTokens，
                // 基于 outputTokens 按经验比例估算（输入通常是输出的 3-5 倍）
                if (inputTokens == 0 && outputTokens > 0) {
                    inputTokens = outputTokens * 4;
                    log.debug("Token 兜底估算: inputTokens={} (基于 outputTokens={} × 4)",
                            inputTokens, outputTokens);
                }
            }
        }

        // 从 Generation 元数据提取完成原因
        String finishReason;
        if (error != null) {
            finishReason = "error: " + error.getMessage();
        } else if (chatResponse != null) {
            var resultMetadata = chatResponse.getResult().getMetadata();
            finishReason = resultMetadata.getFinishReason();
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

    /**
     * 将 ReactStep 转换为 REASONING SSE 事件并推送。
     *
     * <p>仅在流式模式下生效（loopContext 包含 SSE 上下文时）。
     * 使用 switch 表达式穷举 6 种步骤类型，生成对应的事件标题和描述。</p>
     *
     * @param step        刚追加的 ReactStep
     * @param stepIndex   步骤索引
     * @param state       当前 Agent 状态
     * @param loopContext 循环上下文（含 SSE 管理器和流 ID）
     */
    void pushReactStepEvent(ReactStep step, int stepIndex,
                            ReactAgentState state, AgentLoopContext loopContext) {
        var sseManager = loopContext.getSseManager();
        var streamId = loopContext.getStreamId();
        var turnId = loopContext.getTurnId();
        if (sseManager == null || streamId == null || turnId == null) return;

        var info = switch (step) {
            case ReactStep.Thought(var content) -> new String[]{
                    "THOUGHT", "推理思考",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
            case ReactStep.ToolCall(var toolId, var toolName, var inputJson, var latencyMs) -> {
                // 用户可读的显示名称，优先 toolName，回退到 toolId
                String display = toolName != null ? toolName : toolId;
                yield new String[]{
                    "TOOL_CALL", "调用工具: " + display,
                    "正在执行工具 " + display,
                    display,  // SSE toolName 字段传显示名称
                    toolId    // SSE toolId 字段传技术标识
                };
            }
            case ReactStep.Observation(var toolId, var toolName, var success, var output, var tokensUsed) -> {
                String display = toolName != null ? toolName : toolId;
                yield new String[]{
                    "OBSERVATION", (success ? "工具返回: " : "工具失败: ") + display,
                    output.length() > 100 ? output.substring(0, 100) + "..." : output,
                    display,
                    toolId
                };
            }
            case ReactStep.Answer(var content) -> new String[]{
                    "ANSWER", "生成回答",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
            case ReactStep.Suspend(var reason, var suspendedAt, var idx) -> new String[]{
                    "SUSPEND", "Agent 挂起",
                    formatSuspendReason(reason),
                    null
            };
            case ReactStep.Resume(var payload, var resumedAt, var duration) -> new String[]{
                    "RESUME", "Agent 恢复",
                    "挂起时长: " + duration.toMillis() + "ms",
                    null
            };
        };

        var extra = new HashMap<String, Object>();
        extra.put("stepIndex", stepIndex);
        // info[4] 存在时为 toolId（技术标识），传入 extra 供前端调试使用
        if (info.length > 4 && info[4] != null) {
            extra.put("toolId", info[4]);
        }
        sendReasoningEvent(sseManager, streamId, state.sessionId(), turnId,
                info[0], info[1], info[2], info[3], extra);
    }

    /** 发送 REASONING SSE 事件。 */
    @Override
    public void sendReasoningEvent(SseSessionManager sseManager, String streamId,
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

    // ===== A2UI 辅助方法 =====

    /** 判断 A2UI 功能是否启用。 */
    @Override
    public boolean isA2uiEnabled() {
        return a2uiProperties != null && a2uiProperties.enabled();
    }

    /** 获取 A2UI 最大组件数。 */
    @Override
    public int getA2uiMaxComponents() {
        return a2uiProperties != null ? a2uiProperties.maxComponentsPerTree() : 0;
    }

    /** 增强系统提示词（流式约束 + A2UI）。 */
    @Override
    public String enhanceSystemPromptForStreaming(String systemText, @Nullable String a2uiPrompt) {
        return contextAssembler.enhanceSystemPromptForStreaming(systemText, a2uiPrompt);
    }

    /** 解析并校验 A2UI JSON 为组件树。 */
    @Override
    @Nullable
    public A2uiComponentTree parseAndValidateA2uiTree(String json, int maxComponents) {
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
    @Override
    public void logLlmPromptIfEnabled(String scene, List<Message> messages,
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
}
