package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.CompactionEngine;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.service.SessionTitleGenerator;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.memory.experience.ContrastiveLearner;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.experience.ExperienceSummarizer;
import com.lifepilot.memory.experience.SubtaskReflector;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.PendingDecisionItem;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.TaskStateItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 持久化处理器。
 * <p>负责将主对话链路中的 transcript、附件、注入记录、工作状态和异步记忆后处理统一落盘。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class AgentPersistenceHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentPersistenceHandler.class);
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual().start(command);
    private static final Pattern RESUME_INPUT_PATTERN = Pattern.compile(
            "<resume_user_input>\\s*(.*?)\\s*</resume_user_input>",
            Pattern.DOTALL
    );

    /**
     * document.parse 系统提示块匹配正则：吃掉前置的空白行（含 \n\n）+ marker + 内容 + 闭合 marker。
     *
     * <p>{@link BrowserIngressService} 在用户消息末尾追加 hint 引导模型调用
     * {@code document.parse}，模型仍然需要在 goal 原文中看到该 hint；但持久化到
     * transcript 时必须剥离，避免前端历史回显时把"系统提示 + attachmentId"
     * 当作用户原话展示。</p>
     */
    private static final Pattern DOCUMENT_HINT_PATTERN = Pattern.compile(
            "\\s*"
                    + Pattern.quote(BrowserIngressService.DOCUMENT_HINT_BEGIN)
                    + ".*?"
                    + Pattern.quote(BrowserIngressService.DOCUMENT_HINT_END),
            Pattern.DOTALL
    );

    @SuppressWarnings("unused")
    private final AgentConfigProperties config;
    @Nullable
    private final SessionWorkspaceService workspaceService;
    private final TranscriptStore transcriptStore;
    @Nullable
    private final RealtimeExtractor realtimeExtractor;
    @Nullable
    private final InjectionRecordRepository injectionRecordRepository;
    @Nullable
    private final AttachmentRepository attachmentRepository;
    @Nullable
    private final ExperienceSummarizer experienceSummarizer;
    @Nullable
    private final EffectivenessTracker effectivenessTracker;
    @Nullable
    private final ContrastiveLearner contrastiveLearner;
    @Nullable
    private final SubtaskReflector subtaskReflector;
    @Nullable
    private final CompactionEngine compactionEngine;
    @Nullable
    private final ChatTurnService chatTurnService;
    @Nullable
    private final SessionTitleGenerator sessionTitleGenerator;

    public AgentPersistenceHandler(
            AgentConfigProperties config,
            @Nullable SessionWorkspaceService workspaceService,
            TranscriptStore transcriptStore,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable AttachmentRepository attachmentRepository,
            @Nullable ExperienceSummarizer experienceSummarizer,
            @Nullable EffectivenessTracker effectivenessTracker,
            @Nullable ContrastiveLearner contrastiveLearner,
            @Nullable SubtaskReflector subtaskReflector,
            @Nullable CompactionEngine compactionEngine,
            @Nullable ChatTurnService chatTurnService,
            @Nullable SessionTitleGenerator sessionTitleGenerator) {
        this.config = config;
        this.workspaceService = workspaceService;
        this.transcriptStore = transcriptStore;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.attachmentRepository = attachmentRepository;
        this.experienceSummarizer = experienceSummarizer;
        this.effectivenessTracker = effectivenessTracker;
        this.contrastiveLearner = contrastiveLearner;
        this.subtaskReflector = subtaskReflector;
        this.compactionEngine = compactionEngine;
        this.chatTurnService = chatTurnService;
        this.sessionTitleGenerator = sessionTitleGenerator;
    }

    public void saveWorkspaceForSuspend(ReactAgentState state) {
        if (workspaceService == null || state.suspendReason() == null) {
            return;
        }
        try {
            switch (state.suspendReason()) {
                case SuspendReason.UserConfirmation confirmation ->
                        workspaceService.savePendingDecision(state.sessionId(), new PendingDecisionItem(
                                "等待确认",
                                "工具调用等待用户确认：" + confirmation.toolId(),
                                buildSuspendPayload(state.suspendReason()),
                                100,
                                state.traceId(),
                                state.traceId(),
                                null));
                default ->
                        workspaceService.saveTaskState(state.sessionId(), new TaskStateItem(
                                "任务已暂停",
                                formatSuspendSummary(state.suspendReason()),
                                buildSuspendPayload(state.suspendReason()),
                                60,
                                state.traceId(),
                                state.traceId(),
                                null));
            }
        } catch (Exception e) {
            log.warn("保存暂停工作区失败：sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    public void resolveWorkspaceForTrace(String sessionId, @Nullable String traceId) {
        if (workspaceService == null || traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            workspaceService.resolveByTaskId(sessionId, traceId);
            workspaceService.resolveBySourceTraceId(sessionId, traceId);
        } catch (Exception e) {
            log.warn("解析工作区轨迹引用失败：sessionId={}, traceId={}, error={}",
                    sessionId, traceId, e.getMessage());
        }
    }

    public void persistUserMessage(ReactAgentState state) {
        if (state.goal() == null || state.goal().isBlank()) {
            return;
        }
        try {
            boolean visibleToUser = !isA2uiSignalMessage(state.goal());
            transcriptStore.appendUserMessage(
                    state.sessionId(), state.turnId(), state.goal(),
                    state.traceId(), visibleToUser, null);
        } catch (Exception e) {
            log.warn("写入用户消息失败：sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    @Nullable
    public String persistUserMessageReturningId(ReactAgentState state) {
        return persistUserMessageReturningId(state, ChatTurnAction.SEND);
    }

    @Nullable
    public String persistUserMessageReturningId(ReactAgentState state, ChatTurnAction action) {
        if (state.goal() == null || state.goal().isBlank()) {
            return null;
        }
        try {
            String resumeInput = action == ChatTurnAction.RESUME
                    ? extractResumeUserInput(state.goal())
                    : null;
            if (resumeInput != null && !resumeInput.isBlank()) {
                String entryId = transcriptStore.appendUserMessage(
                        state.sessionId(),
                        state.turnId(),
                        resumeInput,
                        state.traceId(),
                        null
                );
                if (chatTurnService != null && state.turnId() != null && entryId != null) {
                    chatTurnService.bindUserEntry(state.sessionId(), state.turnId(), entryId);
                }
                return entryId;
            }
            if (chatTurnService != null && state.turnId() != null) {
                var existingTurn = chatTurnService.findBySessionIdAndTurnId(state.sessionId(), state.turnId());
                if (existingTurn.isPresent() && existingTurn.get().userEntryId() != null
                        && !existingTurn.get().userEntryId().isBlank()) {
                    return existingTurn.get().userEntryId();
                }
            }
            // A2UI 信号消息对模型可见但不展示给用户，避免原始信号数据作为气泡出现
            boolean visibleToUser = !isA2uiSignalMessage(state.goal());
            // 持久化前剥离 document.parse hint，避免操作元数据回显到用户气泡
            String persistedGoal = stripDocumentParseHint(state.goal());
            String entryId = transcriptStore.appendUserMessage(
                    state.sessionId(),
                    state.turnId(),
                    persistedGoal,
                    state.traceId(),
                    visibleToUser,
                    null
            );
            if (chatTurnService != null && state.turnId() != null && entryId != null) {
                chatTurnService.bindUserEntry(state.sessionId(), state.turnId(), entryId);
            }
            return entryId;
        } catch (Exception e) {
            log.warn("写入用户消息失败：sessionId={}, error={}", state.sessionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 剥离用户消息中的 document.parse 系统提示块。
     *
     * <p>{@link BrowserIngressService} 用 sentinel marker 包裹 hint，模型推理时
     * 仍能看到（{@code state.goal()} 原文不变），仅在写入 user transcript 前由本方法
     * 移除，避免前端回看历史时把"系统提示 + attachmentId"当作用户原话展示。</p>
     *
     * @param text 原始用户消息（可能含 hint）
     * @return 剥离 hint 后的文本；若无 hint 或 text 为 null/空，原样返回
     */
    static String stripDocumentParseHint(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!text.contains(BrowserIngressService.DOCUMENT_HINT_BEGIN)) {
            return text;
        }
        String stripped = DOCUMENT_HINT_PATTERN.matcher(text).replaceAll("");
        // 用户原文末尾尾随空白做轻量清理；不做 strip()，避免吃掉合法的内部缩进
        return stripped.stripTrailing();
    }

    /**
     * 判断消息是否为 A2UI 信号交互产生的自动化文本。
     *
     * <p>该文本由 {@code MessageContent.EventMessage.toPlainText()} 生成，
     * 以固定前缀 "用户通过 UI 组件触发了操作：" 开头。此类消息需保留在模型上下文中供推理使用，
     * 但不应作为用户气泡展示在聊天界面。</p>
     */
    static boolean isA2uiSignalMessage(@Nullable String goal) {
        return goal != null && goal.stripLeading().startsWith("用户通过 UI 组件触发了操作：");
    }

    @Nullable
    private String extractResumeUserInput(@Nullable String goal) {
        if (goal == null || goal.isBlank()) {
            return null;
        }
        Matcher matcher = RESUME_INPUT_PATTERN.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        String resumeInput = matcher.group(1);
        return resumeInput != null ? resumeInput.strip() : null;
    }

    @Nullable
    public String persistAssistantMessage(ReactAgentState state,
                                          @Nullable String reactStepsJson) {
        String output = state.finalOutput();
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return transcriptStore.appendAssistantMessage(
                    state.sessionId(),
                    state.turnId(),
                    output,
                    state.reasoningSummary(),
                    state.traceId(),
                    null,
                    reactStepsJson,
                    state.completionMode(),
                    state.resumedFromTraceId(),
                    null
            );
        } catch (Exception e) {
            log.warn("写入助手消息失败：sessionId={}, error={}", state.sessionId(), e.getMessage());
            return null;
        }
    }

    @Nullable
    public String persistAssistantMessageWithA2ui(ReactAgentState state,
                                                  @Nullable String finalContent,
                                                  @Nullable String reasoningSummary,
                                                  @Nullable String a2uiJson,
                                                  @Nullable String reactStepsJson) {
        if ((finalContent == null || finalContent.isBlank()) && a2uiJson == null) {
            return null;
        }
        try {
            return transcriptStore.appendAssistantMessage(
                    state.sessionId(),
                    state.turnId(),
                    finalContent != null ? finalContent : "",
                    reasoningSummary,
                    state.traceId(),
                    a2uiJson,
                    reactStepsJson,
                    state.completionMode(),
                    state.resumedFromTraceId(),
                    null
            );
        } catch (Exception e) {
            log.warn("写入助手消息失败：sessionId={}, error={}", state.sessionId(), e.getMessage());
            return null;
        }
    }

    public void persistInjectionRecord(@Nullable String sourceEntryId,
                                       @Nullable String sessionId,
                                       @Nullable String sourceTraceId,
                                       @Nullable AgentLoopContext loopContext) {
        List<String> entityIds = loopContext != null ? loopContext.getInjectedEntityIds() : List.of();
        if (injectionRecordRepository == null || entityIds.isEmpty()
                || sourceEntryId == null || sourceEntryId.isBlank()) {
            return;
        }
        try {
            injectionRecordRepository.save(sourceEntryId, sessionId, sourceTraceId, entityIds);
            log.debug("注入记录已持久化：sourceEntryId={}, entityCount={}", sourceEntryId, entityIds.size());
        } catch (Exception e) {
            log.warn("持久化注入记录失败：sourceEntryId={}, error={}", sourceEntryId, e.getMessage());
        }
    }

    public void persistToolMediaAttachments(@Nullable String assistantEntryId,
                                            @Nullable String sessionId,
                                            List<MediaDataExtractor.MediaItem> toolMediaItems) {
        if (attachmentRepository == null || assistantEntryId == null || toolMediaItems.isEmpty()) {
            return;
        }
        for (MediaDataExtractor.MediaItem mediaItem : toolMediaItems) {
            try {
                String ext = guessExtension(mediaItem.mediaType());
                String fileName = mediaItem.fieldName() + "." + ext;
                String dataUri = "data:" + mediaItem.mediaType() + ";base64," + mediaItem.data();
                long sizeBytes = Math.round(mediaItem.data().length() * 0.75);
                attachmentRepository.saveForEntry(assistantEntryId, sessionId,
                        fileName, "", sizeBytes, mediaItem.mediaType(), dataUri);
            } catch (Exception e) {
                log.warn("保存工具媒体附件失败：field={}, error={}",
                        mediaItem.fieldName(), e.getMessage());
            }
        }
    }

    public void persistUserMediaAttachments(@Nullable String userEntryId,
                                            @Nullable String sessionId,
                                            @Nullable List<MediaContent> mediaContents) {
        if (attachmentRepository == null || userEntryId == null
                || mediaContents == null || mediaContents.isEmpty()) {
            return;
        }
        for (MediaContent mediaContent : mediaContents) {
            try {
                String fileName = mediaContent.fileName() != null ? mediaContent.fileName()
                        : "media-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                        + "." + guessExtension(mediaContent.mimeType());
                String dataUri = "data:" + mediaContent.mimeType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(mediaContent.data());
                attachmentRepository.saveForEntry(userEntryId, sessionId,
                        fileName, "", mediaContent.sizeBytes(), mediaContent.mimeType(), dataUri);
            } catch (Exception e) {
                log.warn("保存用户媒体附件失败：sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }

    public void asyncPostProcess(ReactAgentState finalState) {
        Thread.startVirtualThread(() -> {
            // 互相独立的后处理步骤并行执行，减少总耗时
            var compactionFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (compactionEngine != null) {
                        compactionEngine.compactIfNeeded(
                                finalState.sessionId(),
                                finalState.traceId(),
                                finalState.preferredProvider()
                        );
                    }
                } catch (Exception e) {
                    log.warn("会话压缩后处理失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            }, VIRTUAL_EXECUTOR);

            var extractionFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (realtimeExtractor != null && finalState.finalOutput() != null) {
                        realtimeExtractor.extractAsync(
                                finalState.sessionId(),
                                finalState.turnId(),
                                finalState.goal(),
                                finalState.finalOutput());
                    }
                } catch (Exception e) {
                    log.warn("实时记忆抽取失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            }, VIRTUAL_EXECUTOR);

            // experienceSummarizer 产出 newExperience，contrastiveLearner 依赖它，用 thenAccept 串联
            var experienceFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    if (experienceSummarizer != null) {
                        return experienceSummarizer.summarize(finalState);
                    }
                } catch (Exception e) {
                    log.warn("经验总结失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
                return (TemporalEntity) null;
            }, VIRTUAL_EXECUTOR).thenAccept(newExperience -> {
                try {
                    if (contrastiveLearner != null && newExperience != null) {
                        contrastiveLearner.learn(newExperience);
                    }
                } catch (Exception e) {
                    log.warn("对比学习失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            });

            var effectivenessFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (effectivenessTracker != null) {
                        effectivenessTracker.evaluate(finalState, finalState.traceId());
                    }
                } catch (Exception e) {
                    log.warn("效果评估失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            }, VIRTUAL_EXECUTOR);

            var reflectionFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (subtaskReflector != null) {
                        subtaskReflector.reflect(finalState);
                    }
                } catch (Exception e) {
                    log.warn("子任务反思失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            }, VIRTUAL_EXECUTOR);

            var titleFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (sessionTitleGenerator != null) {
                        sessionTitleGenerator.generateIfNeeded(
                                finalState.sessionId(), finalState.goal());
                    }
                } catch (Exception e) {
                    log.warn("会话标题生成失败：sessionId={}, error={}",
                            finalState.sessionId(), e.getMessage());
                }
            }, VIRTUAL_EXECUTOR);

            // 等待所有并行任务完成（fire-and-forget 语义不变，但内部并行化）
            CompletableFuture.allOf(
                    compactionFuture, extractionFuture, experienceFuture,
                    effectivenessFuture, reflectionFuture, titleFuture
            ).join();
        });
    }

    static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    static String guessExtension(@Nullable String mimeType) {
        if (mimeType == null) {
            return "bin";
        }
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

    private Map<String, Object> buildSuspendPayload(SuspendReason reason) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        switch (reason) {
            case SuspendReason.WorkflowWait workflowWait -> {
                payload.put("type", "WORKFLOW_WAIT");
                payload.put("executionId", workflowWait.executionId());
                payload.put("workflowId", workflowWait.workflowId());
                payload.put("workflowName", workflowWait.workflowName());
            }
            case SuspendReason.UserConfirmation confirmation -> {
                payload.put("type", "USER_CONFIRMATION");
                payload.put("toolId", confirmation.toolId());
                payload.put("inputJson", confirmation.inputJson());
                payload.put("riskLevel", confirmation.riskLevel());
                payload.put("confirmationId", confirmation.confirmationId());
            }
            case SuspendReason.RemoteDelegation remoteDelegation -> {
                payload.put("type", "REMOTE_DELEGATION");
                payload.put("remoteTaskId", remoteDelegation.remoteTaskId());
                payload.put("remoteAgentUrl", remoteDelegation.remoteAgentUrl());
                payload.put("delegatedGoal", remoteDelegation.delegatedGoal());
            }
            case SuspendReason.ScheduledWakeup scheduledWakeup -> {
                payload.put("type", "SCHEDULED_WAKEUP");
                payload.put("wakeupAt", scheduledWakeup.wakeupAt().toString());
                payload.put("reason", scheduledWakeup.reason());
            }
            case SuspendReason.ExternalDataWait externalDataWait -> {
                payload.put("type", "EXTERNAL_DATA_WAIT");
                payload.put("dataSourceId", externalDataWait.dataSourceId());
                payload.put("description", externalDataWait.description());
            }
        }
        return Map.copyOf(payload);
    }

    private String formatSuspendSummary(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait workflowWait ->
                    "工作流等待：" + workflowWait.workflowName();
            case SuspendReason.UserConfirmation confirmation ->
                    "用户确认等待：" + confirmation.toolId();
            case SuspendReason.RemoteDelegation remoteDelegation ->
                    "远程委派等待：" + remoteDelegation.delegatedGoal();
            case SuspendReason.ScheduledWakeup scheduledWakeup ->
                    "定时唤醒等待：" + scheduledWakeup.reason();
            case SuspendReason.ExternalDataWait externalDataWait ->
                    "外部数据等待：" + externalDataWait.description();
        };
    }
}
