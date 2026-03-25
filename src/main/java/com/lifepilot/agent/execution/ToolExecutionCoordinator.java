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
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 工具执行协调器。
 *
 * <p>负责单次 tool call 的执行、媒体提取、挂起解析、transcript 持久化和 trace 记录，
 * 将 ReAct 主循环中的工具执行细节收敛到独立组件。
 *
 * @author zsg
 * @since 2026-03-25
 */
public class ToolExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionCoordinator.class);

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

    public ToolExecutionCoordinator(AgentToolProvider agentToolProvider,
                                    ObjectMapper objectMapper,
                                    @Nullable TraceRecorder traceRecorder,
                                    @Nullable TranscriptStore transcriptStore,
                                    @Nullable MediaDataExtractor mediaDataExtractor,
                                    @Nullable ProceduralMemory proceduralMemory,
                                    @Nullable IntentMatcher intentMatcher) {
        this.agentToolProvider = agentToolProvider;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.transcriptStore = transcriptStore;
        this.mediaDataExtractor = mediaDataExtractor;
        this.proceduralMemory = proceduralMemory;
        this.intentMatcher = intentMatcher;
    }

    /**
     * 执行单次 tool call，并将其副作用统一回写到 state、trace 和 transcript。
     *
     * <p>方法内部同时负责工具匹配、异常兜底、挂起信号识别、媒体提取和观察结果落步，
     * 让主循环只保留“拿到 tool call 后交给协调器处理”的骨架。
     */
    public ReactAgentState execute(ReactAgentState state,
                                   AssistantMessage.ToolCall toolCall,
                                   List<ToolCallback> toolCallbacks,
                                   @Nullable TraceContext traceContext,
                                   CancellationToken cancellationToken,
                                   AgentLoopContext loopContext,
                                   StepAppender stepAppender) {
        if (cancellationToken.isCancelled()) {
            log.info("工具执行前检测到取消信号: toolId={}", toolCall.name());
            return state;
        }

        String toolId = toolCall.name();
        String inputJson = toolCall.arguments();
        String toolDisplayName = agentToolProvider.resolveToolDisplayName(toolId);

        Instant toolCallStart = Instant.now();
        state = stepAppender.append(state, new ReactStep.ToolCall(toolId, toolDisplayName, inputJson, 0), loopContext);
        persistTranscriptToolCall(state, toolCall, toolId, toolDisplayName, inputJson, toolCallStart);

        ToolCallback matchedCallback = toolCallbacks.stream()
                .filter(Objects::nonNull)
                .filter(cb -> cb.getToolDefinition().name().equals(toolId))
                .findFirst()
                .orElse(null);

        if (matchedCallback == null) {
            log.warn("未找到工具回调: toolId={}", toolId);
            String errorOutput = "工具未注册: " + toolId;
            state = stepAppender.append(state, new ReactStep.Observation(
                    toolId, toolDisplayName, false, errorOutput, 0), loopContext);
            persistTranscriptToolResult(state, toolCall, toolId, false, errorOutput, null, toolCallStart);
            recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                    toolId, inputJson, errorOutput, false);
            return state;
        }

        String rawOutput;
        boolean success;
        try {
            rawOutput = matchedCallback.call(inputJson);
            success = inferToolExecutionSuccess(rawOutput);
        } catch (Exception e) {
            log.warn("工具执行失败: toolId={}, error={}", toolId, e.getMessage());
            rawOutput = "工具执行异常: " + e.getMessage();
            success = false;
        }
        Duration toolCallDuration = Duration.between(toolCallStart, Instant.now());

        if (success) {
            SuspendReason suspendReason = parseSuspendReasonFromOutput(rawOutput);
            if (suspendReason != null) {
                log.info("工具请求挂起: toolId={}, reason={}", toolId, suspendReason);
                state = state.suspend(suspendReason);
                state = stepAppender.append(state, new ReactStep.Observation(
                        toolId, toolDisplayName, true, "工具请求挂起: " + suspendReason, 0), loopContext);
                persistTranscriptToolResult(state, toolCall, toolId, true, rawOutput, null, toolCallStart);
                recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart,
                        toolId, inputJson, rawOutput, true);
                return state;
            }
        }

        String observationOutput = rawOutput;
        if (success && mediaDataExtractor != null) {
            var extraction = mediaDataExtractor.extract(toolId, rawOutput);
            observationOutput = extraction.sanitizedOutput();

            if (loopContext.getSseManager() != null
                    && loopContext.getStreamId() != null
                    && !extraction.mediaItems().isEmpty()) {
                for (var mediaItem : extraction.mediaItems()) {
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

            if (!extraction.mediaItems().isEmpty()) {
                loopContext.addAllToolMedia(extraction.mediaItems());
                for (var mediaItem : extraction.mediaItems()) {
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
            }
        }

        int observationTokens = estimateTextTokens(observationOutput != null ? observationOutput : "");
        state = stepAppender.append(state, new ReactStep.Observation(
                toolId, toolDisplayName, success, observationOutput != null ? observationOutput : "", observationTokens), loopContext);
        persistTranscriptToolResult(state, toolCall, toolId, success, rawOutput, null, toolCallStart);

        if (success && proceduralMemory != null && intentMatcher != null) {
            try {
                var match = intentMatcher.match(toolId + " " + inputJson);
                match.ifPresent(m -> proceduralMemory.recordExecution(m.template().templateId(), true));
            } catch (Exception e) {
                log.warn("L4 执行结果记录失败: toolId={}, error={}", toolId, e.getMessage());
            }
        }

        recordToolCallStep(traceContext, state.stepCount() - 1, toolCallStart, toolId, inputJson, rawOutput, success);
        log.debug("工具执行完成: toolId={}, success={}, latencyMs={}", toolId, success, toolCallDuration.toMillis());
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
            if (root.has("error") && !root.has("data")) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
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

    /** 将 tool_call 记录到 transcript，便于后续回放和历史审计。 */
    private void persistTranscriptToolCall(ReactAgentState state,
                                           AssistantMessage.ToolCall toolCall,
                                           String toolId,
                                           @Nullable String toolDisplayName,
                                           String inputJson,
                                           Instant createdAt) {
        if (transcriptStore == null) {
            return;
        }
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
    }

    /** 将 tool_result 记录到 transcript，与 tool_call 组成完整工具轨迹。 */
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
    }

    /** 将工具执行结果写入 Trace，保证后续诊断能看到输入、输出和耗时。 */
    private void recordToolCallStep(@Nullable TraceContext traceContext,
                                    int stepIndex,
                                    Instant startTime,
                                    String toolId,
                                    String inputJson,
                                    @Nullable String outputJson,
                                    boolean success) {
        if (traceContext == null || traceRecorder == null) {
            return;
        }
        try {
            Duration duration = Duration.between(startTime, Instant.now());
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
}
