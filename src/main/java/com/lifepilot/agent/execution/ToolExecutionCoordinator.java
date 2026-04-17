package com.lifepilot.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkingSetItem;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolSchedulingResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 工具执行协调器。
 *
 * <p>负责单轮 tool call 的规划、波次执行、媒体提取、挂起解析、transcript 持久化和 trace 记录，
 * 将 ReAct 主循环中的工具执行细节收敛到独立组件。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public class ToolExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionCoordinator.class);
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual().start(command);

    private final AgentToolProvider agentToolProvider;
    private final ObjectMapper objectMapper;
    @Nullable
    private final TraceRecorder traceRecorder;
    @Nullable
    private final TranscriptStore transcriptStore;
    @Nullable
    private final MediaDataExtractor mediaDataExtractor;
    @Nullable
    private final ProceduralMemory proceduralMemory;
    @Nullable
    private final IntentMatcher intentMatcher;
    @Nullable
    private final MultimodalRouter multimodalRouter;
    @Nullable
    private final SessionWorkspaceService workspaceService;
    private final int maxParallelToolCalls;

    /** 值得持久化到工作区的工具 ID 集合（写操作或产生结构化结果的工具）。 */
    private static final Set<String> WORKSPACE_WORTHY_TOOLS = Set.of(
            "memory.create", "memory.update", "memory.tag",
            "workflow.execute", "code.execute", "datastore.query");

    public ToolExecutionCoordinator(AgentToolProvider agentToolProvider,
                                    ObjectMapper objectMapper,
                                    @Nullable TraceRecorder traceRecorder,
                                    @Nullable TranscriptStore transcriptStore,
                                    @Nullable MediaDataExtractor mediaDataExtractor,
                                    @Nullable ProceduralMemory proceduralMemory,
                                    @Nullable IntentMatcher intentMatcher) {
        this(agentToolProvider, objectMapper, traceRecorder, transcriptStore,
                mediaDataExtractor, proceduralMemory, intentMatcher, 4, null, null);
    }

    public ToolExecutionCoordinator(AgentToolProvider agentToolProvider,
                                    ObjectMapper objectMapper,
                                    @Nullable TraceRecorder traceRecorder,
                                    @Nullable TranscriptStore transcriptStore,
                                    @Nullable MediaDataExtractor mediaDataExtractor,
                                    @Nullable ProceduralMemory proceduralMemory,
                                    @Nullable IntentMatcher intentMatcher,
                                    int maxParallelToolCalls) {
        this(agentToolProvider, objectMapper, traceRecorder, transcriptStore,
                mediaDataExtractor, proceduralMemory, intentMatcher, maxParallelToolCalls, null, null);
    }

    public ToolExecutionCoordinator(AgentToolProvider agentToolProvider,
                                    ObjectMapper objectMapper,
                                    @Nullable TraceRecorder traceRecorder,
                                    @Nullable TranscriptStore transcriptStore,
                                    @Nullable MediaDataExtractor mediaDataExtractor,
                                    @Nullable ProceduralMemory proceduralMemory,
                                    @Nullable IntentMatcher intentMatcher,
                                    int maxParallelToolCalls,
                                    @Nullable MultimodalRouter multimodalRouter) {
        this(agentToolProvider, objectMapper, traceRecorder, transcriptStore,
                mediaDataExtractor, proceduralMemory, intentMatcher, maxParallelToolCalls,
                multimodalRouter, null);
    }

    public ToolExecutionCoordinator(AgentToolProvider agentToolProvider,
                                    ObjectMapper objectMapper,
                                    @Nullable TraceRecorder traceRecorder,
                                    @Nullable TranscriptStore transcriptStore,
                                    @Nullable MediaDataExtractor mediaDataExtractor,
                                    @Nullable ProceduralMemory proceduralMemory,
                                    @Nullable IntentMatcher intentMatcher,
                                    int maxParallelToolCalls,
                                    @Nullable MultimodalRouter multimodalRouter,
                                    @Nullable SessionWorkspaceService workspaceService) {
        this.agentToolProvider = agentToolProvider;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.transcriptStore = transcriptStore;
        this.mediaDataExtractor = mediaDataExtractor;
        this.proceduralMemory = proceduralMemory;
        this.intentMatcher = intentMatcher;
        this.maxParallelToolCalls = Math.max(1, maxParallelToolCalls);
        this.multimodalRouter = multimodalRouter;
        this.workspaceService = workspaceService;
    }

    /**
     * 执行单次 tool call，并将其副作用统一回写到 state、trace 和 transcript。
     *
     * <p>兼容旧入口，内部统一走批次执行路径。</p>
     */
    public ReactAgentState execute(ReactAgentState state,
                                   AssistantMessage.ToolCall toolCall,
                                   List<ToolCallback> toolCallbacks,
                                   @Nullable TraceContext traceContext,
                                   CancellationToken cancellationToken,
                                   AgentLoopContext loopContext,
                                   StepAppender stepAppender) {
        return executeBatch(
                state,
                List.of(toolCall),
                toolCallbacks,
                traceContext,
                cancellationToken,
                loopContext,
                stepAppender
        );
    }

    /**
     * 执行一轮 tool call 批次。
     *
     * <p>先按调度提示切分为稳定波次，再逐波次执行。波次内可并发，波次间保持串行，
     * 并且所有结果都按模型原始 tool call 顺序回放到 state。</p>
     */
    public ReactAgentState executeBatch(ReactAgentState state,
                                        List<AssistantMessage.ToolCall> toolCalls,
                                        List<ToolCallback> toolCallbacks,
                                        @Nullable TraceContext traceContext,
                                        CancellationToken cancellationToken,
                                        AgentLoopContext loopContext,
                                        StepAppender stepAppender) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return state;
        }
        if (cancellationToken.isCancelled()) {
            log.info("工具批次执行前检测到取消信号: count={}", toolCalls.size());
            return state;
        }

        Map<String, ToolCallback> callbackIndex = indexToolCallbacks(toolCallbacks);
        List<PlannedToolCall> plannedToolCalls = planToolCalls(toolCalls, callbackIndex);
        List<ToolExecutionWave> waves = planWaves(plannedToolCalls);
        log.debug("工具波次规划完成: totalCalls={}, waveCount={}, waves={}",
                plannedToolCalls.size(), waves.size(), summarizeWaves(waves));

        for (int waveIndex = 0; waveIndex < waves.size(); waveIndex++) {
            ToolExecutionWave wave = waves.get(waveIndex);
            if (cancellationToken.isCancelled()) {
                log.info("工具波次执行前检测到取消信号: index={}, size={}", waveIndex, wave.toolCalls().size());
                break;
            }

            log.debug("开始执行工具波次: index={}, size={}, tools={}",
                    waveIndex, wave.toolCalls().size(), summarizeWave(wave));
            state = appendWaveToolCalls(state, wave, loopContext, stepAppender);
            List<ToolExecutionOutcome> outcomes = executeWave(wave);
            state = replayWaveResults(state, outcomes, traceContext, loopContext, stepAppender);

            if (state.suspended()) {
                break;
            }
        }
        return state;
    }

    private Map<String, ToolCallback> indexToolCallbacks(List<ToolCallback> toolCallbacks) {
        var index = new LinkedHashMap<String, ToolCallback>();
        for (ToolCallback callback : toolCallbacks) {
            if (callback == null) {
                continue;
            }
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || definition.name() == null || definition.name().isBlank()) {
                continue;
            }
            index.putIfAbsent(definition.name(), callback);

            String canonicalToolId = agentToolProvider.resolveCanonicalToolId(definition.name());
            if (canonicalToolId != null && !canonicalToolId.isBlank()) {
                index.putIfAbsent(canonicalToolId, callback);
            }
        }
        return Map.copyOf(index);
    }

    private List<PlannedToolCall> planToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                                Map<String, ToolCallback> callbackIndex) {
        var planned = new ArrayList<PlannedToolCall>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);
            String modelToolName = toolCall.name();
            String toolId = agentToolProvider.resolveCanonicalToolId(modelToolName);
            if (toolId == null || toolId.isBlank()) {
                toolId = modelToolName;
            }
            String inputJson = toolCall.arguments();
            String toolDisplayName = agentToolProvider.resolveToolDisplayName(toolId);
            RiskLevel toolRiskLevel = agentToolProvider.resolveToolRiskLevel(toolId);
            AgentToolProvider.ToolSchedulingHint schedulingHint =
                    normalizeSchedulingHint(agentToolProvider.resolveSchedulingHint(toolId, inputJson));
            ToolCallback matchedCallback = callbackIndex.get(modelToolName);
            if (matchedCallback == null) {
                matchedCallback = callbackIndex.get(toolId);
            }
            if (matchedCallback == null) {
                schedulingHint = AgentToolProvider.ToolSchedulingHint.sequential();
            }
            planned.add(new PlannedToolCall(
                    i,
                    toolCall,
                    toolId,
                    inputJson,
                    toolDisplayName,
                    toolRiskLevel,
                    schedulingHint.mode(),
                    schedulingHint.resourceKeys(),
                    matchedCallback
            ));
        }
        return List.copyOf(planned);
    }

    private AgentToolProvider.ToolSchedulingHint normalizeSchedulingHint(
            @Nullable AgentToolProvider.ToolSchedulingHint schedulingHint) {
        if (schedulingHint == null || schedulingHint.mode() == null) {
            return AgentToolProvider.ToolSchedulingHint.sequential();
        }
        if (schedulingHint.mode() == ToolSchedulingMode.RESOURCE_SERIALIZED
                && schedulingHint.resourceKeys().isEmpty()) {
            return AgentToolProvider.ToolSchedulingHint.sequential();
        }
        return schedulingHint;
    }

    private List<ToolExecutionWave> planWaves(List<PlannedToolCall> plannedToolCalls) {
        var waves = new ArrayList<ToolExecutionWave>();
        var currentWaveCalls = new ArrayList<PlannedToolCall>();

        for (PlannedToolCall planned : plannedToolCalls) {
            if (planned.schedulingMode() == ToolSchedulingMode.SEQUENTIAL) {
                flushWave(waves, currentWaveCalls);
                waves.add(new ToolExecutionWave(List.of(planned)));
                continue;
            }

            if (!currentWaveCalls.isEmpty() && shouldSplitWave(currentWaveCalls, planned)) {
                flushWave(waves, currentWaveCalls);
            }

            currentWaveCalls.add(planned);
        }

        flushWave(waves, currentWaveCalls);
        return List.copyOf(waves);
    }

    private boolean shouldSplitWave(List<PlannedToolCall> currentWaveCalls,
                                    PlannedToolCall nextCall) {
        if (currentWaveCalls.size() >= maxParallelToolCalls) {
            return true;
        }
        return hasResourceConflict(currentWaveCalls, nextCall);
    }

    private void flushWave(List<ToolExecutionWave> waves, List<PlannedToolCall> currentWaveCalls) {
        if (currentWaveCalls.isEmpty()) {
            return;
        }
        waves.add(new ToolExecutionWave(List.copyOf(currentWaveCalls)));
        currentWaveCalls.clear();
    }

    private boolean hasResourceConflict(List<PlannedToolCall> currentWaveCalls, PlannedToolCall nextCall) {
        if (nextCall.schedulingMode() != ToolSchedulingMode.RESOURCE_SERIALIZED || nextCall.resourceKeys().isEmpty()) {
            return false;
        }
        for (PlannedToolCall current : currentWaveCalls) {
            if (current.schedulingMode() != ToolSchedulingMode.RESOURCE_SERIALIZED || current.resourceKeys().isEmpty()) {
                continue;
            }
            if (resourceSetsConflict(current.resourceKeys(), nextCall.resourceKeys())) {
                return true;
            }
        }
        return false;
    }

    private boolean resourceSetsConflict(List<String> currentResources, List<String> nextResources) {
        for (String currentResource : currentResources) {
            for (String nextResource : nextResources) {
                if (ToolSchedulingResources.conflicts(currentResource, nextResource)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String summarizeWaves(List<ToolExecutionWave> waves) {
        if (waves.isEmpty()) {
            return "[]";
        }
        var parts = new ArrayList<String>(waves.size());
        for (int i = 0; i < waves.size(); i++) {
            parts.add("wave[" + i + "]=" + summarizeWave(waves.get(i)));
        }
        return parts.toString();
    }

    private String summarizeWave(ToolExecutionWave wave) {
        return "[" + String.join(", ", wave.toolCalls().stream()
                .map(this::summarizePlannedToolCall)
                .toList()) + "]";
    }

    private String summarizePlannedToolCall(PlannedToolCall planned) {
        return switch (planned.schedulingMode()) {
            case SEQUENTIAL -> planned.toolId() + "[seq]";
            case PARALLEL_SAFE -> planned.toolId() + "[parallel]";
            case RESOURCE_SERIALIZED -> planned.toolId() + "[resource:" + summarizeResources(planned.resourceKeys()) + "]";
        };
    }

    private String summarizeResources(List<String> resourceKeys) {
        if (resourceKeys == null || resourceKeys.isEmpty()) {
            return "?";
        }
        return resourceKeys.stream()
                .map(this::summarizeResourceKey)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String summarizeResourceKey(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return "?";
        }
        int separator = resourceKey.indexOf(':');
        if (separator <= 0 || separator == resourceKey.length() - 1) {
            return resourceKey;
        }
        String type = resourceKey.substring(0, separator);
        String value = resourceKey.substring(separator + 1);
        return switch (type) {
            case "path" -> "path=" + value;
            case "tree" -> "tree=" + value;
            case "workspace" -> "workspace=" + value;
            case "origin" -> "origin=" + value;
            default -> type + "=" + value;
        };
    }

    private ReactAgentState appendWaveToolCalls(ReactAgentState state,
                                                ToolExecutionWave wave,
                                                AgentLoopContext loopContext,
                                                StepAppender stepAppender) {
        for (PlannedToolCall planned : wave.toolCalls()) {
            Instant toolCallCreatedAt = Instant.now();
            state = stepAppender.append(state, new ReactStep.ToolCall(
                    planned.toolId(),
                    planned.toolDisplayName(),
                    planned.inputJson(),
                    0,
                    planned.toolCall().id()
            ), loopContext);
            persistTranscriptToolCall(
                    state,
                    planned.toolCall(),
                    planned.toolId(),
                    planned.toolDisplayName(),
                    planned.inputJson(),
                    toolCallCreatedAt
            );
        }
        return state;
    }

    private List<ToolExecutionOutcome> executeWave(ToolExecutionWave wave) {
        if (wave.toolCalls().size() == 1) {
            return List.of(executePlannedCall(wave.toolCalls().get(0)));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ToolExecutionOutcome>> futures = wave.toolCalls().stream()
                    .map(planned -> CompletableFuture.supplyAsync(() -> executePlannedCall(planned), executor))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private ToolExecutionOutcome executePlannedCall(PlannedToolCall planned) {
        Instant toolCallStart = Instant.now();

        if (planned.matchedCallback() == null) {
            String errorOutput = "工具未注册: " + planned.toolId();
            return new ToolExecutionOutcome(
                    planned,
                    toolCallStart,
                    toolCallStart,
                    Duration.ZERO,
                    false,
                    errorOutput,
                    errorOutput,
                    estimateTextTokens(errorOutput),
                    null,
                    List.of()
            );
        }

        String rawOutput;
        boolean success;
        try {
            rawOutput = planned.matchedCallback().call(planned.inputJson());
            success = inferToolExecutionSuccess(rawOutput);
        } catch (Throwable e) {
            // 捕获 Throwable — 防止 Error 级别异常导致 ReAct 循环静默终止
            log.error("工具执行失败: toolId={}, errorType={}, error={}",
                    planned.toolId(), e.getClass().getSimpleName(), e.getMessage(), e);
            rawOutput = "工具执行异常: " + e.getMessage();
            success = false;
        }

        Instant completedAt = Instant.now();
        Duration toolCallDuration = Duration.between(toolCallStart, completedAt);

        if (success) {
            SuspendReason suspendReason = parseSuspendReasonFromOutput(rawOutput);
            if (suspendReason != null) {
                return new ToolExecutionOutcome(
                        planned,
                        toolCallStart,
                        completedAt,
                        toolCallDuration,
                        true,
                        rawOutput,
                        "工具请求挂起: " + suspendReason,
                        0,
                        suspendReason,
                        List.of()
                );
            }
        }

        String observationOutput = rawOutput;
        List<MediaDataExtractor.MediaItem> mediaItems = List.of();
        if (success && mediaDataExtractor != null) {
            var extraction = mediaDataExtractor.extract(planned.toolId(), rawOutput);
            observationOutput = extraction.sanitizedOutput();
            mediaItems = extraction.mediaItems();
        }

        return new ToolExecutionOutcome(
                planned,
                toolCallStart,
                completedAt,
                toolCallDuration,
                success,
                rawOutput,
                observationOutput != null ? observationOutput : "",
                estimateTextTokens(observationOutput != null ? observationOutput : ""),
                null,
                mediaItems != null ? List.copyOf(mediaItems) : List.of()
        );
    }

    private ReactAgentState replayWaveResults(ReactAgentState state,
                                              List<ToolExecutionOutcome> outcomes,
                                              @Nullable TraceContext traceContext,
                                              AgentLoopContext loopContext,
                                              StepAppender stepAppender) {
        for (ToolExecutionOutcome outcome : outcomes) {
            state = replayOutcome(state, outcome, traceContext, loopContext, stepAppender);
        }
        return state;
    }

    private ReactAgentState replayOutcome(ReactAgentState state,
                                          ToolExecutionOutcome outcome,
                                          @Nullable TraceContext traceContext,
                                          AgentLoopContext loopContext,
                                          StepAppender stepAppender) {
        PlannedToolCall planned = outcome.planned();

        if (outcome.success() && outcome.suspendReason() != null) {
            log.info("工具请求挂起: toolId={}, reason={}", planned.toolId(), outcome.suspendReason());
            if (!state.suspended()) {
                state = state.suspend(outcome.suspendReason());
            }
            state = stepAppender.append(state, new ReactStep.Observation(
                    planned.toolId(),
                    planned.toolDisplayName(),
                    true,
                    outcome.observationOutput(),
                    0,
                    planned.toolCall().id()
            ), loopContext);
            persistTranscriptToolResult(state, planned.toolCall(), planned.toolId(),
                    true, outcome.rawOutput(), null, outcome.startedAt());
            recordToolCallStep(traceContext, state.stepCount() - 1, outcome.startedAt(),
                    outcome.completedAt(), outcome.duration(), planned.toolId(),
                    planned.inputJson(), outcome.rawOutput(), true, planned.toolRiskLevel());
            log.debug("工具执行完成: toolId={}, success={}, latencyMs={}",
                    planned.toolId(), true, outcome.duration().toMillis());
            return state;
        }

        if (outcome.success() && !outcome.mediaItems().isEmpty()) {
            state = replayExtractedMedia(state, planned.toolId(), outcome.mediaItems(), loopContext);
        }

        // 无 VISION Provider 时修改 observation 文本，引导 Agent 使用纯文本工具
        String observationOutput = outcome.observationOutput();
        if (!outcome.mediaItems().isEmpty()
                && (multimodalRouter == null || !multimodalRouter.isVisionAvailable())) {
            observationOutput = observationOutput.replace(
                    MediaDataExtractor.PLACEHOLDER,
                    MediaDataExtractor.NO_VISION_PLACEHOLDER);
        }

        // Observation.output 始终保持工具原始输出 — 经验提示等装饰文本由呈现层
        // （ProviderMessageBuilder + ToolTipResolver）在构造 LLM 消息时动态拼接，
        // 避免污染 JSON 结构，影响 Skill 激活 / 审计 / trace 回放等下游解析。
        state = stepAppender.append(state, new ReactStep.Observation(
                planned.toolId(),
                planned.toolDisplayName(),
                outcome.success(),
                observationOutput,
                outcome.observationTokens(),
                planned.toolCall().id()
        ), loopContext);
        persistTranscriptToolResult(state, planned.toolCall(), planned.toolId(),
                outcome.success(), outcome.rawOutput(), null, outcome.startedAt());

        // L4 程序记忆：异步记录意图匹配，不阻塞主链路
        if (outcome.success() && proceduralMemory != null && intentMatcher != null) {
            String intentQuery = buildIntentMatchQuery(planned.toolId(), planned.inputJson());
            Thread.startVirtualThread(() -> {
                try {
                    var match = intentMatcher.match(intentQuery);
                    match.ifPresent(m -> proceduralMemory.recordExecution(m.template().templateId(), true));
                } catch (Exception e) {
                    log.warn("L4 执行结果记录失败: toolId={}, error={}", planned.toolId(), e.getMessage());
                }
            });
        }

        recordToolCallStep(traceContext, state.stepCount() - 1, outcome.startedAt(),
                outcome.completedAt(), outcome.duration(), planned.toolId(),
                planned.inputJson(), outcome.rawOutput(), outcome.success(), planned.toolRiskLevel());

        // 8. L1 工作区：关键工具执行结果写入 WorkingSetItem
        if (outcome.success() && workspaceService != null && state.sessionId() != null) {
            persistToolResultToWorkspace(state, planned, outcome);
        }

        log.debug("工具执行完成: toolId={}, success={}, latencyMs={}",
                planned.toolId(), outcome.success(), outcome.duration().toMillis());
        return state;
    }

    /**
     * 将关键工具执行结果持久化到 L1 工作区，增强长对话上下文保持。
     *
     * <p>仅对 {@link #WORKSPACE_WORTHY_TOOLS} 中的工具生效，避免工作区被低价值条目淹没。</p>
     */
    private void persistToolResultToWorkspace(ReactAgentState state,
                                               PlannedToolCall planned,
                                               ToolExecutionOutcome outcome) {
        if (!WORKSPACE_WORTHY_TOOLS.contains(planned.toolId())) {
            return;
        }
        try {
            String summary = outcome.observationOutput();
            if (summary.length() > 300) {
                summary = summary.substring(0, 300) + "…";
            }
            workspaceService.saveWorkingSet(state.sessionId(), new WorkingSetItem(
                    planned.toolDisplayName() + " 执行结果",
                    summary,
                    Map.of("toolId", planned.toolId()),
                    40,
                    state.traceId(),
                    state.traceId(),
                    null));
        } catch (Exception e) {
            log.debug("工具结果工作区持久化失败: toolId={}, error={}", planned.toolId(), e.getMessage());
        }
    }

    private ReactAgentState replayExtractedMedia(ReactAgentState state,
                                                 String toolId,
                                                 List<MediaDataExtractor.MediaItem> mediaItems,
                                                 AgentLoopContext loopContext) {
        if (loopContext.getSseManager() != null && loopContext.getStreamId() != null) {
            for (var mediaItem : mediaItems) {
                Map<String, Object> mediaData = new HashMap<>();
                mediaData.put("toolId", toolId);
                mediaData.put("mimeType", mediaItem.mediaType());
                mediaData.put("encoding", mediaItem.encoding());
                mediaData.put("data", mediaItem.data());
                mediaData.put("field", mediaItem.fieldName());
                mediaData.put("metadata", mediaItem.metadata());
                loopContext.getSseManager().sendEvent(loopContext.getStreamId(), SseEventType.MEDIA, mediaData);
            }
        }

        loopContext.addAllToolMedia(mediaItems);

        // VISION 不可用时跳过 pendingMedia 注入，避免后续迭代强制走视觉路由
        boolean visionAvailable = multimodalRouter != null && multimodalRouter.isVisionAvailable();
        if (!visionAvailable) {
            log.info("VISION Provider 不可用，跳过工具截图的 pendingMedia 注入: toolId={}, mediaCount={}",
                    toolId, mediaItems.size());
            return state;
        }

        for (var mediaItem : mediaItems) {
            if (!mediaItem.mediaType().startsWith("image/")) {
                continue;
            }
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
        return state;
    }

    /**
     * 从工具输出中推断业务执行是否成功。
     *
     * <p>ToolCallback 只返回字符串，无法直接携带 {@code ToolResult.ok()}。
     * 对于桥接层返回的 JSON envelope，这里根据 {@code status} / {@code error} 字段恢复真实执行状态，
     * 避免“调用未抛异常但业务已失败”被误记为成功。</p>
     */
    private boolean inferToolExecutionSuccess(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(output);
            if (!root.isObject()) {
                return true;
            }
            String status = root.path("status").asText("");
            if ("ERROR".equalsIgnoreCase(status)) {
                return false;
            }
            return !root.has("error") || root.has("data");
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 为程序记忆构造稳定的意图匹配查询。
     *
     * <p>避免将完整 JSON 参数、Markdown 正文或大段代码直接送入 FTS；
     * 这里只保留工具 ID、参数名以及少量稳定的短文本值。</p>
     */
    private String buildIntentMatchQuery(String toolId, String inputJson) {
        StringBuilder builder = new StringBuilder(toolId.replace('.', ' '));
        try {
            JsonNode root = objectMapper.readTree(inputJson);
            if (!root.isObject()) {
                return builder.toString();
            }

            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                appendIntentTerm(builder, fieldNames.next());
            }

            appendIntentValue(builder, root.get("path"), 120);
            appendIntentValue(builder, root.get("command"), 120);
            appendIntentValue(builder, root.get("url"), 160);
            appendIntentValue(builder, root.get("query"), 120);
            appendIntentValue(builder, root.get("language"), 40);
            appendIntentValue(builder, root.get("collectionId"), 80);
            appendIntentValue(builder, root.get("workflowId"), 80);
        } catch (Exception e) {
            log.debug("构造意图匹配查询失败，回退为 toolId: toolId={}, error={}", toolId, e.getMessage());
        }
        return builder.toString();
    }

    private void appendIntentTerm(StringBuilder builder, @Nullable String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        builder.append(' ').append(term);
    }

    private void appendIntentValue(StringBuilder builder, @Nullable JsonNode node, int maxChars) {
        if (node == null || !node.isTextual()) {
            return;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.isBlank()) {
            return;
        }
        if (normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars);
        }
        builder.append(' ').append(normalized);
    }

    /**
     * 从工具 JSON 输出中识别挂起信号。
     *
     * <p>只有同时满足 {@code _suspend=true} 和结构化 {@code _suspendReason} 时，才会转换成运行时挂起原因。
     */
    @Nullable
    private SuspendReason parseSuspendReasonFromOutput(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(output);
            if (!root.isObject()) {
                return null;
            }
            JsonNode suspendNode = root.get("_suspend");
            if (suspendNode == null || !suspendNode.asBoolean(false)) {
                return null;
            }
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
            log.debug("工具输出不是挂起信号 JSON: error={}", e.getMessage());
            return null;
        }
    }

    /** 异步将 tool_call 记录到 transcript，不阻塞工具执行主路径。 */
    private void persistTranscriptToolCall(ReactAgentState state,
                                           AssistantMessage.ToolCall toolCall,
                                           String toolId,
                                           @Nullable String toolDisplayName,
                                           String inputJson,
                                           Instant createdAt) {
        if (transcriptStore == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                transcriptStore.appendToolCall(
                        state.sessionId(),
                        state.traceId(),
                        state.traceId(),
                        toolId,
                        toolCall.id(),
                        toolDisplayName,
                        inputJson,
                        createdAt
                );
            } catch (Exception e) {
                log.warn("写入 transcript tool_call 失败: sessionId={}, toolId={}, error={}",
                        state.sessionId(), toolId, e.getMessage());
            }
        }, VIRTUAL_EXECUTOR);
    }

    /** 异步将 tool_result 记录到 transcript，不阻塞工具执行主路径。 */
    private void persistTranscriptToolResult(ReactAgentState state,
                                             AssistantMessage.ToolCall toolCall,
                                             String toolId,
                                             boolean success,
                                             @Nullable String outputJson,
                                             @Nullable String artifactId,
                                             Instant createdAt) {
        if (transcriptStore == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                transcriptStore.appendToolResult(
                        state.sessionId(),
                        state.traceId(),
                        state.traceId(),
                        toolId,
                        toolCall.id(),
                        success,
                        outputJson != null ? outputJson : "",
                        artifactId,
                        true,
                        false,
                        createdAt
                );
            } catch (Exception e) {
                log.warn("写入 transcript tool_result 失败: sessionId={}, toolId={}, error={}",
                        state.sessionId(), toolId, e.getMessage());
            }
        }, VIRTUAL_EXECUTOR);
    }

    /** 将工具执行结果写入 Trace，保证后续诊断能看到输入、输出和耗时。 */
    private void recordToolCallStep(@Nullable TraceContext traceContext,
                                    int stepIndex,
                                    Instant startTime,
                                    Instant completedAt,
                                    Duration duration,
                                    String toolId,
                                    String inputJson,
                                    @Nullable String outputJson,
                                    boolean success,
                                    RiskLevel riskLevel) {
        if (traceContext == null || traceRecorder == null) {
            return;
        }
        try {
            var step = new ToolCallStep(
                    stepIndex,
                    completedAt,
                    duration,
                    toolId,
                    "execute",
                    inputJson,
                    outputJson != null ? outputJson : "",
                    success,
                    success ? null : outputJson,
                    riskLevel
            );
            traceRecorder.recordStep(traceContext, step);
        } catch (Exception e) {
            log.debug("Trace 工具步骤记录失败: toolId={}, startedAt={}, error={}",
                    toolId, startTime, e.getMessage());
        }
    }

    /** 对纯文本结果做轻量 token 估算，供 observation 步骤和预算扣减使用。 */
    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    /** 允许主循环决定“追加步骤后是否同步推送 SSE”。 */
    @FunctionalInterface
    public interface StepAppender {
        ReactAgentState append(ReactAgentState state, ReactStep step, AgentLoopContext loopContext);
    }

    private record PlannedToolCall(
            int index,
            AssistantMessage.ToolCall toolCall,
            String toolId,
            String inputJson,
            @Nullable String toolDisplayName,
            RiskLevel toolRiskLevel,
            ToolSchedulingMode schedulingMode,
            List<String> resourceKeys,
            @Nullable ToolCallback matchedCallback
    ) {
        private PlannedToolCall {
            resourceKeys = resourceKeys == null ? List.of() : List.copyOf(resourceKeys);
        }
    }

    private record ToolExecutionWave(List<PlannedToolCall> toolCalls) {}

    private record ToolExecutionOutcome(
            PlannedToolCall planned,
            Instant startedAt,
            Instant completedAt,
            Duration duration,
            boolean success,
            @Nullable String rawOutput,
            String observationOutput,
            int observationTokens,
            @Nullable SuspendReason suspendReason,
            List<MediaDataExtractor.MediaItem> mediaItems
    ) {
        private ToolExecutionOutcome {
            observationOutput = observationOutput != null ? observationOutput : "";
            mediaItems = mediaItems != null ? List.copyOf(mediaItems) : List.of();
        }
    }
}
