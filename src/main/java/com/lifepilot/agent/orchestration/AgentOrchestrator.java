package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.DegradedResponseBuilder;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.callback.NonStreamingCallback;
import com.lifepilot.agent.callback.StreamingCallback;
import com.lifepilot.agent.checkpoint.AgentCheckpoint;
import com.lifepilot.agent.checkpoint.AgentCheckpointFingerprinter;
import com.lifepilot.agent.checkpoint.AgentCheckpointStore;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidationException;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
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
 * Agent 编排器，负责协调和管理 Agent 的完整执行流程。
 *
 * <p>提供同步和流式两种执行模式，支持 ReAct 循环、checkpoint 保存与恢复、SSE 流式输出、
 * Trace 追踪等功能。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";

    // ===== 依赖注入组件：由 Spring 或其他容器提供的核心服务 =====
    private final ReactAgentLoop agentLoop;
    private final StreamingEventHandler streamingEventHandler;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;
    private final GenerationRouter generationRouter;
    @Nullable private final TraceRecorder traceRecorder;
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaValidator mediaValidator;
    @Nullable private final MediaProcessor mediaProcessor;
    @Nullable private final AgentCheckpointStore checkpointStore;
    @Nullable private final SuspendStore suspendStore;
    @Nullable private final SessionWorkspaceService workspaceService;
    private final AgentExecutionPersistenceSupport executionPersistence;

    public AgentOrchestrator(
            ReactAgentLoop agentLoop,
            AgentPersistenceHandler persistenceHandler,
            StreamingEventHandler streamingEventHandler,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            GenerationRouter generationRouter,
            @Nullable TraceRecorder traceRecorder,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaValidator mediaValidator,
            @Nullable MediaProcessor mediaProcessor,
            @Nullable AgentCheckpointStore checkpointStore,
            @Nullable SuspendStore suspendStore,
            @Nullable ChatTurnService chatTurnService,
            @Nullable SessionWorkspaceService workspaceService) {
        this.agentLoop = agentLoop;
        this.streamingEventHandler = streamingEventHandler;
        this.config = config;
        this.objectMapper = objectMapper;
        this.generationRouter = generationRouter;
        this.traceRecorder = traceRecorder;
        this.multimodalRouter = multimodalRouter;
        this.mediaValidator = mediaValidator;
        this.mediaProcessor = mediaProcessor;
        this.checkpointStore = checkpointStore;
        this.suspendStore = suspendStore;
        this.workspaceService = workspaceService;
        this.executionPersistence = new AgentExecutionPersistenceSupport(persistenceHandler, chatTurnService);
    }

    /** 判断是否为测试会话，测试会话不需要持久化 transcript */
    private boolean isTestSession(@Nullable String sessionId) {
        return executionPersistence.isTransientSession(sessionId);
    }

    // ===== 公共 API：供外部调用的同步/流式/恢复入口 =====
    /**
     * 运行 Agent 并返回最终响应结果。
     *
     * <p>适用于非流式场景，会等待整个 ReAct 循环完成后一次性返回结果。</p>
     */
    public AgentResponse run(AgentRequest request) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Exception error = null;
        var loopContext = new AgentLoopContext();

        var token = new CancellationToken();
        final AgentRequest effectiveRequest = preprocessMedia(request, state, null, null);
        if (effectiveRequest == null) {
            // preprocessMedia 返回 null 表示媒体校验失败
            return AgentResponse.error(state, new MediaValidationException("媒体校验失败"));
        }

        try {
            state = initStateWithResumePolicy(effectiveRequest);
            executionPersistence.bindTurnTrace(state);
            boolean testSession = isTestSession(effectiveRequest.sessionId());
            executionPersistence.persistUserTurn(state, effectiveRequest);
            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();
            var callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, effectiveRequest, agentLoop);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, token, loopContext);
            cleanupWorkspaceProgress(state);
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                return handleSuspendSync(state, traceContext, loopContext);
            }
            if (state.terminationReason() == null) {
                String summary = buildReasoningSummary(state, traceContext);
                state = state.toBuilder().reasoningSummary(summary).build();
                clearCheckpoint(effectiveRequest);
            } else {
                saveCheckpoint(state, effectiveRequest);
            }

            String assistantEntryId = null;
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
            }
            executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));

            TokenUsage tokenUsage = aggregateTokenUsage(traceContext);
            var cachedTree = loopContext.getLastCollectedA2uiTree();
            var a2uiComponents = cachedTree != null ? cachedTree.components() : null;

            return buildAgentResponse(state, assistantEntryId, a2uiComponents, tokenUsage);

        } catch (Exception e) {
            log.error("ReAct 循环执行失败：error={}", e.getMessage(), e);
            if (shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, e);
                saveCheckpoint(state, effectiveRequest);
                boolean testSession = isTestSession(effectiveRequest.sessionId());
                String assistantEntryId = null;
                if (!testSession) {
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                return buildAgentResponse(state, assistantEntryId, null, aggregateTokenUsage(traceContext));
            }
            error = e;
            executionPersistence.markTurnFailed(state, e);
            return AgentResponse.error(state, e);
        } finally {
            endTraceIfEnabled(traceContext, state, error);
        }
    }
    /**
     * 流式执行 Agent 并通过 SSE 推送事件。
     *
     * <p>适用于需要实时反馈的场景，会逐步发送思考过程、工具调用、最终答案等事件。</p>
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
        String tempTurnId = request.turnId() != null && !request.turnId().isBlank()
                ? request.turnId()
                : UUID.randomUUID().toString();
        String userEntryId = null;
        String assistantEntryId = null;
        boolean testSession = false;
        var eventBuffer = sseManager.createEventBuffer(streamId);
        var loopContext = new AgentLoopContext(sseManager, streamId, tempTurnId, eventBuffer);
        final AgentRequest effectiveRequest = preprocessMedia(request, state, sseManager, streamId);
        if (effectiveRequest == null) return;

        try {
            state = initStateWithResumePolicy(effectiveRequest);
            executionPersistence.bindTurnTrace(state);
            testSession = isTestSession(effectiveRequest.sessionId());
            userEntryId = executionPersistence.persistUserTurn(state, effectiveRequest);
            if (state.traceId() != null) {
                var traceStartData = new HashMap<String, Object>();
                traceStartData.put("sessionId", request.sessionId());
                traceStartData.put("turnId", tempTurnId);
                traceStartData.put("traceId", state.traceId());
                traceStartData.put("timestamp", Instant.now().toEpochMilli());
                if (userEntryId != null) {
                    traceStartData.put("userEntryId", userEntryId);
                }
                if (eventBuffer != null) {
                    eventBuffer.offer(SseEventType.TRACE_START, traceStartData);
                } else {
                    sseManager.sendEvent(streamId, SseEventType.TRACE_START, traceStartData);
                }
            }

            agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                    "AGENT_START", "开始执行",
                    "Agent 开始执行任务，正在初始化推理循环和流式输出。",
                    null, Map.of(), eventBuffer);

            traceContext = startTraceIfEnabled(state, effectiveRequest);
            loopStart = Instant.now();
            var callback = new StreamingCallback(config, generationRouter, multimodalRouter, agentLoop,
                    cancellationToken, loopContext, sseManager, streamId,
                    request.sessionId(), tempTurnId, effectiveRequest);
            state = agentLoop.coreLoop(state, effectiveRequest, traceContext, loopStart,
                    callback, cancellationToken, loopContext);
            cleanupWorkspaceProgress(state);
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                handleSuspendStreaming(state, streamId, sseManager, loopContext);
                return;
            }

            // 检查流式回调中是否发生了错误
            if (callback.hasStreamingError()) {
                Exception streamingFailure = callback.getStreamingError();
                if (shouldDegradeUnexpectedException(state)) {
                    state = degradeForException(state, streamingFailure);
                    finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                    saveCheckpoint(state, effectiveRequest);
                } else {
                    error = streamingFailure;
                }
            }

            if (error == null) {
                // 提取最终内容和 A2UI 组件树
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
                    clearCheckpoint(effectiveRequest);
                } else {
                    state = state.toBuilder()
                            .finalOutput(finalContent)
                            .build();
                    saveCheckpoint(state, effectiveRequest);
                }

                // 持久化助手回复和 turn 记录
                if (!testSession) {
                    String a2uiJson = streamingEventHandler.serializeA2uiTree(
                            loopContext.getLastCollectedA2uiTree());
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantStreaming(
                            state, finalContent, reasoningSummary, a2uiJson, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                finalTokenUsage = aggregateTokenUsage(traceContext);
            }
        } catch (Exception e) {
            log.error("流式执行 Agent 失败：error={}", e.getMessage(), e);
            if (shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, e);
                finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                saveCheckpoint(state, effectiveRequest);
                if (!testSession) {
                    String a2uiJson = streamingEventHandler.serializeA2uiTree(
                            loopContext.getLastCollectedA2uiTree());
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantStreaming(
                            state, finalContent, reasoningSummary, a2uiJson, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                finalTokenUsage = aggregateTokenUsage(traceContext);
            } else {
                error = e;
            }
        } finally {
            endTraceIfEnabled(traceContext, state, error);

            if (error != null) {
                executionPersistence.markTurnFailed(state, error);
                if (eventBuffer != null && !eventBuffer.isClosed()) {
                    // 缓冲区模式：构建错误数据，通过 offerTerminal 排空后派发
                    var errorData = new java.util.HashMap<String, Object>();
                    errorData.put("code", 500);
                    errorData.put("message", "执行失败：Agent 遇到未预期错误 - " + error.getMessage());
                    if (state.traceId() != null) errorData.put("traceId", state.traceId());
                    if (tempTurnId != null && !tempTurnId.isBlank()) errorData.put("turnId", tempTurnId);
                    errorData.put("turnStatus", ChatTurnStatus.FAILED.name());
                    eventBuffer.offerTerminal(SseEventType.ERROR, errorData);
                } else {
                    streamingEventHandler.sendStreamError(
                            sseManager, streamId, 500,
                            "执行失败：Agent 遇到未预期错误 - " + error.getMessage(),
                            state.traceId(), tempTurnId, ChatTurnStatus.FAILED);
                }
            } else {
                agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                        "ANSWER_FINALIZED", "回答已完成",
                        "流式输出已完成，正在发送 DONE 事件。",
                        null, Map.of(), eventBuffer);
                var doneData = streamingEventHandler.buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        state.steps(), reasoningSummary, finalContent, assistantEntryId,
                        loopContext.getLastCollectedA2uiTree(),
                        loopContext.streamingTimingsMs());
                if (eventBuffer != null && !eventBuffer.isClosed()) {
                    // 缓冲区模式：offerTerminal 触发排空 → 派发 DONE → 关闭 emitter
                    eventBuffer.offerTerminal(SseEventType.DONE, doneData);
                } else {
                    sseManager.sendEvent(streamId, SseEventType.DONE, doneData);
                    sseManager.closeEmitter(streamId);
                }
            }
        }
    }
    /**
     * 从挂起状态恢复 Agent 执行。
     *
     * <p>当 Agent 因等待用户确认、工作流节点等原因挂起后，可通过此方法恢复执行。
     * 会从 suspendStore 加载之前的状态，并继续执行 ReAct 循环。</p>
     */
    public void resumeFromSuspend(String traceId, ResumePayload payload) {
        if (suspendStore == null) {
            throw new IllegalStateException("SuspendStore 未配置，无法恢复挂起的 Agent");
        }
        // 原子加载并删除挂起状态，防止并发恢复同一个 Agent
        SuspendedAgent suspended = suspendStore.loadAndDelete(traceId)
                .orElseThrow(() -> new IllegalStateException("找不到挂起的 Agent: " + traceId));
        agentLoop.validateResumePayload(suspended.suspendReason(), payload);

        ReactAgentState state = suspended.toAgentState(objectMapper).resume();
        state = state.appendStep(new ReactStep.Resume(payload, Instant.now(),
                Duration.between(suspended.suspendedAt(), Instant.now())));
        String resumeToolId = "resume:" + suspended.suspendReason().getClass().getSimpleName();
        state = state.appendStep(new ReactStep.Observation(
                resumeToolId, null, true, agentLoop.formatResumeObservation(payload), 0, null));
        log.info("Agent 从挂起状态恢复执行：traceId={}, reasonType={}, payloadType={}",
                traceId, suspended.suspendReason().getClass().getSimpleName(),
                payload.getClass().getSimpleName());

        final ReactAgentState resumedState = state;
        Thread.startVirtualThread(() -> runResume(traceId, resumedState, suspended));
    }

    /** 在虚拟线程中异步执行恢复后的 Agent 逻辑
        避免阻塞调用方（通常是 Web 请求线程），让恢复操作在后台运行
     */
    private void runResume(String suspendedTraceId, ReactAgentState state,
                           SuspendedAgent originalSuspend) {
        var token = new CancellationToken();
        var loopStart = Instant.now();
        var loopContext = new AgentLoopContext();

        try {
            var request = new AgentRequest(
                    state.goal(),
                    state.sessionId(),
                    state.source(),
                    state.userId(),
                    state.turnId(),
                    ChatTurnAction.RESUME,
                    state.taskMode(),
                    null,
                    state.budget(),
                    state.parentTraceId(),
                    state.depth(),
                    state.preferredProvider(),
                    state.allowedToolIds(),
                    null,
                    null,
                    ResumePolicy.AUTO
            );
            var callback = new com.lifepilot.agent.callback.NonStreamingCallback(
                    config, generationRouter, multimodalRouter, request, agentLoop);
            state = agentLoop.coreLoop(state, request, null, loopStart, callback, token, loopContext);

            if (state.suspended() && state.suspendReason() != null) {
                handleSuspendSync(state, null, loopContext);
                log.info("Agent 从挂起恢复后再次进入挂起态：traceId={}, reasonType={}",
                        state.traceId(), state.suspendReason().getClass().getSimpleName());
                return;
            }

            boolean testSession = isTestSession(state.sessionId());
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                String assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
            }
            // 挂起状态已在 resumeFromSuspend 中原子删除，无需再次清理
            log.info("Agent 从挂起恢复完成：traceId={}, stepCount={}", state.traceId(), state.stepCount());
        } catch (Exception e) {
            executionPersistence.markTurnFailed(state, e);
            // 恢复失败时重新保存挂起快照，允许后续重试恢复
            if (suspendStore != null) {
                try {
                    suspendStore.save(originalSuspend);
                    log.warn("恢复失败，已重新保存挂起快照以允许重试：traceId={}", suspendedTraceId);
                } catch (Exception saveEx) {
                    log.error("重新保存挂起快照也失败，状态已丢失：traceId={}, error={}",
                            suspendedTraceId, saveEx.getMessage());
                }
            }
            log.error("Agent 从挂起恢复失败：traceId={}, suspendedTraceId={}, error={}",
                    state.traceId(), suspendedTraceId, e.getMessage(), e);
        }
    }
    /**
     * 处理多模态内容（图片、音频等）的预处理和校验。
     *
     * <p>如果配置了 MultimodalRouter 和 MediaValidator/MediaProcessor，
     * 会对媒体内容进行格式校验、压缩转码等处理；否则直接返回原始请求。</p>
     */
    @Nullable
    private AgentRequest preprocessMedia(AgentRequest request, ReactAgentState state,
                                         @Nullable SseSessionManager sseManager,
                                         @Nullable String streamId) {
        if (!hasMultimodalContent(request)) return request;

        if (multimodalRouter == null) {
            log.warn("检测到多模态内容但未配置 MultimodalRouter，将使用原始媒体内容：sessionId={}",
                    request.sessionId());
            return request;
        }

        if (mediaValidator == null || mediaProcessor == null) return request;

        try {
            var processedMedia = validateAndPreprocessMedia(request.mediaContents());
            return new AgentRequest(
                    request.message(),
                    request.sessionId(),
                    request.source(),
                    request.userId(),
                    request.turnId(),
                    request.action(),
                    request.taskMode(),
                    request.systemPrompt(),
                    request.budget(),
                    request.parentTraceId(),
                    request.depth(),
                    request.preferredProvider(),
                    request.allowedToolIds(),
                    processedMedia,
                    request.temperature(),
                    request.resumePolicy()
            );
        } catch (MediaValidationException e) {
            log.warn("媒体内容校验失败：sessionId={}, error={}", request.sessionId(), e.getMessage());
            if (sseManager != null && streamId != null) {
                executionPersistence.markTurnFailed(state, e);
                streamingEventHandler.sendStreamError(
                        sseManager,
                        streamId,
                        400,
                        "媒体内容校验失败：" + e.getMessage(),
                        state.traceId(),
                        request.turnId(),
                        ChatTurnStatus.FAILED
                );
                return null;
            }
            throw e;
        }
    }
    /** 判断请求是否包含多模态内容（图片、音频、视频等） */
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }
    /** 对所有媒体内容进行格式校验和预处理（压缩、转码等） */
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
    /**
     * 初始化 Agent 状态，优先尝试从 checkpoint 恢复。
     *
     * <p>对于测试会话，直接创建新状态；否则尝试从 checkpoint 加载历史状态。</p>
     */
    private ReactAgentState initState(AgentRequest request) {
        var defaultBudget = Budget.fromConfig(config.getBudget());
        if (isTestSession(request.sessionId())) {
            return ReactAgentState.init(request, defaultBudget);
        }
        var checkpoint = claimCheckpoint(request);
        if (checkpoint.isPresent()) {
            var restoredState = checkpoint.get().restore(objectMapper, request);
            log.info("从 checkpoint 恢复到新 session：sessionId={}, fromTraceId={}, toTraceId={}",
                    restoredState.sessionId(), checkpoint.get().sourceTraceId(), restoredState.traceId());
            return restoredState;
        }
        return ReactAgentState.init(request, defaultBudget);
    }
    /** 根据 resumePolicy 决定是否忽略 checkpoint 强制新建状态 */
    private ReactAgentState initStateWithResumePolicy(AgentRequest request) {
        if (request.resumePolicy() == ResumePolicy.FRESH) {
            clearCheckpoint(request);
            return ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        }
        return initState(request);
    }

    /** 启动 Trace 追踪（如果配置了 traceRecorder） */
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    /** 结束 Trace 追踪并记录最终输出、成功状态等信息 */
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
    /** 尝试获取当前 session 的 checkpoint 用于恢复（fingerprint 用于区分同一 session 的不同对话分支） */
    private Optional<AgentCheckpoint> claimCheckpoint(AgentRequest request) {
        if (!checkpointEnabled()) {
            return Optional.empty();
        }
        try {
            return checkpointStore.claim(request.sessionId(), request.channel(), fingerprintOf(request));
        } catch (Exception e) {
            log.warn("获取 Agent checkpoint 失败：sessionId={}, error={}", request.sessionId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 保存当前状态到 checkpoint，便于后续意外中断时恢复 */
    private void saveCheckpoint(ReactAgentState state, AgentRequest request) {
        if (!checkpointEnabled() || !shouldPersistCheckpoint(state)) {
            return;
        }
        try {
            checkpointStore.save(AgentCheckpoint.from(state, fingerprintOf(request), objectMapper));
        } catch (Exception e) {
            log.warn("保存 Agent checkpoint 失败：sessionId={}, traceId={}, error={}",
                    state.sessionId(), state.traceId(), e.getMessage());
        }
    }

    /** 清理 L1 工作区中的执行进度快照。 */
    private void cleanupWorkspaceProgress(ReactAgentState state) {
        if (workspaceService == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return;
        }
        try {
            workspaceService.resolveBySourceTraceId(state.sessionId(), state.traceId());
        } catch (Exception e) {
            log.debug("清理工作区执行进度失败: error={}", e.getMessage());
        }
    }

    /** 删除指定 session 的 checkpoint，通常在正常完成后调用以清理临时状态 */
    private void clearCheckpoint(AgentRequest request) {
        if (!checkpointEnabled()) {
            return;
        }
        try {
            checkpointStore.delete(request.sessionId(), request.channel(), fingerprintOf(request));
        } catch (Exception e) {
            log.warn("清除 Agent checkpoint 失败：sessionId={}, error={}", request.sessionId(), e.getMessage());
        }
    }

    /** 判断是否应该对异常进行降级处理而不是直接抛出 */
    private boolean shouldDegradeUnexpectedException(ReactAgentState state) {
        if (state.finalOutput() != null && !state.finalOutput().isBlank()) {
            return true;
        }
        return state.steps().stream().anyMatch(this::isSubstantiveStep);
    }

    private boolean isSubstantiveStep(ReactStep step) {
        return !(step instanceof ReactStep.Progress);
    }

    /** 构造一个可接受的降级状态，将异常转换为终止原因并保留已有输出 */
    private ReactAgentState degradeForException(ReactAgentState state, Exception e) {
        String reason = "执行中断 - " + e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            reason += " - " + e.getMessage();
        }
        return DegradedResponseBuilder.terminateWithReason(state, reason);
    }

    /** 判断是否应该保存 checkpoint：仅在降级模式且有实际步骤时保存 */
    private boolean shouldPersistCheckpoint(ReactAgentState state) {
        return state.completionMode() == CompletionMode.DEGRADED
                && state.terminationReason() != null
                && !state.terminationReason().isBlank()
                && state.stepCount() > 0;
    }

    /** 判断 checkpoint 功能是否启用 */
    private boolean checkpointEnabled() {
        return config.getCheckpoint().isEnabled() && checkpointStore != null;
    }

    /** 生成请求的 fingerprint，用于区分同一 session 中的不同对话分支 */
    private String fingerprintOf(AgentRequest request) {
        return AgentCheckpointFingerprinter.fingerprint(request);
    }

    /** 处理同步挂起场景，保存 workspace 和 suspendStore 并返回挂起响应 */
    private AgentResponse handleSuspendSync(ReactAgentState state,
                                            @Nullable TraceContext traceContext,
                                            AgentLoopContext loopContext) {
        executionPersistence.saveWorkspaceForSuspend(state);
        String suspendMessage = resolveSuspendMessage(state);
        var suspendedState = state.toBuilder()
                .finalOutput(suspendMessage)
                .terminationReason(resolveSuspendTerminationReason(state))
                .completionReason(CompletionReason.SUSPENDED)
                .completionMode(CompletionMode.SUSPENDED)
                .build();
        if (suspendStore != null) {
            suspendStore.save(SuspendedAgent.from(suspendedState, objectMapper));
            agentLoop.scheduleWakeupIfNeeded(suspendedState);
            log.info("Agent 被挂起并保存到存储：traceId={}, reasonType={}",
                    suspendedState.traceId(), suspendedState.suspendReason().getClass().getSimpleName());
        } else {
            log.warn("Agent 挂起但 SuspendStore 未配置，无法保存挂起状态：traceId={}", suspendedState.traceId());
        }
        String assistantEntryId = executionPersistence.persistAssistantSync(
                suspendedState,
                serializeReactStepsJson(suspendedState.steps()),
                loopContext);
        executionPersistence.markTurnCompleted(suspendedState, assistantEntryId, ChatTurnStatus.SUSPENDED);
        return buildAgentResponse(suspendedState, assistantEntryId, null, aggregateTokenUsage(traceContext));
    }

    /**
     * 处理流式挂起。
     *
     * <p>这里优先把"现在需要用户补充信息"的信号尽快发给前端，
     * 避免等 transcript/turn 落库全部完成后，输入框才恢复可用。
     * 对用户来说，挂起是一种交互切换，时机应优先于后台持久化。</p>
     */
    private void handleSuspendStreaming(ReactAgentState state, String streamId,
                                        SseSessionManager sseManager,
                                        AgentLoopContext loopContext) {
        executionPersistence.saveWorkspaceForSuspend(state);
        String suspendMessage = resolveSuspendMessage(state);
        var suspendedState = state.toBuilder()
                .finalOutput(suspendMessage)
                .terminationReason(resolveSuspendTerminationReason(state))
                .completionReason(CompletionReason.SUSPENDED)
                .completionMode(CompletionMode.SUSPENDED)
                .build();
        if (suspendStore != null) {
            var suspendedAgent = SuspendedAgent.from(suspendedState, objectMapper).toBuilder()
                    .streamId(streamId).build();
            suspendStore.save(suspendedAgent);
            agentLoop.scheduleWakeupIfNeeded(suspendedState);
            log.info("Agent 被挂起并保存到存储：traceId={}, reasonType={}, streamId={}",
                    suspendedState.traceId(), suspendedState.suspendReason().getClass().getSimpleName(), streamId);
        } else {
            log.warn("Agent 挂起但 SuspendStore 未配置，无法保存挂起状态：traceId={}", suspendedState.traceId());
        }

        // 先向前端发出挂起信号，让输入框立即恢复为"继续回复即可"的自然对话状态。
        sseManager.sendEvent(streamId, SseEventType.AGENT_SUSPENDED, buildSuspendedEventPayload(suspendedState, suspendMessage));
        sseManager.closeEmitter(streamId);

        // 挂起后的 transcript/turn 落库放在事件发送之后，避免用户先感知到"卡住"。
        try {
            String assistantEntryId = executionPersistence.persistAssistantStreaming(
                    suspendedState,
                    suspendMessage,
                    suspendedState.reasoningSummary(),
                    null,
                    serializeReactStepsJson(suspendedState.steps()),
                    loopContext);
            executionPersistence.markTurnCompleted(suspendedState, assistantEntryId, ChatTurnStatus.SUSPENDED);
        } catch (Exception e) {
            log.error("流式挂起后续持久化失败：sessionId={}, traceId={}, error={}",
                    suspendedState.sessionId(), suspendedState.traceId(), e.getMessage(), e);
        }
    }

    /** 构建 AGENT_SUSPENDED 事件载荷。 */
    private Map<String, Object> buildSuspendedEventPayload(ReactAgentState suspendedState, String suspendMessage) {
        var suspendedEvent = new HashMap<String, Object>();
        suspendedEvent.put("traceId", suspendedState.traceId());
        suspendedEvent.put("sessionId", suspendedState.sessionId());
        suspendedEvent.put("completionMode", CompletionMode.SUSPENDED);
        suspendedEvent.put("turnStatus", ChatTurnStatus.SUSPENDED);
        suspendedEvent.put("contentRole", OutputContentRole.SUSPEND_PROMPT.name());
        suspendedEvent.put("reasonType", suspendedState.suspendReason().getClass().getSimpleName());
        String reasonSourceId = resolveSuspendReasonSourceId(suspendedState.suspendReason());
        if (reasonSourceId != null && !reasonSourceId.isBlank()) {
            suspendedEvent.put("reasonSourceId", reasonSourceId);
        }
        suspendedEvent.put("reasonDetail", agentLoop.formatSuspendReason(suspendedState.suspendReason()));
        suspendedEvent.put("terminationReason", resolveSuspendTerminationReason(suspendedState));
        suspendedEvent.put("content", suspendMessage);
        suspendedEvent.put("suspendedAt", Instant.now().toString());
        if (suspendedState.turnId() != null && !suspendedState.turnId().isBlank()) {
            suspendedEvent.put("turnId", suspendedState.turnId());
        }
        return suspendedEvent;
    }

    /** 提取挂起原因的稳定标识，供前端区分"等待用户补充"这类接续模式。 */
    @Nullable
    private String resolveSuspendReasonSourceId(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait workflowWait -> workflowWait.executionId();
            case SuspendReason.UserConfirmation confirmation -> confirmation.confirmationId();
            case SuspendReason.RemoteDelegation remoteDelegation -> remoteDelegation.remoteTaskId();
            case SuspendReason.ScheduledWakeup _ -> null;
            case SuspendReason.ExternalDataWait externalDataWait -> externalDataWait.dataSourceId();
        };
    }

    /** 构造友好的挂起提示消息，告知用户当前缺少什么信息或条件 */
    private String resolveSuspendMessage(ReactAgentState state) {
        if (state.finalOutput() != null && !state.finalOutput().isBlank()) {
            return state.finalOutput().strip();
        }
        String reasonDetail = agentLoop.formatSuspendReason(state.suspendReason());
        if (reasonDetail == null || reasonDetail.isBlank()) {
            return "我还缺少继续处理所需的信息。你回复后，我会接着当前进度继续处理。";
        }
        return "当前任务暂时无法继续：" + reasonDetail + "。条件满足后我会从当前进度继续处理。";
    }

    /** 提取挂起终止原因的描述字符串，用于 transcript 和日志展示 */
    private String resolveSuspendTerminationReason(ReactAgentState state) {
        if (state.terminationReason() != null && !state.terminationReason().isBlank()) {
            return state.terminationReason();
        }
        return "suspended";
    }

    /** 构造推理摘要，汇总本轮执行的模型、步数、token 消耗等信息 */
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
        return "本轮推理已完成，使用模型 %s，经过 %d 个推理步骤，累计约 %d 个 Token。"
                .formatted(modelId, steps, tokens);
    }
    /** 将 ReAct 步骤序列化为 JSON 字符串，用于 transcript 存储 */
    @Nullable
    private String serializeReactStepsJson(@Nullable List<ReactStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        return ReactStepSerializer.serializeToJson(steps, objectMapper);
    }
    /** 从 trace 上下文中聚合 token 使用情况，包括输入输出 token 数和使用的模型 */
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

    /** 构建最终的 Agent 响应对象，包含输出、token 消耗、状态等信息 */
    private AgentResponse buildAgentResponse(ReactAgentState state,
                                             @Nullable String assistantEntryId,
                                             @Nullable List<com.lifepilot.interaction.web.model.A2uiComponent> a2uiComponents,
                                             @Nullable TokenUsage tokenUsage) {
        return new AgentResponse(
                state.traceId(),
                state.sessionId(),
                state.turnId(),
                state.taskMode(),
                state.finalOutput() != null ? state.finalOutput() : "",
                state.budget().tokensUsed(),
                state.stepCount(),
                state.terminationReason(),
                state.completionReason(),
                assistantEntryId,
                a2uiComponents,
                tokenUsage,
                state.completionMode(),
                state.resumedFromTraceId(),
                resolveTurnStatus(state)
        );
    }

    /** 根据 execution 状态决定 turn 的最终状态：success/degraded/suspended */
    private ChatTurnStatus resolveTurnStatus(ReactAgentState state) {
        if (state.suspended()) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (state.completionMode() == CompletionMode.DEGRADED
                || (state.terminationReason() != null && !state.terminationReason().isBlank())) {
            return ChatTurnStatus.DEGRADED;
        }
        return ChatTurnStatus.SUCCESS;
    }

    /** 包装流式传输中可重试的异常，便于上层捕获并重试 */
    public static final class RetryableStreamingException extends RuntimeException {

        public RetryableStreamingException(Throwable cause) {
            super(cause);
        }
    }
}
