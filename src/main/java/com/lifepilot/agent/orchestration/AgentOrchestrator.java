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
import com.lifepilot.agent.recovery.TaskRecoverySummaryBuilder;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.a2ui.UiEmitTreeCapture;
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
import com.lifepilot.memory.store.workspace.SessionWorkspaceService;
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
    @Nullable private final UiEmitTreeCapture uiEmitTreeCapture;
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
            @Nullable SessionWorkspaceService workspaceService,
            @Nullable UiEmitTreeCapture uiEmitTreeCapture) {
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
        this.uiEmitTreeCapture = uiEmitTreeCapture;
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
        Throwable error = null;
        var loopContext = new AgentLoopContext();

        var token = new CancellationToken();
        AgentRequest effectiveRequest = preprocessMedia(request, state, null, null);
        if (effectiveRequest == null) {
            // preprocessMedia 返回 null 表示媒体校验失败
            return AgentResponse.error(state, new MediaValidationException("媒体校验失败"));
        }

        try {
            // ── 阶段 1: 状态初始化 ─
            state = resolveInitState(effectiveRequest);
            var loopRequest = effectiveRequest;
            if (effectiveRequest.action() == ChatTurnAction.RESUME && !state.goal().equals(effectiveRequest.message())) {
                loopRequest = effectiveRequest.withMessage(state.goal());
            }

            // ── 阶段 2: Turn 绑定 ─
            executionPersistence.bindTurnTrace(state);
            boolean testSession = isTestSession(effectiveRequest.sessionId());
            executionPersistence.persistUserTurn(state, effectiveRequest);
            traceContext = startTraceIfEnabled(state, effectiveRequest);

            // ── 阶段 3: 执行 ReAct 循环 ─
            loopStart = Instant.now();
            var callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, loopRequest, agentLoop);
            state = agentLoop.coreLoop(state, loopRequest, traceContext, loopStart,
                    callback, token, loopContext);

            // ── 阶段 4: 挂起检测 ─
            cleanupWorkspaceProgress(state);
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                return handleSuspendSync(state, traceContext, loopContext);
            }

            // ── 阶段 5: 成功路径 — 正常/降级分流、持久化、响应构建 ─
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

            return buildAgentResponse(state, assistantEntryId, a2uiComponents, tokenUsage,
                    loopContext.getCollectedArtifactRefs());

        } catch (Throwable e) {
            // ── 阶段 6: 异常路径 — 可降级异常走 DEGRADED，不可恢复走 FAILED ─
            log.error("ReAct 循环执行失败：errorType={}, error={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            if (e instanceof Exception ex && shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, ex);
                saveCheckpoint(state, effectiveRequest);
                boolean testSession = isTestSession(effectiveRequest.sessionId());
                String assistantEntryId = null;
                if (!testSession) {
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                }
                executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                return buildAgentResponse(state, assistantEntryId, null, aggregateTokenUsage(traceContext),
                        loopContext.getCollectedArtifactRefs());
            }
            error = e;
            Exception wrapped = e instanceof Exception ex ? ex : new RuntimeException(e);
            executionPersistence.markTurnFailed(state, wrapped);
            return AgentResponse.error(state, wrapped);
        } finally {
            endTraceIfEnabled(traceContext, state, error);
        }
    }
    /**
     * 流式执行 Agent 并通过 SSE 推送事件。
     *
     * <p>适用于需要实时反馈的场景，会逐步发送思考过程、工具调用、最终答案等事件。</p>
     *
     * <h3>执行阶段</h3>
     * <ol>
     *   <li>初始化 — SSE emitter、loopContext、媒体预处理</li>
     *   <li>状态解析 — SuspendStore / checkpoint / 全新 init</li>
     *   <li>Turn 绑定 — trace、用户消息持久化、TRACE_START 事件</li>
     *   <li>ReAct 循环 — 核心推理 → 工具调用 → 观察循环</li>
     *   <li>挂起检测 — 工具返回的 _suspend 信号或 LLM await_user_input 协议</li>
     *   <li>流式错误检测 — callback 中累积的流式连接异常</li>
     *   <li>成功路径 — 提取最终内容、降级/正常分支、持久化 transcript</li>
     *   <li>异常路径 — 可降级异常走 DEGRADED、不可恢复走 FAILED</li>
     *   <li>终结事件 — 推送 DONE 或 ERROR SSE 事件、关闭 emitter</li>
     * </ol>
     */
    public void runStreaming(AgentRequest request, String streamId,
                             SseSessionManager sseManager,
                             CancellationToken cancellationToken) {
        ReactAgentState state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));
        TraceContext traceContext = null;
        Instant loopStart = Instant.now();
        Throwable error = null;
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
        AgentRequest effectiveRequest = preprocessMedia(request, state, sseManager, streamId);
        if (effectiveRequest == null) return;

        try {
            // ── 阶段 1: 状态初始化 ──────────────────────────────────
            // RESUME 操作优先从 SuspendStore 恢复完整状态，fallback 到 checkpoint → init
            state = resolveInitState(effectiveRequest);
            var loopRequest = effectiveRequest;
            if (effectiveRequest.action() == ChatTurnAction.RESUME && !state.goal().equals(effectiveRequest.message())) {
                loopRequest = effectiveRequest.withMessage(state.goal());
            }

            // ── 阶段 2: Turn 绑定与启动事件 ─────────────────────────
            // 将 turnId ↔ traceId 绑定，持久化用户消息，推送 TRACE_START + AGENT_START
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

            // ── 阶段 3: 执行 ReAct 循环 ────────────────────────────
            traceContext = startTraceIfEnabled(state, loopRequest);
            loopStart = Instant.now();
            var callback = new StreamingCallback(config, generationRouter, multimodalRouter, agentLoop,
                    cancellationToken, loopContext, sseManager, streamId,
                    request.sessionId(), tempTurnId, loopRequest);
            state = agentLoop.coreLoop(state, loopRequest, traceContext, loopStart,
                    callback, cancellationToken, loopContext);

            // ── 阶段 4: 挂起检测 ────────────────────────────────────
            cleanupWorkspaceProgress(state);
            if (cancellationToken.isCancelled()) {
                state = markStreamingCancelled(state, callback.getFinalContent());
                finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                finalTokenUsage = aggregateTokenUsage(traceContext);
                clearCheckpoint(effectiveRequest);
                if (!testSession) {
                    executionPersistence.markTurnCompleted(state, null, ChatTurnStatus.CANCELLED);
                }
                log.info("流式执行已按用户停止请求取消: sessionId={}, traceId={}, turnId={}",
                        state.sessionId(), state.traceId(), state.turnId());
                return;
            }
            if (state.suspended() && state.suspendReason() != null) {
                clearCheckpoint(effectiveRequest);
                handleSuspendStreaming(state, streamId, sseManager, loopContext, eventBuffer);
                return;
            }

            // ── 阶段 5: 流式错误检测 ────────────────────────────────
            // StreamingCallback 中累积的连接异常（EOF/网络中断等），在 coreLoop 结束后统一处理
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

            // ── 阶段 6: 成功路径 — 提取内容、降级/正常分流、持久化 ─
            if (error == null) {
                String callbackContent = callback.getFinalContent();
                finalContent = state.finalOutput() != null
                        ? state.finalOutput()
                        : (callbackContent != null ? callbackContent : finalContent);

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

                // 从 ui.render 工具捕获的组件树写回 loopContext（必须在 serialize 之前）
                if (uiEmitTreeCapture != null) {
                    var capturedTree = uiEmitTreeCapture.poll(streamId);
                    if (capturedTree != null) {
                        loopContext.setLastCollectedA2uiTree(capturedTree);
                    }
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
        } catch (Throwable e) {
            // ── 阶段 7: 异常路径 — 可降级异常走 DEGRADED，不可恢复走 FAILED ─
            log.error("流式执行 Agent 失败：errorType={}, error={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            if (e instanceof Exception ex && shouldDegradeUnexpectedException(state)) {
                state = degradeForException(state, ex);
                finalContent = state.finalOutput() != null ? state.finalOutput() : "";
                saveCheckpoint(state, effectiveRequest);
                // 从 ui.render 工具捕获的组件树写回 loopContext（必须在 serialize 之前）
                if (uiEmitTreeCapture != null) {
                    var capturedTree = uiEmitTreeCapture.poll(streamId);
                    if (capturedTree != null) {
                        loopContext.setLastCollectedA2uiTree(capturedTree);
                    }
                }
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
            // ── 阶段 8: 终结事件 — 推送 DONE 或 ERROR，关闭 SSE emitter ─
            endTraceIfEnabled(traceContext, state, error);

            // 兜底清理，防止泄漏（组件树已在 success/catch 路径中 poll 过，这里仅做安全移除）
            if (uiEmitTreeCapture != null) {
                uiEmitTreeCapture.poll(streamId); // 兜底清理，防止泄漏
            }

            if (error != null) {
                executionPersistence.markTurnFailed(state,
                        error instanceof Exception ex ? ex : new RuntimeException(error));
                if (eventBuffer != null && !eventBuffer.isClosed()) {
                    // 缓冲区模式：构建错误数据，通过 offerTerminal 排空后派发
                    var errorData = new HashMap<String, Object>();
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
                boolean cancelled = state.completionReason() == CompletionReason.CANCELLED;
                agentLoop.sendReasoningEvent(sseManager, streamId, request.sessionId(), tempTurnId,
                        cancelled ? "GENERATION_CANCELLED" : "ANSWER_FINALIZED",
                        cancelled ? "已停止生成" : "回答已完成",
                        cancelled ? "用户已停止本轮生成，正在发送 DONE 事件。"
                                : "流式输出已完成，正在发送 DONE 事件。",
                        null, Map.of(), eventBuffer);
                var doneData = streamingEventHandler.buildDoneEventPayload(
                        request, state, tempTurnId, finalTokenUsage,
                        state.steps(), reasoningSummary, finalContent, assistantEntryId,
                        loopContext.getLastCollectedA2uiTree(),
                        loopContext.streamingTimingsMs(),
                        loopContext.getCollectedArtifactRefs());
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

    /**
     * 硬终止挂起的 Agent — 不进入 ReAct 循环，直接落库 DEGRADED + 释放资源。
     *
     * <p>设计意图：用户在前端点"放弃任务"等显式取消场景下，跳过 LLM 重新解读"取消"
     * 这一无意义的来回。{@link #resumeFromSuspend} 是"继续推理"语义，而本方法是
     * "结束本轮"语义，二者不应混用同一入口。</p>
     *
     * <p>处理顺序：
     * <ol>
     *   <li>原子 loadAndDelete 取出挂起记录（防止与 resume 并发）</li>
     *   <li>恢复 state 并追加一条 Resume 步骤记录取消原因，便于审计</li>
     *   <li>用 {@link DegradedResponseBuilder#terminateWithReason} 构造降级输出</li>
     *   <li>BrowserTakeover 场景顺手关闭浏览器会话；非该原因或释放失败仅记日志</li>
     *   <li>持久化 assistant 消息并把 turn 标为 FAILED（无 CANCELLED 枚举时的最近近似）</li>
     * </ol>
     * 挂起记录不存在视为幂等无操作（前端可能重复点击放弃），仅 warn 不抛。</p>
     *
     * @param traceId 挂起记录的 traceId（与 SuspendStore 主键一致）
     * @param reason  取消原因，会写入 terminationReason 供 transcript 展示
     */
    public void cancelSuspendedAgent(String traceId, String reason) {
        if (suspendStore == null) {
            throw new IllegalStateException("SuspendStore 未配置，无法取消挂起的 Agent");
        }
        var optionalSuspended = suspendStore.loadAndDelete(traceId);
        if (optionalSuspended.isEmpty()) {
            log.warn("取消挂起 Agent 时未找到对应记录（可能已被 resume 或重复取消）：traceId={}", traceId);
            return;
        }
        SuspendedAgent suspended = optionalSuspended.get();

        ReactAgentState state = suspended.toAgentState(objectMapper).resume();
        Instant now = Instant.now();
        // 把取消原因记成 Resume + Observation 一对，保持步骤结构和正常 resume 一致
        var cancelPayload = new ResumePayload.BrowserTakeoverCompleted(
                suspended.suspendReason() instanceof SuspendReason.BrowserTakeover bt ? bt.sessionId() : null,
                ResumePayload.BrowserTakeoverCompleted.USER_CANCELLED_PREFIX + " " + reason);
        state = state.appendStep(new ReactStep.Resume(cancelPayload, now,
                Duration.between(suspended.suspendedAt(), now)));
        String resumeToolId = "resume:" + suspended.suspendReason().getClass().getSimpleName();
        state = state.appendStep(new ReactStep.Observation(
                resumeToolId, null, true, "用户取消任务: " + reason, 0, null));

        // 释放浏览器资源（如适用）— 失败不影响主流程，由 idle scheduler 兜底
        releaseBrowserSessionIfApplicable(suspended);

        // 走降级模板生成最终输出，CompletionMode = DEGRADED
        state = DegradedResponseBuilder.terminateWithReason(state, reason);

        boolean testSession = isTestSession(state.sessionId());
        String assistantEntryId = null;
        try {
            if (!testSession) {
                String reactStepsJson = serializeReactStepsJson(state.steps());
                assistantEntryId = executionPersistence.persistAssistantSync(
                        state, reactStepsJson, new AgentLoopContext());
            }
            executionPersistence.markTurnCompleted(state, assistantEntryId, ChatTurnStatus.CANCELLED);
            log.info("挂起 Agent 已硬终止：traceId={}, reasonType={}, reason={}",
                    traceId, suspended.suspendReason().getClass().getSimpleName(), reason);
        } catch (Exception e) {
            log.error("硬终止挂起 Agent 时持久化失败：traceId={}, error={}", traceId, e.getMessage(), e);
        }
    }

    /**
     * 浏览器接管挂起场景下尝试关闭对应 Page，避免会话残留。
     *
     * <p>BrowserSessionManager 当前没有注入到 orchestrator（避免循环依赖 + 保持模块解耦），
     * 故依赖反射 / 不直接调用；这里仅在 reason 是 BrowserTakeover 时记录上下文，
     * 实际清理交给 {@code BrowserSessionScheduler} 的 idle 兜底机制。
     * 后续若发现 idle 兜底不够及时，再补 BrowserSessionManager 注入。</p>
     */
    private void releaseBrowserSessionIfApplicable(SuspendedAgent suspended) {
        if (!(suspended.suspendReason() instanceof SuspendReason.BrowserTakeover bt)) {
            return;
        }
        // 仅打日志，实际 closePage 等 idle scheduler 兜底，避免在 orchestrator 注入 meta 层依赖
        log.info("挂起 Agent 取消，浏览器会话将由 idle scheduler 回收：sessionId={}", bt.sessionId());
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
            // 兜底分支 — 主路径已切到 cancelSuspendedAgent。这里保留前缀检测仅在
            // 历史调用方仍走 resumeFromSuspend + USER_CANCELLED 前缀时生效，
            // 防止意外路径把"取消"信号送进 LLM 推理循环。
            ResumePayload.BrowserTakeoverCompleted cancelPayload = detectUserCancelledPayload(state);
            if (cancelPayload != null) {
                log.info("Resume 入口检测到取消前缀（兜底分支），Agent 直接终止：traceId={}, note={}",
                        state.traceId(), cancelPayload.note());
                state = DegradedResponseBuilder.terminateWithReason(state, cancelPayload.note());
                boolean testSession = isTestSession(state.sessionId());
                if (!testSession) {
                    String reactStepsJson = serializeReactStepsJson(state.steps());
                    String assistantEntryId = executionPersistence.persistAssistantSync(state, reactStepsJson, loopContext);
                    // terminateWithReason 已设置 terminationReason，resolveTurnStatus 会判为 DEGRADED
                    executionPersistence.markTurnCompleted(state, assistantEntryId, resolveTurnStatus(state));
                }
                return;
            }

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
                    state.disabledToolIds(),
                    null,
                    null,
                    ResumePolicy.AUTO,
                    state.overrideKnowledgeBaseIds(),
                    state.memoryContextMode(),
                    state.turnRecoveryContext()
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
                    request.disabledToolIds(),
                    processedMedia,
                    request.temperature(),
                    request.resumePolicy(),
                    request.overrideKnowledgeBaseIds(),
                    request.memoryContextMode(),
                    request.turnRecoveryContext()
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

    /**
     * 解析初始状态：RESUME 操作优先从 SuspendStore 恢复完整执行状态，
     * 未命中时 fallback 到 checkpoint → init。
     *
     * <p>这是聊天"继续"路径与事件驱动恢复路径的统一入口。</p>
     */
    private ReactAgentState resolveInitState(AgentRequest request) {
        if (request.action() == ChatTurnAction.RESUME) {
            var restored = tryRestoreFromSuspend(request);
            if (restored.isPresent()) {
                return restored.get();
            }
        }
        return initStateWithResumePolicy(request);
    }

    /**
     * 从 SuspendStore 恢复 Agent 执行状态。
     *
     * <p>原子加载并删除挂起快照，重建完整状态（步骤历史、预算、已发现工具、
     * 已加载技能等），并将用户的新消息追加为观察步骤。</p>
     *
     * @param request 当前请求（含用户的新"继续"消息）
     * @return 恢复后的状态，未找到挂起记录时返回 empty
     */
    private Optional<ReactAgentState> tryRestoreFromSuspend(AgentRequest request) {
        if (suspendStore == null) {
            return Optional.empty();
        }
        var suspended = suspendStore.loadAndDeleteBySession(request.sessionId(), request.channel());
        if (suspended.isEmpty()) {
            return Optional.empty();
        }
        var saved = suspended.get();
        var restoredState = saved.toAgentState(objectMapper);
        String oldTraceId = restoredState.traceId();

        var builder = restoredState.toBuilder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(request.sessionId())
                .turnId(request.turnId())
                .source(request.source())
                .parentTraceId(oldTraceId)
                .resumedFromTraceId(oldTraceId)
                .turnRecoveryContext(request.turnRecoveryContext() != null
                        ? request.turnRecoveryContext()
                        : restoredState.turnRecoveryContext())
                .preferredProvider(
                        request.preferredProvider() != null
                                ? request.preferredProvider()
                                : restoredState.preferredProvider())
                .allowedToolIds(
                        request.allowedToolIds() != null
                                ? request.allowedToolIds()
                                : restoredState.allowedToolIds())
                .disabledToolIds(
                        request.disabledToolIds() != null
                                ? request.disabledToolIds()
                                : restoredState.disabledToolIds())
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .reasoningSummary(null)
                .suspended(false)
                .suspendReason(null)
                .completionMode(CompletionMode.NORMAL);

        // 将用户的新消息追加为观察步骤，让 LLM 感知到继续指令
        if (request.message() != null && !request.message().isBlank()) {
            var state = builder.build();
            state = state.appendStep(new ReactStep.Observation(
                    "user_continue", null, true,
                    "用户继续: " + request.message(), 0, null));
            builder = state.toBuilder();
        }

        var state = builder.build();
        log.info("从 SuspendStore 恢复 Agent 状态: sessionId={}, oldTraceId={}, newTraceId={}, stepCount={}",
                state.sessionId(), oldTraceId, state.traceId(), state.stepCount());
        return Optional.of(state);
    }

    /** 启动 Trace 追踪（如果配置了 traceRecorder） */
    @Nullable
    private TraceContext startTraceIfEnabled(ReactAgentState state, AgentRequest request) {
        if (traceRecorder == null) return null;
        return traceRecorder.startTrace(state.traceId(), state.sessionId(), request.message());
    }

    /** 结束 Trace 追踪并记录最终输出、成功状态等信息 */
    private void endTraceIfEnabled(@Nullable TraceContext traceContext,
                                   ReactAgentState state, @Nullable Throwable error) {
        if (traceRecorder == null || traceContext == null) return;
        String finalOutput = state.finalOutput();
        boolean success = error == null
                && state.terminationReason() == null
                && state.completionReason() != CompletionReason.CANCELLED;
        String errorMessage = error != null ? error.getMessage() : null;
        String terminationReason = error != null
                ? error.getClass().getSimpleName() : state.terminationReason();
        traceRecorder.endTrace(traceContext, finalOutput, success, errorMessage, terminationReason);
    }

    /** 构造用户主动停止后的终态，避免误记 SUCCESS 或触发助手后处理。 */
    private ReactAgentState markStreamingCancelled(ReactAgentState state, @Nullable String partialContent) {
        String content = partialContent != null ? partialContent.strip() : "";
        return state.toBuilder()
                .done(true)
                .finalOutput(content)
                .terminationReason("用户主动停止生成")
                .completionReason(CompletionReason.CANCELLED)
                .completionMode(CompletionMode.NORMAL)
                .build();
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
        return buildAgentResponse(suspendedState, assistantEntryId, null, aggregateTokenUsage(traceContext),
                loopContext.getCollectedArtifactRefs());
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
                                        AgentLoopContext loopContext,
                                        @Nullable SseEventBuffer eventBuffer) {
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
        // 挂起事件作为终结事件走缓冲区排空，确保前序 TOKEN 事件不丢失。
        if (eventBuffer != null && !eventBuffer.isClosed()) {
            eventBuffer.offerTerminal(SseEventType.AGENT_SUSPENDED, buildSuspendedEventPayload(suspendedState, suspendMessage));
        } else {
            sseManager.sendEvent(streamId, SseEventType.AGENT_SUSPENDED, buildSuspendedEventPayload(suspendedState, suspendMessage));
            sseManager.closeEmitter(streamId);
        }

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
        // BrowserTakeover 场景下把后端配置的挂起超时秒数带到事件里，让前端弹窗读取统一值而非本地硬编码。
        if (suspendedState.suspendReason() instanceof SuspendReason.BrowserTakeover browserTakeover
                && browserTakeover.timeoutSeconds() != null) {
            suspendedEvent.put("timeoutSeconds", browserTakeover.timeoutSeconds());
        }
        String reasonDetail = agentLoop.formatSuspendReason(suspendedState.suspendReason());
        suspendedEvent.put("reasonDetail", reasonDetail);
        suspendedEvent.put("taskRecovery", TaskRecoverySummaryBuilder.fromSuspendReason(
                suspendedState.suspendReason(), reasonDetail));
        Map<String, Object> executionConstraints = ExecutionConstraintSummarySupport.from(suspendedState);
        if (!executionConstraints.isEmpty()) {
            suspendedEvent.put("executionConstraints", executionConstraints);
        }
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
            case SuspendReason.BrowserTakeover browserTakeover -> browserTakeover.sessionId();
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
        return buildAgentResponse(state, assistantEntryId, a2uiComponents, tokenUsage, java.util.List.of());
    }

    /** 构建最终的 Agent 响应对象，含本轮工具产生的文件产物引用列表。 */
    private AgentResponse buildAgentResponse(ReactAgentState state,
                                             @Nullable String assistantEntryId,
                                             @Nullable List<com.lifepilot.interaction.web.model.A2uiComponent> a2uiComponents,
                                             @Nullable TokenUsage tokenUsage,
                                             java.util.List<com.lifepilot.interaction.model.ArtifactRef> artifactRefs) {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSteps(state.steps(), artifactRefs);
        var taskRecovery = TaskRecoverySummaryBuilder.fromState(state, toolSummaries).orElse(null);
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
                resolveTurnStatus(state),
                artifactRefs,
                toolSummaries,
                taskRecovery
        );
    }

    /**
     * 从已追加 Resume 步骤的 state 里检测"用户取消"信号 — 兜底分支，
     * 用户显式取消的主路径走 {@link #cancelSuspendedAgent(String, String)}。
     *
     * <p>扫描 steps 尾部最近一个 Resume step，若其 payload 是
     * {@link ResumePayload.BrowserTakeoverCompleted} 且 note 带 USER_CANCELLED 前缀，
     * 则返回该 payload；否则返回 null。仅作历史调用方意外仍走前缀语义时的保护网。</p>
     *
     * @return 取消 payload，未命中时 null
     */
    @Nullable
    private ResumePayload.BrowserTakeoverCompleted detectUserCancelledPayload(ReactAgentState state) {
        var steps = state.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.Resume resume) {
                if (resume.payload() instanceof ResumePayload.BrowserTakeoverCompleted c && c.isUserCancelled()) {
                    return c;
                }
                // 找到最近一个 Resume 就停止，避免往前找到无关的旧 Resume
                return null;
            }
        }
        return null;
    }

    /** 根据 execution 状态决定 turn 的最终状态：success/degraded/suspended */
    private ChatTurnStatus resolveTurnStatus(ReactAgentState state) {
        if (state.completionReason() == CompletionReason.CANCELLED) {
            return ChatTurnStatus.CANCELLED;
        }
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
