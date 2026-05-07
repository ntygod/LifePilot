package com.lifepilot.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.OutputContentRole;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.ReactStepSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * 流式事件处理器 — SSE 推送与 A2UI 解析。
 *
 * <p>从 ReactAgentLoop 提取的 SSE 事件构建与发送逻辑，
 * 包括 REASONING / ERROR / DONE 事件推送和 A2UI 组件解析。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class StreamingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingEventHandler.class);

    private final ObjectMapper objectMapper;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final AttachmentRepository attachmentRepository;

    public StreamingEventHandler(
            ObjectMapper objectMapper,
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
            @Nullable AttachmentRepository attachmentRepository) {
        this.objectMapper = objectMapper;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.attachmentRepository = attachmentRepository;
    }

    // ===== SSE 事件发送 =====

    /** 发送 SSE ERROR 事件并关闭连接。 */
    public void sendStreamError(SseSessionManager sseManager, String streamId,
                                int code, String message,
                                @Nullable String traceId,
                                @Nullable String turnId,
                                @Nullable ChatTurnStatus turnStatus) {
        var errorData = new HashMap<String, Object>();
        errorData.put("code", code);
        errorData.put("message", message);
        if (traceId != null) {
            errorData.put("traceId", traceId);
        }
        if (turnId != null && !turnId.isBlank()) {
            errorData.put("turnId", turnId);
        }
        if (turnStatus != null) {
            errorData.put("turnStatus", turnStatus.name());
        }
        sseManager.sendEvent(streamId, SseEventType.ERROR, errorData);
        sseManager.closeEmitter(streamId);
    }

    // ===== DONE 事件构建 =====

    /**
     * 构建 DONE 事件 payload。
     *
     * @param request 原始请求
     * @param state 最终状态
     * @param tempTurnId 临时 turnId
     * @param finalTokenUsage Token 使用量
     * @param steps ReAct 步骤序列
     * @param reasoningSummary 推理概要
     * @param finalContent 最终内容
     * @param assistantEntryId 助手 transcript 条目 ID
     * @param lastCollectedA2uiTree 最后收集的 A2UI 组件树
     * @param streamTimings 流式体验时序指标
     * @return DONE 事件 payload
     */
    public Map<String, Object> buildDoneEventPayload(AgentRequest request,
                                                     ReactAgentState state,
                                                     String tempTurnId,
                                                     @Nullable TokenUsage finalTokenUsage,
                                                     @Nullable List<ReactStep> steps,
                                                     @Nullable String reasoningSummary,
                                                     @Nullable String finalContent,
                                                     @Nullable String assistantEntryId,
                                                     @Nullable A2uiComponentTree lastCollectedA2uiTree,
                                                     @Nullable Map<String, Long> streamTimings) {
        var doneData = new HashMap<String, Object>();
        doneData.put("entryId", assistantEntryId != null ? assistantEntryId : tempTurnId);
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

        // ReactStep 序列化
        var reactSteps = ReactStepSerializer.serialize(steps != null ? steps : List.of());
        if (!reactSteps.isEmpty()) {
            doneData.put("reactSteps", reactSteps);
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
        doneData.put("taskMode", state.taskMode().name());
        doneData.put("completionMode", state.completionMode().name());
        doneData.put("contentRole", resolveContentRole(state, steps, finalContent).name());
        if (state.completionReason() != null) {
            doneData.put("completionReason", state.completionReason().name());
        }
        if (state.terminationReason() != null && !state.terminationReason().isBlank()) {
            doneData.put("terminationReason", state.terminationReason());
        }
        doneData.put("turnStatus", resolveTurnStatus(state).name());
        if (state.resumedFromTraceId() != null && !state.resumedFromTraceId().isBlank()) {
            doneData.put("resumedFromTraceId", state.resumedFromTraceId());
        }
        if (reasoningSummary != null) {
            doneData.put("reasoningSummary", reasoningSummary);
        }
        if (lastCollectedA2uiTree != null && !lastCollectedA2uiTree.components().isEmpty()) {
            doneData.put("a2uiComponents", lastCollectedA2uiTree.components());
        }
        if (streamTimings != null && !streamTimings.isEmpty()) {
            doneData.put("streamTimings", streamTimings);
        }

        var contents = new ArrayList<Map<String, Object>>();
        if (finalContent != null && !finalContent.isBlank()) {
            var textContent = new HashMap<String, Object>();
            textContent.put("type", "TEXT");
            textContent.put("text", finalContent);
            contents.add(textContent);
        }
        doneData.put("contents", contents);
        var attachments = buildAttachmentPayloads(assistantEntryId);
        if (!attachments.isEmpty()) {
            doneData.put("attachments", attachments);
        }
        return doneData;
    }

    private List<Map<String, Object>> buildAttachmentPayloads(@Nullable String assistantEntryId) {
        if (assistantEntryId == null || assistantEntryId.isBlank() || attachmentRepository == null) {
            return List.of();
        }
        try {
            return attachmentRepository.findByEntryId(assistantEntryId).stream()
                    .map(record -> {
                        String mimeType = record.mimeType() != null && !record.mimeType().isBlank()
                                ? record.mimeType()
                                : "application/octet-stream";
                        Map<String, Object> attachment = new HashMap<>();
                        attachment.put("fileId", record.id());
                        attachment.put("filename", record.fileName());
                        attachment.put("size", record.fileSize());
                        attachment.put("type", mimeType);
                        attachment.put("url", record.url() != null ? record.url() : "");
                        attachment.put("isImage", mimeType.startsWith("image/"));
                        return Map.copyOf(attachment);
                    })
                    .toList();
        } catch (Exception e) {
            log.debug("构建 DONE 附件失败: entryId={}, error={}", assistantEntryId, e.getMessage());
            return List.of();
        }
    }

    private OutputContentRole resolveContentRole(ReactAgentState state,
                                                 @Nullable List<ReactStep> steps,
                                                 @Nullable String finalContent) {
        if (state.suspended()) {
            return OutputContentRole.SUSPEND_PROMPT;
        }
        if (state.completionReason() == CompletionReason.EXPLICIT_BLOCKED) {
            return OutputContentRole.BLOCKED;
        }
        if (finalContent != null && steps != null) {
            for (int index = steps.size() - 1; index >= 0; index--) {
                ReactStep step = steps.get(index);
                if (step instanceof ReactStep.Progress progress
                        && finalContent.strip().equals(progress.content().strip())) {
                    return OutputContentRole.PROGRESS;
                }
                if (step instanceof ReactStep.Answer || step instanceof ReactStep.Thought) {
                    break;
                }
            }
        }
        return OutputContentRole.FINAL;
    }

    private ChatTurnStatus resolveTurnStatus(ReactAgentState state) {
        if (state.suspended()) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (state.completionMode() == com.lifepilot.agent.model.CompletionMode.DEGRADED
                || (state.terminationReason() != null && !state.terminationReason().isBlank())) {
            return ChatTurnStatus.DEGRADED;
        }
        return ChatTurnStatus.SUCCESS;
    }

    // ===== 知识库来源 =====

    /** 构建知识库来源摘要。 */
    List<Map<String, Object>> buildKnowledgeSources(@Nullable String sessionId) {
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

    /** 序列化 A2UI 组件树为 JSON。 */
    @Nullable
    public String serializeA2uiTree(@Nullable A2uiComponentTree tree) {
        if (tree == null || tree.components().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            log.warn("A2UI 组件树序列化失败: error={}", e.getMessage());
            return null;
        }
    }
}
