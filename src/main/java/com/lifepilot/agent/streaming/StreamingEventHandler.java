package com.lifepilot.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.model.MemorySourceSummarySupport;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.agent.learning.extraction.MemoryChangeSummarySupport;
import com.lifepilot.agent.learning.extraction.MemoryExtractionCandidateRepository;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ExecutionConstraintSummarySupport;
import com.lifepilot.agent.model.OutputContentRole;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.ReactStepSerializer;
import com.lifepilot.agent.recovery.TaskRecoverySummaryBuilder;
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
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final MemoryExtractionCandidateRepository memoryExtractionCandidateRepository;

    public StreamingEventHandler(
            ObjectMapper objectMapper,
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
            @Nullable AttachmentRepository attachmentRepository,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable SemanticMemory semanticMemory,
            @Nullable MemoryExtractionCandidateRepository memoryExtractionCandidateRepository) {
        this.objectMapper = objectMapper;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.attachmentRepository = attachmentRepository;
        this.injectionRecordRepository = injectionRecordRepository;
        this.semanticMemory = semanticMemory;
        this.memoryExtractionCandidateRepository = memoryExtractionCandidateRepository;
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
        return buildDoneEventPayload(request, state, tempTurnId, finalTokenUsage, steps, reasoningSummary,
                finalContent, assistantEntryId, lastCollectedA2uiTree, streamTimings, List.of());
    }

    /**
     * 构建 DONE 事件 payload，并携带本轮最终产物引用。
     *
     * <p>{@code artifact-ref} SSE 负责即时展示，DONE 中的 {@code artifactRefs}
     * 负责最终消息沉淀和事件丢失时的兜底。</p>
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
                                                     @Nullable Map<String, Long> streamTimings,
                                                     @Nullable List<ArtifactRef> artifactRefs) {
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

        var artifactRefPayloads = TaskRecoverySummaryBuilder.artifactRefPayloads(artifactRefs);

        // ReactStep 序列化
        var reactSteps = ReactStepSerializer.serialize(steps != null ? steps : List.of());
        if (!reactSteps.isEmpty()) {
            doneData.put("reactSteps", reactSteps);
        }
        var toolSummaries = TaskRecoverySummaryBuilder.attachTurnArtifactRefs(
                buildToolSummaries(reactSteps),
                artifactRefPayloads);
        if (!toolSummaries.isEmpty()) {
            doneData.put("toolsSummary", toolSummaries);
        }
        TaskRecoverySummaryBuilder.fromState(state, toolSummaries)
                .ifPresent(summary -> doneData.put("taskRecovery", summary));

        // 对话中自然浮现本轮使用的上下文来源：知识库 + 记忆。
        var sources = new ArrayList<Map<String, Object>>();
        sources.addAll(buildKnowledgeSources(request.sessionId()));
        sources.addAll(buildMemorySources(assistantEntryId));
        if (!sources.isEmpty()) {
            doneData.put("sources", sources);
        }
        var memoryChanges = buildMemoryChanges(tempTurnId);
        if (!memoryChanges.isEmpty()) {
            doneData.put("memoryChanges", memoryChanges);
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
        if (state.turnRecoveryContext() != null && !state.turnRecoveryContext().isEmpty()) {
            doneData.put("turnRecoveryContext", state.turnRecoveryContext());
        }
        Map<String, Object> executionConstraints = ExecutionConstraintSummarySupport.from(state);
        if (!executionConstraints.isEmpty()) {
            doneData.put("executionConstraints", executionConstraints);
        }
        if (reasoningSummary != null) {
            doneData.put("reasoningSummary", reasoningSummary);
        }
        if (lastCollectedA2uiTree != null && !lastCollectedA2uiTree.components().isEmpty()) {
            doneData.put("a2uiComponents", lastCollectedA2uiTree.components());
        }
        if (!artifactRefPayloads.isEmpty()) {
            doneData.put("artifactRefs", artifactRefPayloads);
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
        if (state.completionReason() == CompletionReason.CANCELLED) {
            return ChatTurnStatus.CANCELLED;
        }
        if (state.suspended()) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (state.completionMode() == com.lifepilot.agent.model.CompletionMode.DEGRADED
                || (state.terminationReason() != null && !state.terminationReason().isBlank())) {
            return ChatTurnStatus.DEGRADED;
        }
        return ChatTurnStatus.SUCCESS;
    }

    // ===== 工具执行摘要 =====

    /**
     * 从 ReAct 步骤压缩出本轮工具执行摘要。
     *
     * <p>{@code reactSteps} 仍保留完整轨迹；{@code toolsSummary} 面向主对话轻量浮现，
     * 让用户不用展开轨迹也能看到用了哪些工具、成败和输出摘要。</p>
     */
    List<Map<String, Object>> buildToolSummaries(List<Map<String, Object>> reactSteps) {
        return TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(reactSteps);
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

    // ===== 记忆来源 =====

    /** 构建本轮实际注入过的记忆摘要。 */
    List<Map<String, Object>> buildMemorySources(@Nullable String assistantEntryId) {
        if (assistantEntryId == null || assistantEntryId.isBlank()
                || injectionRecordRepository == null || semanticMemory == null) {
            return List.of();
        }
        try {
            var entityIds = injectionRecordRepository.findEntityIdsBySourceEntryId(assistantEntryId);
            if (entityIds.isEmpty()) {
                return List.of();
            }
            var result = new ArrayList<Map<String, Object>>();
            for (String entityId : entityIds) {
                if (entityId == null || entityId.isBlank()) {
                    continue;
                }
                semanticMemory.findById(entityId)
                        .map(MemorySourceSummarySupport::toMemorySource)
                        .ifPresent(result::add);
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            log.debug("构建记忆来源摘要失败: entryId={}, error={}", assistantEntryId, e.getMessage());
            return List.of();
        }
    }

    // ===== 记忆沉淀 =====

    /** 构建本轮对话实际落库的记忆变更摘要。 */
    List<Map<String, Object>> buildMemoryChanges(@Nullable String turnId) {
        if (turnId == null || turnId.isBlank() || memoryExtractionCandidateRepository == null) {
            return List.of();
        }
        try {
            return MemoryChangeSummarySupport.buildAppliedMemoryChangeSummaries(
                    memoryExtractionCandidateRepository,
                    turnId,
                    5);
        } catch (Exception e) {
            log.debug("构建记忆沉淀摘要失败: turnId={}, error={}", turnId, e.getMessage());
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
