package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.recovery.TaskRecoverySummaryBuilder;
import com.lifepilot.agent.recovery.ToolExecutionSummarySupport;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.model.TurnRecoveryActionRequest;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.governance.policy.MemoryUserBoundaryPolicy;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.project.context.ProjectContextResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话轮次服务。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Service
public class ChatTurnService {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int RECOVERY_OUTPUT_DETAIL_MAX_LENGTH = 1200;
    private static final int RECOVERY_NEXT_ACTION_LIMIT = 5;
    private static final int RECOVERY_NEXT_ACTION_MAX_LENGTH = 180;

    private final ChatTurnRepository chatTurnRepository;
    private final SessionTranscriptRepository transcriptRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    @Nullable
    private final NotificationProperties notificationProperties;
    @Nullable
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable
    private final ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;
    @Nullable
    private final ChatSessionRepository chatSessionRepository;
    @Nullable
    private final ProjectContextResolver projectContextResolver;
    @Nullable
    private final MemoryAccessPolicy memoryAccessPolicy;

    @Autowired
    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                           @Nullable ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository,
                           @Nullable MemorySpaceRepository memorySpaceRepository,
                           @Nullable ChatSessionRepository chatSessionRepository,
                           @Nullable ProjectContextResolver projectContextResolver,
                           @Nullable MemoryAccessPolicy memoryAccessPolicy) {
        this.chatTurnRepository = chatTurnRepository;
        this.transcriptRepository = transcriptRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.notificationProperties = notificationProperties;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.chatTurnMemorySnapshotRepository = chatTurnMemorySnapshotRepository;
        this.memorySpaceRepository = memorySpaceRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.projectContextResolver = projectContextResolver;
        this.memoryAccessPolicy = memoryAccessPolicy;
    }

    public ResolvedTurnRequest prepare(String sessionId, ChatRequest request) {
        Instant now = Instant.now();
        String turnId = request.turnId();
        ChatTurnAction action = request.action();
        return switch (action) {
            case SEND -> prepareSend(sessionId, turnId, request, now);
            case RETRY, RESUME, RESTART -> prepareReplay(sessionId, turnId, action, request, now);
        };
    }

    public Optional<ChatTurnRecord> findBySessionIdAndTurnId(String sessionId, String turnId) {
        return chatTurnRepository.findBySessionIdAndTurnId(sessionId, turnId);
    }

    public List<ChatTurnRecord> findBySessionId(String sessionId) {
        return chatTurnRepository.findBySessionId(sessionId);
    }

    public void bindUserEntry(String sessionId, String turnId, String userEntryId) {
        chatTurnRepository.bindUserEntry(sessionId, turnId, userEntryId, Instant.now());
    }

    public void bindTrace(String sessionId, String turnId, @Nullable String traceId) {
        chatTurnRepository.updateTrace(sessionId, turnId, traceId, Instant.now());
    }

    public void markCompleted(String sessionId,
                              String turnId,
                              ChatTurnStatus status,
                              @Nullable String assistantEntryId,
                              @Nullable String traceId,
                              @Nullable String resumedFromTraceId,
                              @Nullable CompletionMode completionMode) {
        chatTurnRepository.markCompleted(
                sessionId,
                turnId,
                status,
                assistantEntryId,
                traceId,
                resumedFromTraceId,
                completionMode != null ? completionMode.name() : null,
                Instant.now()
        );

        // 发布对话完成事件 — 触发认知闭环（画像巩固 + 隐式信号 + 未命中检测）
        if (status == ChatTurnStatus.SUCCESS) {
            try {
                String userId = notificationProperties != null ? notificationProperties.getDefaultUserId() : "default";
                String summary = loadConversationSummary(sessionId);
                MemoryLearningBoundary memoryLearningBoundary = resolveMemoryLearningBoundary(turnId);
                eventPublisher.publishEvent(
                        new ConversationCompletedEvent(
                                this,
                                userId,
                                sessionId,
                                turnId,
                                summary,
                                memoryLearningBoundary.learningEnabled(),
                                memoryLearningBoundary.reason()));
            } catch (Exception e) {
                log.debug("对话完成事件发布跳过: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    private MemoryLearningBoundary resolveMemoryLearningBoundary(String turnId) {
        if (chatTurnMemorySnapshotRepository == null) {
            return MemoryLearningBoundary.enabled();
        }
        return chatTurnMemorySnapshotRepository.findByTurnId(turnId)
                .map(snapshot -> {
                    boolean enabled = snapshot.personalLearningEnabled()
                            || snapshot.domainLearningEnabled()
                            || snapshot.experienceLearningEnabled();
                    if (enabled) {
                        return MemoryLearningBoundary.enabled();
                    }
                    String reason = stringValue(snapshot.resolutionSource().get("autoLearningSkipReason"));
                    if (reason == null && Boolean.TRUE.equals(snapshot.resolutionSource().get("projectResolutionFailed"))) {
                        reason = stringValue(snapshot.resolutionSource().get("projectResolutionReason"));
                    }
                    return MemoryLearningBoundary.disabled(
                            reason != null ? reason : "memory_learning_disabled");
                })
                .orElseGet(MemoryLearningBoundary::enabled);
    }

    /** 加载对话摘要（取最近一条用户消息的前 200 字作为上下文）。 */
    @Nullable
    private String loadConversationSummary(String sessionId) {
        try {
            var entries = transcriptRepository.findBySessionId(sessionId);
            return entries.stream()
                    .filter(e -> "user".equals(e.role()))
                    .reduce((first, second) -> second)  // 取最后一条
                    .map(e -> {
                        String content = e.payloadJson();
                        return content != null && content.length() > 200 ? content.substring(0, 200) : content;
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public void markFailed(String sessionId,
                           String turnId,
                           @Nullable String traceId,
                           @Nullable Integer errorCode,
                           String errorMessage) {
        chatTurnRepository.markFailed(sessionId, turnId, traceId, errorCode, errorMessage, Instant.now());
    }

    private ResolvedTurnRequest prepareSend(String sessionId,
                                            String turnId,
                                            ChatRequest request,
                                            Instant now) {
        if (chatTurnRepository.findBySessionIdAndTurnId(sessionId, turnId).isPresent()) {
            throw new IllegalArgumentException("turnId 已存在，禁止重复 SEND: turnId=" + turnId);
        }
        String payloadJson = serializeSnapshot(new TurnRequestSnapshot(
                request.content(),
                request.attachmentIds(),
                request.preferredProvider(),
                request.singleTurnOverride()
        ));
        chatTurnRepository.create(turnId, sessionId, ChatTurnAction.SEND, ChatTurnStatus.PENDING, payloadJson, now);
        persistTurnMemorySnapshot(sessionId, turnId, request.content(), now);
        return new ResolvedTurnRequest(
                turnId,
                ChatTurnAction.SEND,
                request.content(),
                request.attachmentIds(),
                request.preferredProvider(),
                request.singleTurnOverride(),
                null
        );
    }

    private ResolvedTurnRequest prepareReplay(String sessionId,
                                              String turnId,
                                              ChatTurnAction action,
                                              ChatRequest request,
                                              Instant now) {
        ChatTurnRecord turn = chatTurnRepository.findBySessionIdAndTurnId(sessionId, turnId)
                .orElseThrow(() -> new IllegalArgumentException("turn 不存在: turnId=" + turnId));
        if (action != ChatTurnAction.RESUME
                && turn.assistantEntryId() != null
                && !turn.assistantEntryId().isBlank()) {
            transcriptRepository.updateVisibility(turn.assistantEntryId(), false, false);
        }
        TurnRequestSnapshot snapshot = deserializeSnapshot(turn.requestPayloadJson());
        String snapshotContent = snapshot.content() != null ? snapshot.content() : "";
        String resolvedContent = snapshotContent;
        TurnRecoveryContext recoveryContext = null;
        TurnRequestSnapshot updatedSnapshot = snapshot;
        TurnRecoveryActionRequest recoveryAction = request.recoveryAction();
        boolean hasRecoveryAction = recoveryAction != null;
        if (action == ChatTurnAction.RESUME) {
            Map<String, Object> taskRecovery = loadTaskRecoveryFromAssistant(turn.assistantEntryId());
            if (request.hasContent() || hasRecoveryAction || !taskRecovery.isEmpty()) {
                String resumeInput = hasRecoveryAction
                        ? buildRecoveryActionInstruction(action, recoveryAction, request.content())
                        : (request.hasContent()
                                ? request.content()
                                : buildTaskRecoveryInstruction(action, taskRecovery));
                resolvedContent = buildResumeContent(snapshotContent, resumeInput);
                recoveryContext = buildRecoveryContext(turn, action, resumeInput, now, taskRecovery, recoveryAction);
            }
        } else if (action == ChatTurnAction.RESTART) {
            Map<String, Object> taskRecovery = loadTaskRecoveryFromAssistant(turn.assistantEntryId());
            boolean hasTaskRecovery = !taskRecovery.isEmpty();
            if (request.hasContent() || hasRecoveryAction || hasTaskRecovery) {
                String restartInstruction = hasRecoveryAction
                        ? buildRecoveryActionInstruction(action, recoveryAction, request.content())
                        : (request.hasContent()
                                ? request.content()
                                : buildTaskRecoveryInstruction(action, taskRecovery));
                if (hasRecoveryAction || hasTaskRecovery) {
                    recoveryContext = buildRecoveryContext(turn, action, restartInstruction, now, taskRecovery, recoveryAction);
                }
                String visibleContent = resolveRestartVisibleContent(request, snapshotContent, hasRecoveryAction || hasTaskRecovery);
                resolvedContent = restartInstruction;
                resolvedContent = buildRestartContent(resolvedContent, visibleContent, recoveryContext);
                if (!Objects.equals(normalizeBlank(snapshotContent), normalizeBlank(visibleContent))) {
                    updatedSnapshot = snapshot.withContent(visibleContent);
                    updateUserEntryContent(turn.userEntryId(), visibleContent);
                }
            }
        }
        updatedSnapshot = updatedSnapshot.withLastRecoveryContext(recoveryContext);
        chatTurnRepository.markAttemptStarted(
                sessionId, turnId, action, serializeSnapshot(updatedSnapshot), now);
        // Replay 行为继承原 turn 的单轮 override；若新请求显式带了 override（极少见）以新请求为准
        com.lifepilot.interaction.web.model.SessionConfigOverride overrideForReplay =
                request.singleTurnOverride() != null ? request.singleTurnOverride() : snapshot.singleTurnOverride();
        return new ResolvedTurnRequest(
                turnId,
                action,
                resolvedContent,
                mergeAttachmentIds(snapshot.attachmentIds(), request.attachmentIds()),
                snapshot.preferredProvider(),
                overrideForReplay,
                toRecoveryContextPayload(recoveryContext)
        );
    }

    private String buildResumeContent(String originalContent, String resumeInput) {
        String normalizedOriginal = originalContent != null ? originalContent.strip() : "";
        String normalizedResumeInput = resumeInput != null ? resumeInput.strip() : "";
        if (normalizedResumeInput.isBlank()) {
            return normalizedOriginal;
        }
        if (normalizedOriginal.isBlank()) {
            return """
                    <resume_user_input>
                    %s
                    </resume_user_input>
                    """.formatted(normalizedResumeInput).strip();
        }
        return """
                %s

                <resume_user_input>
                %s
                </resume_user_input>
                """.formatted(normalizedOriginal, normalizedResumeInput).strip();
    }

    private String resolveRestartVisibleContent(ChatRequest request,
                                                String snapshotContent,
                                                boolean hasRecoveryAction) {
        String visibleContent = request.visibleContentOrContent();
        if (hasRecoveryAction && visibleContent.isBlank()) {
            return snapshotContent;
        }
        return visibleContent;
    }

    private String buildRecoveryActionInstruction(ChatTurnAction action,
                                                  TurnRecoveryActionRequest recoveryAction,
                                                  String userSupplement) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> checkpoint = recoveryAction.toCheckpointMap();
        lines.add(recoveryInstructionOpeningLine(action, recoveryAction));
        appendInstructionLine(lines, "恢复动作", recoveryAction.label());
        appendInstructionLine(lines, "动作说明", recoveryAction.description());
        appendInstructionLine(lines, "恢复模式", recoveryAction.mode());
        appendInstructionLine(lines, "继续策略", TaskRecoverySummaryBuilder.resumeStrategy(action.name(), checkpoint));
        appendInstructionLine(lines, "调用 ID", recoveryAction.callId());
        appendInstructionLine(lines, "目标", targetAsLine(recoveryAction));
        appendInstructionLine(lines, "缺失能力", missingCapabilitiesAsLine(recoveryAction.missingCapabilities()));
        appendInstructionLine(lines, "执行类型", recoveryAction.executionKind());
        appendInstructionLine(lines, "操作", recoveryAction.action());
        appendInstructionLine(lines, "中断状态", interruptedAsLine(recoveryAction.interrupted()));
        appendInstructionLine(lines, "关联对象", subjectAsLine(
                recoveryAction.subjectLabel(),
                recoveryAction.subjectNames()));
        appendInstructionLine(lines, "工作目录", recoveryAction.workingDirectory());
        appendInstructionLine(lines, "上次输入", recoveryAction.inputSummary());
        appendInstructionLine(lines, "上次输入详情", truncateRecoveryText(
                recoveryAction.inputDetail(),
                RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
        appendInstructionLine(lines, "上次输出", recoveryAction.outputSummary());
        appendInstructionLine(lines, "详细输出", truncateRecoveryText(
                recoveryAction.outputDetail(),
                RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
        appendInstructionLine(lines, "相关文件", recoveryAction.generatedFilePath());
        appendInstructionLine(lines, "产物复用", generatedFileReuseLine(recoveryAction.generatedFilePath()));
        appendInstructionLine(lines, "产物引用", artifactRefsAsLine(recoveryAction.artifactRefs()));
        appendInstructionLine(lines, "恢复提示", recoveryAction.recoveryHint());
        appendInstructionLine(lines, "恢复计划", valuesAsLine(compactRecoveryNextActions(recoveryAction.nextActions())));
        appendInstructionLine(lines, "用户补充", userSupplement);
        lines.add(recoveryInstructionClosingLine(action, recoveryAction));
        return String.join("\n", lines).strip();
    }

    private String buildTaskRecoveryInstruction(ChatTurnAction action,
                                                Map<String, Object> taskRecovery) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> checkpoint = mapValue(taskRecovery.get("checkpoint"));
        lines.add(action == ChatTurnAction.RESTART
                ? "重新开始上一轮任务。"
                : "按上一轮恢复计划继续。");
        appendInstructionLine(lines, "恢复标题", stringValue(taskRecovery.get("title")));
        appendInstructionLine(lines, "恢复提示", stringValue(taskRecovery.get("detail")));
        appendInstructionLine(lines, "恢复动作", stringValue(taskRecovery.get("actionLabel")));
        appendInstructionLine(lines, "恢复模式", stringValue(taskRecovery.get("resumeMode")));
        appendInstructionLine(lines, "继续策略", firstPresent(
                stringValue(taskRecovery.get("resumeStrategy")),
                TaskRecoverySummaryBuilder.resumeStrategy(action.name(), checkpoint)));
        appendInstructionLine(lines, "恢复计划", valuesAsLine(compactRecoveryNextActions(
                stringListValue(taskRecovery.get("nextActions")))));
        if (!checkpoint.isEmpty()) {
            appendInstructionLine(lines, "调用 ID", stringValue(checkpoint.get("callId")));
            appendInstructionLine(lines, "动作说明", stringValue(checkpoint.get("recoveryActionDescription")));
            appendInstructionLine(lines, "目标", checkpointTargetAsLine(checkpoint));
            appendInstructionLine(lines, "缺失能力", missingCapabilitiesAsLine(checkpoint.get("missingCapabilities")));
            appendInstructionLine(lines, "执行类型", stringValue(checkpoint.get("executionKind")));
            appendInstructionLine(lines, "操作", stringValue(checkpoint.get("action")));
            appendInstructionLine(lines, "中断状态", interruptedAsLine(checkpoint.get("interrupted")));
            appendInstructionLine(lines, "关联对象", subjectAsLine(
                    checkpoint.get("subjectLabel"),
                    checkpoint.get("subjectNames")));
            appendInstructionLine(lines, "工作目录", stringValue(checkpoint.get("workingDirectory")));
            appendInstructionLine(lines, "上次输入", stringValue(checkpoint.get("inputSummary")));
            appendInstructionLine(lines, "上次输入详情", truncateRecoveryText(
                    stringValue(checkpoint.get("inputDetail")),
                    RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
            appendInstructionLine(lines, "上次输出", stringValue(checkpoint.get("outputSummary")));
            appendInstructionLine(lines, "详细输出", truncateRecoveryText(
                    stringValue(checkpoint.get("outputDetail")),
                    RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
            appendInstructionLine(lines, "相关文件", stringValue(checkpoint.get("generatedFilePath")));
            appendInstructionLine(lines, "产物复用", generatedFileReuseLine(
                    stringValue(checkpoint.get("generatedFilePath"))));
            appendInstructionLine(lines, "产物引用", artifactRefsAsLine(checkpoint.get("artifactRefs")));
        }
        lines.add(action == ChatTurnAction.RESTART
                ? "请重新开始这一轮，并优先处理恢复摘要中的卡点。"
                : "请按恢复计划继续，保留已经完成的内容。");
        return String.join("\n", lines).strip();
    }

    private String recoveryInstructionOpeningLine(ChatTurnAction action, TurnRecoveryActionRequest recoveryAction) {
        if (action == ChatTurnAction.RESTART) {
            return "重新开始上一轮任务。";
        }
        return hasRecoveryCheckpoint(recoveryAction)
                ? "从上一轮断点继续。"
                : "按上一轮恢复计划继续。";
    }

    private String recoveryInstructionClosingLine(ChatTurnAction action, TurnRecoveryActionRequest recoveryAction) {
        boolean hasCheckpoint = hasRecoveryCheckpoint(recoveryAction);
        if (action == ChatTurnAction.RESTART) {
            return hasCheckpoint
                    ? "请重新开始这一轮，并优先修正这个失败点。"
                    : "请重新开始这一轮，保留可复用信息并按恢复计划推进。";
        }
        return hasCheckpoint
                ? "请从这个失败点继续，不要重复已经完成的步骤。"
                : "请按恢复计划继续，保留已经完成的内容。";
    }

    private boolean hasRecoveryCheckpoint(TurnRecoveryActionRequest recoveryAction) {
        return !recoveryAction.toCheckpointMap().isEmpty();
    }

    private void appendInstructionLine(List<String> lines, String label, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value.strip());
        }
    }

    @Nullable
    private String targetAsLine(TurnRecoveryActionRequest recoveryAction) {
        String target = firstPresent(recoveryAction.toolName(), recoveryAction.toolId());
        String category = failureCategoryAsLine(recoveryAction.category());
        if ((target == null || target.isBlank()) && (category == null || category.isBlank())) {
            return null;
        }
        if (target == null || target.isBlank()) {
            return category;
        }
        return category == null || category.isBlank()
                ? target
                : target + "（" + category + "）";
    }

    @Nullable
    private String checkpointTargetAsLine(Map<String, Object> checkpoint) {
        String target = firstPresent(
                stringValue(checkpoint.get("toolName")),
                stringValue(checkpoint.get("toolId")));
        String category = failureCategoryAsLine(checkpoint.get("failureCategory"));
        if ((target == null || target.isBlank()) && (category == null || category.isBlank())) {
            return null;
        }
        if (target == null || target.isBlank()) {
            return category;
        }
        return category == null || category.isBlank()
                ? target
                : target + "（" + category + "）";
    }

    @Nullable
    private String failureCategoryAsLine(@Nullable Object value) {
        return ToolExecutionSummarySupport.failureCategoryDisplay(stringValue(value));
    }

    @Nullable
    private String truncateRecoveryText(@Nullable String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }

    @Nullable
    private List<String> mergeAttachmentIds(@Nullable List<String> originalAttachmentIds,
                                            @Nullable List<String> requestAttachmentIds) {
        if ((originalAttachmentIds == null || originalAttachmentIds.isEmpty())
                && (requestAttachmentIds == null || requestAttachmentIds.isEmpty())) {
            return null;
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (originalAttachmentIds != null) {
            merged.addAll(originalAttachmentIds);
        }
        if (requestAttachmentIds != null) {
            merged.addAll(requestAttachmentIds);
        }
        return merged.isEmpty() ? null : List.copyOf(merged);
    }

    private String serializeSnapshot(TurnRequestSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 turn 请求快照失败", e);
        }
    }

    private TurnRequestSnapshot deserializeSnapshot(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, TurnRequestSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 turn 请求快照失败", e);
        }
    }

    private TurnRecoveryContext buildRecoveryContext(ChatTurnRecord turn,
                                                     ChatTurnAction action,
                                                     String resumeInput,
                                                     Instant now) {
        Map<String, Object> taskRecovery = loadTaskRecoveryFromAssistant(turn.assistantEntryId());
        return buildRecoveryContext(turn, action, resumeInput, now, taskRecovery, null);
    }

    private TurnRecoveryContext buildRecoveryContext(ChatTurnRecord turn,
                                                     ChatTurnAction action,
                                                     String resumeInput,
                                                     Instant now,
                                                     Map<String, Object> taskRecovery) {
        return buildRecoveryContext(turn, action, resumeInput, now, taskRecovery, null);
    }

    private TurnRecoveryContext buildRecoveryContext(ChatTurnRecord turn,
                                                     ChatTurnAction action,
                                                     String resumeInput,
                                                     Instant now,
                                                     Map<String, Object> taskRecovery,
                                                     @Nullable TurnRecoveryActionRequest recoveryAction) {
        Map<String, Object> checkpoint = compactRecoveryCheckpoint(mergeRecoveryCheckpoint(
                mapValue(taskRecovery.get("checkpoint")),
                recoveryAction != null ? recoveryAction.toCheckpointMap() : Map.of()));
        List<String> nextActions = compactRecoveryNextActions(recoveryAction != null && recoveryAction.nextActions() != null
                ? recoveryAction.nextActions()
                : stringListValue(taskRecovery.get("nextActions")));
        String resumeStrategy = firstPresent(
                stringValue(taskRecovery.get("resumeStrategy")),
                TaskRecoverySummaryBuilder.resumeStrategy(action.name(), checkpoint));
        return new TurnRecoveryContext(
                action.name(),
                normalizeBlank(resumeInput),
                normalizeBlank(turn.latestTraceId()),
                normalizeBlank(turn.assistantEntryId()),
                firstPresent(
                        stringValue(taskRecovery.get("title")),
                        recoveryAction != null ? recoveryAction.label() : null),
                firstPresent(
                        stringValue(taskRecovery.get("detail")),
                        recoveryAction != null ? recoveryAction.recoveryHint() : null),
                resumeStrategy,
                checkpoint.isEmpty() ? null : checkpoint,
                nextActions.isEmpty() ? null : nextActions,
                now.toString()
        );
    }

    private Map<String, Object> mergeRecoveryCheckpoint(Map<String, Object> base,
                                                        Map<String, Object> override) {
        if (base.isEmpty()) {
            return override;
        }
        if (override.isEmpty()) {
            return base;
        }
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return Map.copyOf(merged);
    }

    private Map<String, Object> compactRecoveryCheckpoint(Map<String, Object> checkpoint) {
        if (checkpoint.isEmpty()) {
            return Map.of();
        }
        String outputDetail = truncateRecoveryText(
                stringValue(checkpoint.get("outputDetail")),
                RECOVERY_OUTPUT_DETAIL_MAX_LENGTH);
        String inputDetail = truncateRecoveryText(
                stringValue(checkpoint.get("inputDetail")),
                RECOVERY_OUTPUT_DETAIL_MAX_LENGTH);
        if (Objects.equals(outputDetail, checkpoint.get("outputDetail"))
                && Objects.equals(inputDetail, checkpoint.get("inputDetail"))) {
            return checkpoint;
        }
        Map<String, Object> compacted = new LinkedHashMap<>(checkpoint);
        if (inputDetail == null) {
            compacted.remove("inputDetail");
        } else {
            compacted.put("inputDetail", inputDetail);
        }
        if (outputDetail == null) {
            compacted.remove("outputDetail");
        } else {
            compacted.put("outputDetail", outputDetail);
        }
        return Map.copyOf(compacted);
    }

    private List<String> compactRecoveryNextActions(@Nullable List<?> nextActions) {
        if (nextActions == null || nextActions.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<String> compacted = new ArrayList<>();
        for (Object nextAction : nextActions) {
            String text = truncateRecoveryText(
                    stringValue(nextAction),
                    RECOVERY_NEXT_ACTION_MAX_LENGTH);
            if (text != null && !text.isBlank() && seen.add(text)) {
                compacted.add(text);
            }
            if (compacted.size() >= RECOVERY_NEXT_ACTION_LIMIT) {
                break;
            }
        }
        return compacted.isEmpty() ? List.of() : List.copyOf(compacted);
    }

    private String buildRestartContent(String content,
                                       String visibleContent,
                                       @Nullable TurnRecoveryContext recoveryContext) {
        if (!Objects.equals(normalizeBlank(content), normalizeBlank(visibleContent))) {
            StringBuilder structured = new StringBuilder();
            structured.append("<restart_original_user_input>\n")
                    .append(visibleContent.strip())
                    .append("\n</restart_original_user_input>\n\n")
                    .append("<restart_instruction>\n")
                    .append(content.strip())
                    .append("\n</restart_instruction>");
            String checkpointSection = recoveryContext != null
                    ? buildRestartRecoveryCheckpointSection(recoveryContext)
                    : "";
            if (!checkpointSection.isBlank()) {
                structured.append("\n\n").append(checkpointSection);
            }
            return structured.toString();
        }
        if (recoveryContext == null) {
            return content;
        }
        return appendRestartRecoveryCheckpoint(content, recoveryContext);
    }

    private String appendRestartRecoveryCheckpoint(String content, TurnRecoveryContext recoveryContext) {
        String checkpointSection = buildRestartRecoveryCheckpointSection(recoveryContext);
        if (checkpointSection.isBlank()) {
            return content;
        }
        return content + "\n\n" + checkpointSection;
    }

    private String buildRestartRecoveryCheckpointSection(TurnRecoveryContext recoveryContext) {
        StringBuilder section = new StringBuilder("<task_recovery_checkpoint>\n");
        section.append("- 这是对上一轮未完成任务的重新开始，不是新的独立任务。\n");
        appendRecoveryLine(section, "上次 trace", recoveryContext.sourceTraceId());
        appendRecoveryLine(section, "标题", recoveryContext.title());
        appendRecoveryLine(section, "详情", recoveryContext.detail());
        appendRecoveryLine(section, "继续策略", recoveryContext.resumeStrategy());
        Map<String, Object> checkpoint = recoveryContext.checkpoint();
        boolean hasCheckpoint = checkpoint != null && !checkpoint.isEmpty();
        if (hasCheckpoint) {
            appendRecoveryLine(section, "类型", stringValue(checkpoint.get("kind")));
            appendRecoveryLine(section, "恢复模式", stringValue(checkpoint.get("recoveryActionMode")));
            appendRecoveryLine(section, "动作说明", stringValue(checkpoint.get("recoveryActionDescription")));
            appendRecoveryLine(section, "调用 ID", stringValue(checkpoint.get("callId")));
            appendRecoveryLine(section, "工具", firstPresent(
                    stringValue(checkpoint.get("toolName")),
                    stringValue(checkpoint.get("toolId"))));
            appendRecoveryLine(section, "执行类型", stringValue(checkpoint.get("executionKind")));
            appendRecoveryLine(section, "失败分类", failureCategoryAsLine(checkpoint.get("failureCategory")));
            appendRecoveryLine(section, "缺失能力", missingCapabilitiesAsLine(checkpoint.get("missingCapabilities")));
            appendRecoveryLine(section, "操作", stringValue(checkpoint.get("action")));
            appendRecoveryLine(section, "中断状态", interruptedAsLine(checkpoint.get("interrupted")));
            appendRecoveryLine(section, "工作目录", stringValue(checkpoint.get("workingDirectory")));
            appendRecoveryLine(section, "输入", stringValue(checkpoint.get("inputSummary")));
            appendRecoveryLine(section, "输入详情", truncateRecoveryText(
                    stringValue(checkpoint.get("inputDetail")),
                    RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
            appendRecoveryLine(section, "输出", stringValue(checkpoint.get("outputSummary")));
            appendRecoveryLine(section, "详细输出", truncateRecoveryText(
                    stringValue(checkpoint.get("outputDetail")),
                    RECOVERY_OUTPUT_DETAIL_MAX_LENGTH));
            appendRecoveryLine(section, "生成文件", stringValue(checkpoint.get("generatedFilePath")));
            appendRecoveryLine(section, "产物复用", generatedFileReuseLine(
                    stringValue(checkpoint.get("generatedFilePath"))));
            appendRecoveryLine(section, "产物引用", artifactRefsAsLine(checkpoint.get("artifactRefs")));
            appendRecoveryLine(section, "关联对象", subjectAsLine(
                    checkpoint.get("subjectLabel"),
                    checkpoint.get("subjectNames")));
        }
        if (recoveryContext.nextActions() != null && !recoveryContext.nextActions().isEmpty()) {
            section.append("- 建议下一步:\n");
            for (String nextAction : recoveryContext.nextActions()) {
                if (nextAction != null && !nextAction.isBlank()) {
                    section.append("  - ").append(nextAction.strip()).append('\n');
                }
            }
        }
        section.append(hasCheckpoint
                ? "- 重新开始时优先修正失败点，再完成原始请求。\n"
                : "- 重新开始时保留可复用信息，按计划完成原始请求。\n");
        section.append("</task_recovery_checkpoint>");
        return section.toString();
    }

    private void appendRecoveryLine(StringBuilder section, String label, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        section.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    @Nullable
    private String generatedFileReuseLine(@Nullable String generatedFilePath) {
        if (generatedFilePath == null || generatedFilePath.isBlank()) {
            return null;
        }
        String path = generatedFilePath.strip();
        return "已生成文件可直接复用：" + path + "。继续时先检查并引用它，不要无故重复生成或覆盖。";
    }

    @Nullable
    private String artifactRefsAsLine(@Nullable Object value) {
        if (!(value instanceof List<?> refs) || refs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Object ref : refs) {
            if (!(ref instanceof Map<?, ?> raw)) {
                continue;
            }
            String artifactId = stringValue(raw.get("artifactId"));
            if (artifactId == null) {
                continue;
            }
            String fileName = firstPresent(stringValue(raw.get("fileName")), artifactId);
            String kind = stringValue(raw.get("kind"));
            String mimeType = stringValue(raw.get("mimeType"));
            String downloadUrl = stringValue(raw.get("downloadUrl"));
            List<String> detailParts = new ArrayList<>();
            if (kind != null && !kind.isBlank()) {
                detailParts.add(kind);
            }
            if (mimeType != null && !mimeType.isBlank()) {
                detailParts.add(mimeType);
            }
            String detail = String.join("/", detailParts);
            String suffix = detail.isBlank() ? "" : "（" + detail + "）";
            lines.add(fileName + suffix + " id=" + artifactId
                    + (downloadUrl != null ? " url=" + downloadUrl : ""));
            if (lines.size() >= 8) {
                break;
            }
        }
        return lines.isEmpty()
                ? null
                : String.join("；", lines)
                + "。继续时优先复用这些产物，不要无故重复生成。";
    }

    @Nullable
    private String missingCapabilitiesAsLine(@Nullable Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                String text = stringValue(item);
                if (text != null && !text.isBlank()) {
                    lines.add(text);
                }
                continue;
            }
            String id = stringValue(raw.get("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            String kind = stringValue(raw.get("kind"));
            String source = stringValue(raw.get("source"));
            String reason = stringValue(raw.get("reason"));
            List<String> detailParts = new ArrayList<>();
            if (kind != null && !kind.isBlank()) {
                detailParts.add(kind);
            }
            if (source != null && !source.isBlank()) {
                detailParts.add(source);
            }
            if (reason != null && !reason.isBlank()) {
                detailParts.add(reason);
            }
            String detail = String.join("/", detailParts);
            lines.add(detail.isBlank() ? id : id + "（" + detail + "）");
            if (lines.size() >= 6) {
                break;
            }
        }
        return lines.isEmpty() ? null : String.join("；", lines);
    }

    @Nullable
    private String interruptedAsLine(@Nullable Object value) {
        return Boolean.TRUE.equals(value) ? "已开始但没有返回执行结果" : null;
    }

    @Nullable
    private String firstPresent(@Nullable String first, @Nullable String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @Nullable
    private String valuesAsLine(@Nullable Object value) {
        if (value instanceof List<?> items) {
            String joined = items.stream()
                    .map(this::stringValue)
                    .filter(item -> item != null && !item.isBlank())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            return joined.isBlank() ? null : joined;
        }
        return stringValue(value);
    }

    @Nullable
    private String subjectAsLine(@Nullable Object labelValue, @Nullable Object namesValue) {
        String names = valuesAsLine(namesValue);
        if (names == null || names.isBlank()) {
            return null;
        }
        String label = stringValue(labelValue);
        return label == null || label.isBlank() ? names : label + " " + names;
    }

    private void updateUserEntryContent(@Nullable String userEntryId, String content) {
        if (userEntryId == null || userEntryId.isBlank()) {
            return;
        }
        try {
            transcriptRepository.updateMessageContent(userEntryId, content);
        } catch (Exception e) {
            log.debug("更新重启后的用户消息内容失败，跳过 transcript 同步: userEntryId={}, error={}",
                    userEntryId, e.getMessage());
        }
    }

    private Map<String, Object> loadTaskRecoveryFromAssistant(@Nullable String assistantEntryId) {
        if (assistantEntryId == null || assistantEntryId.isBlank()) {
            return Map.of();
        }
        try {
            var row = transcriptRepository.findById(assistantEntryId);
            if (row.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> payload = objectMapper.readValue(row.get().payloadJson(), MAP_TYPE);
            String taskRecoveryJson = stringValue(payload.get("taskRecoveryJson"));
            if (taskRecoveryJson == null) {
                return Map.of();
            }
            Map<String, Object> taskRecovery = objectMapper.readValue(taskRecoveryJson, MAP_TYPE);
            return taskRecovery != null ? taskRecovery : Map.of();
        } catch (Exception e) {
            log.debug("读取恢复上下文失败，跳过 turn 快照增强: assistantEntryId={}, error={}",
                    assistantEntryId, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    @Nullable
    private Map<String, Object> toRecoveryContextPayload(@Nullable TurnRecoveryContext recoveryContext) {
        if (recoveryContext == null) {
            return null;
        }
        return mapValue(objectMapper.convertValue(recoveryContext, MAP_TYPE));
    }

    private List<String> stringListValue(@Nullable Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .map(this::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    public Optional<ChatTurnMemorySnapshot> findMemorySnapshot(String turnId) {
        if (chatTurnMemorySnapshotRepository == null) {
            return Optional.empty();
        }
        return chatTurnMemorySnapshotRepository.findByTurnId(turnId);
    }

    private void persistTurnMemorySnapshot(String sessionId,
                                           String turnId,
                                           String userText,
                                           Instant now) {
        if (chatTurnMemorySnapshotRepository == null || memorySpaceRepository == null) {
            return;
        }
        if (memoryAccessPolicy == null) {
            throw new IllegalStateException("MemoryAccessPolicy 未装配，无法生成记忆快照");
        }
        var personalSpace = memorySpaceRepository.ensureDefaultPersonalSpace();
        var experienceSpace = memorySpaceRepository.ensureDefaultExperienceSpace();
        ProjectContextResolution projectResolution = resolveProjectContext(sessionId);
        String projectSpaceId = null;
        if (!projectResolution.failed()) {
            var projectContext = projectResolution.context();
            if (projectContext.isolated()) {
                projectSpaceId = projectContext.projectSpaceId();
            }
        }
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository != null
                ? sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId)
                : List.of();
        boolean knowledgeBound = !knowledgeBaseIds.isEmpty();
        List<String> domainReadSpaceIds = resolveDomainReadSpaceIds(knowledgeBaseIds);
        String domainWriteSpaceId = resolveDomainWriteSpaceId(knowledgeBaseIds);
        List<String> readSpaceIds = memoryAccessPolicy.buildSnapshotReadSpaceIds(
                projectSpaceId,
                personalSpace.id(),
                experienceSpace.id(),
                domainReadSpaceIds);
        Map<String, Object> resolutionSource = new java.util.LinkedHashMap<>();
        resolutionSource.put("source", "session_config");
        resolutionSource.put("knowledgeBound", knowledgeBound);
        resolutionSource.put("resolvedDomainReadSpaces", domainReadSpaceIds);
        if (domainWriteSpaceId != null && !domainWriteSpaceId.isBlank()) {
            resolutionSource.put("resolvedDomainWriteSpaceId", domainWriteSpaceId);
        }
        if (projectResolution.failed()) {
            resolutionSource.put("projectResolutionFailed", true);
            resolutionSource.put("projectResolutionReason", projectResolution.reason());
        }
        if (projectSpaceId != null) {
            resolutionSource.put("resolvedProjectSpaceId", projectSpaceId);
        }
        var autoLearningBoundary = MemoryUserBoundaryPolicy.autoLearningBoundary(userText);
        if (autoLearningBoundary.skip()) {
            resolutionSource.put("autoLearningSkippedByUser", true);
            resolutionSource.put("autoLearningSkipReason", autoLearningBoundary.reason());
            log.debug("本轮自动记忆学习已按用户边界关闭: sessionId={}, turnId={}, reason={}",
                    sessionId, turnId, autoLearningBoundary.reason());
        }
        boolean learningEnabled = !projectResolution.failed() && !autoLearningBoundary.skip();
        ChatTurnMemorySnapshot snapshot = new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                personalSpace.id(),
                experienceSpace.id(),
                domainWriteSpaceId,
                projectSpaceId,
                readSpaceIds,
                knowledgeBaseIds,
                learningEnabled && !knowledgeBound,
                learningEnabled && knowledgeBound && domainWriteSpaceId != null && !domainWriteSpaceId.isBlank(),
                learningEnabled,
                resolutionSource,
                now
        );
        chatTurnMemorySnapshotRepository.save(snapshot);
    }

    /**
     * 解析当前会话对应的项目上下文。
     *
     * <p>主账户/SHARED 项目可正常返回无项目写入目标；查询或解析失败时返回 failed 结果，
     * 调用方据此关闭本轮自动学习，避免写入错误空间。</p>
     */
    private ProjectContextResolution resolveProjectContext(String sessionId) {
        if (chatSessionRepository == null || projectContextResolver == null) {
            log.warn("项目上下文依赖未配置，本轮自动学习关闭: sessionId={}", sessionId);
            return ProjectContextResolution.failed("project_context_dependency_missing");
        }
        Optional<ChatSession> session;
        try {
            session = chatSessionRepository.findById(sessionId);
        } catch (Exception e) {
            log.warn("查询会话项目归属失败，本轮自动学习关闭: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return ProjectContextResolution.failed("chat_session_lookup_failed");
        }
        if (session.isEmpty()) {
            try {
                return ProjectContextResolution.resolved(projectContextResolver.resolve(null));
            } catch (Exception e) {
                log.warn("解析主账户项目上下文失败，本轮自动学习关闭: sessionId={}, error={}",
                        sessionId, e.getMessage());
                return ProjectContextResolution.failed("personal_context_resolution_failed");
            }
        }
        String projectId = session.get().projectId();
        try {
            return ProjectContextResolution.resolved(projectContextResolver.resolve(projectId));
        } catch (Exception e) {
            log.warn("解析项目空间失败，本轮自动学习关闭: sessionId={}, projectId={}, error={}",
                    sessionId, projectId != null ? projectId : "<personal>", e.getMessage());
            return ProjectContextResolution.failed("project_context_resolution_failed");
        }
    }

    private List<String> resolveDomainReadSpaceIds(List<String> knowledgeBaseIds) {
        if (memorySpaceRepository == null) {
            return List.of();
        }
        List<String> readSpaceIds = new ArrayList<>();
        if (knowledgeBaseIds != null) {
            for (String knowledgeBaseId : knowledgeBaseIds) {
                MemorySpace space = memorySpaceRepository.ensureKnowledgeBaseDomainSpace(knowledgeBaseId);
                readSpaceIds.add(space.id());
            }
        }
        return readSpaceIds.stream().distinct().toList();
    }

    @Nullable
    private String resolveDomainWriteSpaceId(List<String> knowledgeBaseIds) {
        if (memorySpaceRepository == null) {
            return null;
        }
        if (knowledgeBaseIds != null && knowledgeBaseIds.size() == 1) {
            return memorySpaceRepository.ensureKnowledgeBaseDomainSpace(knowledgeBaseIds.getFirst()).id();
        }
        return null;
    }

    /**
     * 构造 assistant 消息持久化用的 payload Map（含推理模型多轮契约所需字段）。
     *
     * <p>对应 {@code session_transcript_entries.payload_json} 扩展 schema：
     * <ul>
     *   <li>{@code content} —— 主文本（必有）；</li>
     *   <li>{@code reasoning_content} —— 推理过程文本（DeepSeek/Qwen3 等返回，多轮回传契约要求）；</li>
     *   <li>{@code reasoning_signature} —— Anthropic thinking block 签名；</li>
     *   <li>{@code tool_calls} —— Provider 返回的工具调用列表；</li>
     *   <li>{@code provider_metadata} —— 厂商私有元数据；</li>
     *   <li>{@code model_id} / {@code provider_id} / {@code tokens} —— 调用元数据（对账、排障）。</li>
     * </ul>
     *
     * <p>仅在对应字段非空 / 非默认值时写入；读取方按字段是否存在决定是否启用对应能力。</p>
     *
     * @param response LLM 响应富字段
     * @return 可序列化为 payload_json 的 Map（保留字段插入序）
     */
    public Map<String, Object> buildAssistantPayload(LlmResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", "assistant");
        payload.put("content", response.content());
        if (response.reasoningContent() != null) {
            payload.put("reasoning_content", response.reasoningContent());
        }
        if (response.reasoningSignature() != null) {
            payload.put("reasoning_signature", response.reasoningSignature());
        }
        if (!response.toolCalls().isEmpty()) {
            payload.put("tool_calls", response.toolCalls());
        }
        if (!response.providerMetadata().isEmpty()) {
            payload.put("provider_metadata", response.providerMetadata());
        }
        payload.put("model_id", response.modelName());
        payload.put("provider_id", response.providerId());
        payload.put("tokens", Map.of(
                "input", response.inputTokens(),
                "output", response.outputTokens(),
                "reasoning", response.reasoningTokens() != null ? response.reasoningTokens() : 0,
                "cached_input", response.cachedInputTokens()
        ));
        return payload;
    }

    private record TurnRequestSnapshot(
            String content,
            @Nullable List<String> attachmentIds,
            @Nullable String preferredProvider,
            @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride singleTurnOverride,
            @Nullable TurnRecoveryContext lastRecoveryContext
    ) {
        private TurnRequestSnapshot(String content,
                                    @Nullable List<String> attachmentIds,
                                    @Nullable String preferredProvider,
                                    @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride singleTurnOverride) {
            this(content, attachmentIds, preferredProvider, singleTurnOverride, null);
        }

        private TurnRequestSnapshot withLastRecoveryContext(@Nullable TurnRecoveryContext recoveryContext) {
            return new TurnRequestSnapshot(
                    content,
                    attachmentIds,
                    preferredProvider,
                    singleTurnOverride,
                    recoveryContext
            );
        }

        private TurnRequestSnapshot withContent(String nextContent) {
            return new TurnRequestSnapshot(
                    nextContent,
                    attachmentIds,
                    preferredProvider,
                    singleTurnOverride,
                    lastRecoveryContext
            );
        }
    }

    private record TurnRecoveryContext(
            String action,
            @Nullable String resumeInput,
            @Nullable String sourceTraceId,
            @Nullable String assistantEntryId,
            @Nullable String title,
            @Nullable String detail,
            @Nullable String resumeStrategy,
            @Nullable Map<String, Object> checkpoint,
            @Nullable List<String> nextActions,
            String capturedAt
    ) {
    }

    private record MemoryLearningBoundary(boolean learningEnabled, @Nullable String reason) {
        private static MemoryLearningBoundary enabled() {
            return new MemoryLearningBoundary(true, null);
        }

        private static MemoryLearningBoundary disabled(String reason) {
            return new MemoryLearningBoundary(false, reason);
        }
    }

    public record ResolvedTurnRequest(
            String turnId,
            ChatTurnAction action,
            String content,
            @Nullable List<String> attachmentIds,
            @Nullable String preferredProvider,
            @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride singleTurnOverride,
            @Nullable Map<String, Object> turnRecoveryContext
    ) {
        public ResolvedTurnRequest {
            turnRecoveryContext = turnRecoveryContext != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(turnRecoveryContext))
                    : null;
        }

        public ResolvedTurnRequest(String turnId,
                                   ChatTurnAction action,
                                   String content,
                                   @Nullable List<String> attachmentIds,
                                   @Nullable String preferredProvider,
                                   @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride singleTurnOverride) {
            this(turnId, action, content, attachmentIds, preferredProvider, singleTurnOverride, null);
        }
    }
}
