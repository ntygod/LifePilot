package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.callback.StreamingCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.MediaValidationException;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Agent 执行编排器 — 提取 run/runStreaming 共享编排逻辑。
 *
 * <p>负责请求预处理、状态初始化、持久化、挂起处理、完成后处理等编排步骤，
 * 将核心 ReAct 循环委托给 {@link ReactAgentLoop}。
 * 每次调用创建独立的 {@link AgentLoopContext}，消除并发安全隐患。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";

    /** 测试会话前缀 — 以此开头的 sessionId 不持久化对话历史和记忆。 */
    private static final String TEST_SESSION_PREFIX = "test:";
    /** 评估会话前缀。 */
    private static final String EVAL_SESSION_PREFIX = "eval-";

    // ===== 核心依赖 =====
    private final ReactAgentLoop agentLoop;
    private final AgentPersistenceHandler persistenceHandler;
    private final StreamingEventHandler streamingEventHandler;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final LlmRouter llmRouter;
    @Nullable private final TraceRecorder traceRecorder;
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaValidator mediaValidator;
    @Nullable private final MediaProcessor mediaProcessor;
    @Nullable private final SuspendStore suspendStore;
    @Nullable private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final java.util.concurrent.ScheduledExecutorService suspendScheduler;

    public AgentOrchestrator(
            ReactAgentLoop agentLoop,
            AgentPersistenceHandler persistenceHandler,
            StreamingEventHandler streamingEventHandler,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            SessionManager sessionManager,
            LlmRouter llmRouter,
            @Nullable TraceRecorder traceRecorder,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaValidator mediaValidator,
            @Nullable MediaProcessor mediaProcessor,
            @Nullable SuspendStore suspendStore,
            @Nullable org.springframework.context.ApplicationEventPublisher eventPublisher,
            com.lifepilot.config.threadpool.SharedScheduler sharedScheduler) {
        this.agentLoop = agentLoop;
        this.persistenceHandler = persistenceHandler;
        this.streamingEventHandler = streamingEventHandler;
        this.config = config;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.llmRouter = llmRouter;
        this.traceRecorder = traceRecorder;
        this.multimodalRouter = multimodalRouter;
        this.mediaValidator = mediaValidator;
        this.mediaProcessor = mediaProcessor;
        this.suspendStore = suspendStore;
        this.eventPublisher = eventPublisher;
        this.suspendScheduler = sharedScheduler.cleanup();
    }

    /** 判断是否为临时会话（测试或评估），不持久化对话历史和记忆。 */
    private static boolean isTestSession(@Nullable String sessionId) {
        return sessionId != null
                && (sessionId.startsWith(TEST_SESSION_PREFIX) || sessionId.startsWith(EVAL_SESSION_PREFIX));
    }

    // ===== 同步执行入口 =====

    /**
     * 同步执行 ReAct 循环。
     *
     * <p>完整流程：媒体预处理 → 初始化状态 → L1 写入用户消息 → 持久化用户消息 →
     * 启动 Trace → coreLoop → 挂起/完成分支 → L1 写入助手响应 → 持久化助手消息 →
     * 异步后处理 → 构建 AgentResponse。</p>
     */
    public AgentResponse run(AgentRequest request) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        var loopContext = new AgentLoopContext();

        var token = new CancellationToken();

        // 媒体校验与预处理
        final AgentRequest effectiveRequest = preprocessMedia(request, state, null, null);
        if (effectiveRequest == null) {
            // preprocessMedia 返回 null 表示媒体校验失败（仅流式模式使用，同步模式走异常路径）
            return AgentResponse.error(state, new MediaValidationException("媒体校验失败"));
        }

        try {
            state = initState(effectiveRequest);
            boolean testSession = isTestSession(effectiveRequest.sessionId());
            if (!testSession) {
                persistenceHandler.persistUserMessage(state);
            }
            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();

            // 核心循环 — 非流式回调
            var callback = new com.lifepilot.agent.callback.NonStreamingCallback(
                    config, llmRouter, multimodalRouter, effectiveRequest, agentLoop);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, token, loopContext);

            // 挂起分支
            if (state.suspended() && state.suspendReason() != null) {
                return handleSuspendSync(state, traceContext);
            }

            // 构建推理概要
            if (state.terminationReason() == null) {
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder().reasoningSummary(summary).build();
            }

            String assistantMessageId = null;
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                assistantMessageId = persistenceHandler.persistAssistantMessage(state, reactStepsJson);
                persistenceHandler.persistInjectionRecord(assistantMessageId, state.sessionId(),
                        loopContext);
                persistenceHandler.persistToolMediaAttachments(assistantMessageId, state.sessionId(),
                        loopContext.getCollectedToolMedia());
                loopContext.clearToolMedia();
                persistenceHandler.resolveWorkspaceForTrace(state.sessionId(), state.traceId());
                persistenceHandler.asyncPostProcess(state);
            }

            TokenUsage tokenUsage = aggregateTokenUsage(traceContext);
            var cachedTree = loopContext.getLastCollectedA2uiTree();
            var a2uiComponents = cachedTree != null ? cachedTree.components() : null;

            return new AgentResponse(
                    state.traceId(), state.sessionId(),
                    state.finalOutput() != null ? state.finalOutput() : "",
                    state.budget().tokensUsed(), state.stepCount(),
                    state.terminationReason(), assistantMessageId,
                    a2uiComponents, tokenUsage);

        } catch (Exception e) {
            log.error("ReAct 循环异常终止: error={}", e.getMessage(), e);
            error = e;
            return AgentResponse.error(state, e);
        } finally {
            endTraceIfEnabled(traceContext, state, error);
        }
    }

    // ===== SSE 流式执行入口 =====

    /**
     * SSE 流式执行 ReAct 循环。
     *
     * <p>完整流程与 run() 一致，但通过 SSE 推送 TOKEN / REASONING / MEDIA / DONE 事件。</p>
     */
    public void runStreaming(AgentRequest request, String streamId,
                             SseSessionManager sseManager,
                             CancellationToken cancellationToken) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        String finalContent = "";
        TokenUsage finalTokenUsage = null;
        String reasoningSummary = null;
        String tempTurnId = UUID.randomUUID().toString();
        String userMessageId = null;
        String assistantMessageId = null;
        var loopContext = new AgentLoopContext(sseManager, streamId, tempTurnId);

        // 媒体校验与预处理
        final AgentRequest effectiveRequest = preprocessMedia(request, state, sseManager, streamId);
        if (effectiveRequest == null) return; // 媒体校验失败，已发送 SSE 错误

        try {
            state = initState(effectiveRequest);
            boolean testSession = isTestSession(effectiveRequest.sessionId());
            if (!testSession) {
                userMessageId = persistenceHandler.persistUserMessageReturningId(state);
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

            agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                    "AGENT_START", "开始处理请求",
                    "Agent 已接收到用户请求，正在准备上下文与预算。",
                    null, Map.of());

            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();

            // 核心循环 — 流式回调
            var callback = new StreamingCallback(config, llmRouter, multimodalRouter, agentLoop,
                    cancellationToken, null, sseManager, streamId,
                    request.sessionId(), tempTurnId, effectiveRequest);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, cancellationToken, loopContext);

            // 挂起分支
            if (state.suspended() && state.suspendReason() != null) {
                handleSuspendStreaming(state, streamId, sseManager);
                return;
            }

            // 检查流式错误
            if (callback.hasStreamingError()) {
                error = callback.getStreamingError();
            } else {
                // 归一化最终输出
                String callbackContent = callback.getFinalContent();
                finalContent = state.finalOutput() != null
                        ? state.finalOutput()
                        : (callbackContent != null ? callbackContent : finalContent);
                var extractedFinalContent = streamingEventHandler.extractA2uiContent(finalContent);
                A2uiComponentTree finalA2uiTree = extractedFinalContent.tree();
                finalContent = extractedFinalContent.visibleText();
                if (finalA2uiTree != null) {
                    loopContext.setLastCollectedA2uiTree(finalA2uiTree);
                }

                if (state.terminationReason() == null) {
                    reasoningSummary = buildReasoningSummary(state, traceContext);
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .reasoningSummary(reasoningSummary)
                            .build();
                }

                // 持久化
                if (!testSession) {
                    String a2uiJson = streamingEventHandler.serializeA2uiTree(
                            loopContext.getLastCollectedA2uiTree());
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantMessageId = persistenceHandler.persistAssistantMessageWithA2ui(
                            state, finalContent, reasoningSummary, a2uiJson, reactStepsJson);
                    persistenceHandler.persistInjectionRecord(assistantMessageId, state.sessionId(),
                            loopContext);
                    persistenceHandler.persistToolMediaAttachments(assistantMessageId, state.sessionId(),
                            loopContext.getCollectedToolMedia());
                    loopContext.clearToolMedia();
                    persistenceHandler.persistUserMediaAttachments(assistantMessageId,
                            state.sessionId(), effectiveRequest.mediaContents());
                    persistenceHandler.resolveWorkspaceForTrace(state.sessionId(), state.traceId());
                    persistenceHandler.asyncPostProcess(state);
                }
                finalTokenUsage = aggregateTokenUsage(traceContext);
            }
        } catch (Exception e) {
            log.error("流式 Agent 循环异常终止: error={}", e.getMessage(), e);
            error = e;
        } finally {
            endTraceIfEnabled(traceContext, state, error);

            if (error != null) {
                streamingEventHandler.sendStreamError(sseManager, streamId, 500,
                        "处理失败: " + error.getMessage(), state.traceId());
            } else {
                agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                        "ANSWER_FINALIZED", "回答已生成", "本轮推理与回答已完成。",
                        null, Map.of());
                var doneData = streamingEventHandler.buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        state.steps(), reasoningSummary, finalContent, assistantMessageId,
                        loopContext.getLastCollectedA2uiTree());
                sseManager.sendEvent(streamId, SseEventType.DONE, doneData);
                sseManager.closeEmitter(streamId);
            }
        }
    }

    // ===== 挂起-恢复：通用恢复入口 =====

    /**
     * 通用恢复入口 — 从挂起状态恢复 Agent 执行。
     *
     * @param traceId 挂起时的 traceId
     * @param payload 恢复载荷
     */
    public void resumeFromSuspend(String traceId, ResumePayload payload) {
        if (suspendStore == null) {
            throw new IllegalStateException("SuspendStore 未注入，无法恢复挂起的 Agent");
        }
        SuspendedAgent suspended = suspendStore.load(traceId)
                .orElseThrow(() -> new IllegalStateException("未找到挂起的 Agent: " + traceId));
        agentLoop.validateResumePayload(suspended.suspendReason(), payload);

        ReactAgentState state = suspended.toAgentState(objectMapper).resume();
        state = state.appendStep(new ReactStep.Resume(payload, Instant.now(),
                Duration.between(suspended.suspendedAt(), Instant.now())));
        String resumeToolId = "resume:" + suspended.suspendReason().getClass().getSimpleName();
        state = state.appendStep(new ReactStep.Observation(
                resumeToolId, null, true, agentLoop.formatResumeObservation(payload), 0));
        suspendStore.delete(traceId);

        log.info("Agent 恢复执行: traceId={}, reasonType={}, payloadType={}",
                traceId, suspended.suspendReason().getClass().getSimpleName(),
                payload.getClass().getSimpleName());

        final ReactAgentState resumedState = state;
        final SuspendedAgent suspendedSnapshot = suspended;
        Thread.startVirtualThread(() -> runResume(resumedState, suspendedSnapshot));
    }

    /** 非流式恢复路径。 */
    private void runResume(ReactAgentState state, SuspendedAgent suspended) {
        var token = new CancellationToken();
        var loopStart = Instant.now();
        var loopContext = new AgentLoopContext();

        try {
            var request = new AgentRequest(
                    state.goal(), state.sessionId(), state.channel(),
                    null, state.budget(), state.parentTraceId(),
                    state.depth(), null, state.allowedToolIds(), null, null);
            var callback = new com.lifepilot.agent.callback.NonStreamingCallback(
                    config, llmRouter, multimodalRouter, request, agentLoop);
            state = agentLoop.coreLoop(state, request, null, loopStart, callback, token, loopContext);

            boolean testSession = isTestSession(state.sessionId());
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                persistenceHandler.persistAssistantMessage(state, reactStepsJson);
                persistenceHandler.resolveWorkspaceForTrace(state.sessionId(), state.traceId());
                persistenceHandler.asyncPostProcess(state);
            }
            log.info("Agent 恢复后执行完成: traceId={}, stepCount={}", state.traceId(), state.stepCount());
        } catch (Exception e) {
            log.error("Agent 恢复后执行异常: traceId={}, error={}", state.traceId(), e.getMessage(), e);
        }
    }

    // ===== 共享编排步骤 =====

    /**
     * 媒体校验与预处理 — 返回处理后的请求，校验失败时返回 null。
     *
     * <p>同步模式下校验失败抛异常（由调用方捕获），
     * 流式模式下校验失败发送 SSE 错误事件并返回 null。</p>
     */
    @Nullable
    private AgentRequest preprocessMedia(AgentRequest request, ReactAgentState state,
                                         @Nullable SseSessionManager sseManager,
                                         @Nullable String streamId) {
        if (!hasMultimodalContent(request)) return request;

        if (multimodalRouter == null) {
            log.warn("请求包含媒体内容但 MultimodalRouter 未注入，降级为纯文本: sessionId={}",
                    request.sessionId());
            return request;
        }

        if (mediaValidator == null || mediaProcessor == null) return request;

        try {
            var processedMedia = validateAndPreprocessMedia(request.mediaContents());
            return new AgentRequest(request.message(), request.sessionId(), request.channel(),
                    request.systemPrompt(), request.budget(), request.parentTraceId(),
                    request.depth(), request.preferredProvider(), request.allowedToolIds(),
                    processedMedia, request.temperature());
        } catch (MediaValidationException e) {
            log.warn("媒体校验失败: sessionId={}, error={}", request.sessionId(), e.getMessage());
            if (sseManager != null && streamId != null) {
                streamingEventHandler.sendStreamError(sseManager, streamId, 400,
                        "媒体校验失败: " + e.getMessage(), state.traceId());
                return null;
            }
            throw e; // 同步模式重新抛出
        }
    }

    /** 判断请求是否包含多模态内容。 */
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }

    /** 媒体校验与预处理。 */
    private List<MediaContent> validateAndPreprocessMedia(List<MediaContent> mediaContents) {
        assert mediaValidator != null;
        mediaValidator.validateAll(mediaContents);
        List<MediaContent> images = mediaContents.stream()
                .filter(mc -> mc.mimeType().startsWith("image/")).toList();
        assert mediaProcessor != null;
        List<MediaContent> processedImages = mediaProcessor.processAll(images);
        List<MediaContent> nonImages = mediaContents.stream()
                .filter(mc -> !mc.mimeType().startsWith("image/")).toList();
        var result = new ArrayList<MediaContent>(processedImages.size() + nonImages.size());
        result.addAll(processedImages);
        result.addAll(nonImages);
        return List.copyOf(result);
    }

    /** 初始化 ReAct 状态。 */
    private ReactAgentState initState(AgentRequest request) {
        var defaultBudget = Budget.fromConfig(config.getBudget());
        if (isTestSession(request.sessionId())) {
            return ReactAgentState.init(request, defaultBudget);
        }
        var existingSession = sessionManager.findSession(request.sessionId());
        if (existingSession.isPresent()) {
            var snapshot = existingSession.get();
            return ReactAgentState.fromSession(snapshot, request, defaultBudget);
        }
        return ReactAgentState.init(request, defaultBudget);
    }

    /** 启动 Trace（如果 TraceRecorder 可用）。 */
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    /** 结束 Trace。 */
    private void endTraceIfEnabled(@Nullable TraceContext traceContext,
                                   ReactAgentState state, @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) return;
        String finalOutput = state.finalOutput();
        boolean success = error == null && state.terminationReason() == null;
        String errorMessage = error != null ? error.getMessage() : null;
        String terminationReason = error != null
                ? error.getClass().getSimpleName() : state.terminationReason();
        traceRecorder.endTrace(traceContext, finalOutput, success, errorMessage, terminationReason);
    }

    // ===== 挂起处理 =====

    /** 同步模式挂起处理。 */
    private AgentResponse handleSuspendSync(ReactAgentState state,
                                            @Nullable TraceContext traceContext) {
        persistenceHandler.saveWorkspaceForSuspend(state);
        if (suspendStore != null) {
            suspendStore.save(SuspendedAgent.from(state, objectMapper));
            agentLoop.scheduleWakeupIfNeeded(state);
            log.info("Agent 已挂起并持久化: traceId={}, reasonType={}",
                    state.traceId(), state.suspendReason().getClass().getSimpleName());
        } else {
            log.warn("Agent 请求挂起但 SuspendStore 未注入，无法持久化: traceId={}", state.traceId());
        }
        return new AgentResponse(
                state.traceId(), state.sessionId(),
                "Agent 已挂起，等待恢复信号。原因: " + agentLoop.formatSuspendReason(state.suspendReason()),
                state.budget().tokensUsed(), state.stepCount(),
                "suspended", null, null, aggregateTokenUsage(traceContext));
    }

    /** 流式模式挂起处理。 */
    private void handleSuspendStreaming(ReactAgentState state, String streamId,
                                        SseSessionManager sseManager) {
        persistenceHandler.saveWorkspaceForSuspend(state);
        if (suspendStore != null) {
            var suspendedAgent = SuspendedAgent.from(state, objectMapper).toBuilder()
                    .streamId(streamId).build();
            suspendStore.save(suspendedAgent);
            agentLoop.scheduleWakeupIfNeeded(state);
            log.info("流式 Agent 已挂起并持久化: traceId={}, reasonType={}, streamId={}",
                    state.traceId(), state.suspendReason().getClass().getSimpleName(), streamId);
        } else {
            log.warn("Agent 请求挂起但 SuspendStore 未注入，无法持久化: traceId={}", state.traceId());
        }
        sseManager.sendEvent(streamId, SseEventType.AGENT_SUSPENDED, Map.of(
                "traceId", state.traceId(),
                "sessionId", state.sessionId(),
                "reasonType", state.suspendReason().getClass().getSimpleName(),
                "reasonDetail", agentLoop.formatSuspendReason(state.suspendReason()),
                "suspendedAt", Instant.now().toString()));
        sseManager.closeEmitter(streamId);
    }

    // ===== 推理概要与 Token 聚合 =====

    /** 构建推理概要字符串。 */
    private String buildReasoningSummary(ReactAgentState state,
                                         @Nullable TraceContext traceContext) {
        int steps = state.stepCount();
        int tokens = state.budget() != null ? state.budget().tokensUsed() : 0;
        String modelId = DEFAULT_MODEL_ID;
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

    /** 将 ReactStep 列表序列化为 JSON 字符串。 */
    @Nullable
    private String serializeReactStepsJson(@Nullable List<ReactStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return ReactStepSerializer.serializeToJson(steps, objectMapper);
    }

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
}
