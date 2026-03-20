package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 持久化处理器。
 *
 * <p>聚合会话持久化、附件落库、工作区写入和异步后处理。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class AgentPersistenceHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentPersistenceHandler.class);

    private final AgentConfigProperties config;
    private final SessionManager sessionManager;

    @Nullable private final SessionWorkspaceService workspaceService;
    @Nullable private final ConversationHistoryStore conversationHistoryStore;
    @Nullable private final RealtimeExtractor realtimeExtractor;
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final AttachmentRepository attachmentRepository;
    @Nullable private final ExperienceSummarizer experienceSummarizer;
    @Nullable private final EffectivenessTracker effectivenessTracker;
    @Nullable private final ContrastiveLearner contrastiveLearner;
    @Nullable private final SubtaskReflector subtaskReflector;

    public AgentPersistenceHandler(
            AgentConfigProperties config,
            SessionManager sessionManager,
            @Nullable SessionWorkspaceService workspaceService,
            @Nullable ConversationHistoryStore conversationHistoryStore,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable AttachmentRepository attachmentRepository,
            @Nullable ExperienceSummarizer experienceSummarizer,
            @Nullable EffectivenessTracker effectivenessTracker,
            @Nullable ContrastiveLearner contrastiveLearner,
            @Nullable SubtaskReflector subtaskReflector) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.workspaceService = workspaceService;
        this.conversationHistoryStore = conversationHistoryStore;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.attachmentRepository = attachmentRepository;
        this.experienceSummarizer = experienceSummarizer;
        this.effectivenessTracker = effectivenessTracker;
        this.contrastiveLearner = contrastiveLearner;
        this.subtaskReflector = subtaskReflector;
    }

    public void saveWorkspaceForSuspend(ReactAgentState state) {
        if (workspaceService == null || state.suspendReason() == null) {
            return;
        }
        try {
            switch (state.suspendReason()) {
                case SuspendReason.UserConfirmation confirmation ->
                        workspaceService.savePendingDecision(state.sessionId(), new PendingDecisionItem(
                                "等待用户确认",
                                "等待用户确认执行高风险工具 " + confirmation.toolId(),
                                buildSuspendPayload(state.suspendReason()),
                                100,
                                state.traceId(),
                                state.traceId(),
                                null));
                default ->
                        workspaceService.saveTaskState(state.sessionId(), new TaskStateItem(
                                "任务已挂起",
                                formatSuspendSummary(state.suspendReason()),
                                buildSuspendPayload(state.suspendReason()),
                                60,
                                state.traceId(),
                                state.traceId(),
                                null));
            }
        } catch (Exception e) {
            log.warn("挂起工作区写入失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
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
            log.warn("工作区状态关闭失败: sessionId={}, traceId={}, error={}",
                    sessionId, traceId, e.getMessage());
        }
    }

    public void persistUserMessage(ReactAgentState state) {
        if (conversationHistoryStore == null
                || state.goal() == null || state.goal().isBlank()) {
            return;
        }
        try {
            conversationHistoryStore.appendUserMessage(
                    state.sessionId(), state.goal(), state.traceId());
        } catch (Exception e) {
            log.warn("用户消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    @Nullable
    public String persistUserMessageReturningId(ReactAgentState state) {
        if (conversationHistoryStore == null
                || state.goal() == null || state.goal().isBlank()) {
            return null;
        }
        try {
            return conversationHistoryStore.appendUserMessage(
                    state.sessionId(), state.goal(), state.traceId());
        } catch (Exception e) {
            log.warn("用户消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    @Nullable
    public String persistAssistantMessage(ReactAgentState state,
                                          @Nullable String reactStepsJson) {
        if (conversationHistoryStore == null) {
            return null;
        }
        String output = state.finalOutput();
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return conversationHistoryStore.appendAssistantMessage(
                    state.sessionId(), output, state.reasoningSummary(),
                    state.traceId(), null, reactStepsJson);
        } catch (Exception e) {
            log.warn("助手消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    @Nullable
    public String persistAssistantMessageWithA2ui(ReactAgentState state,
                                                  @Nullable String finalContent,
                                                  @Nullable String reasoningSummary,
                                                  @Nullable String a2uiJson,
                                                  @Nullable String reactStepsJson) {
        if (conversationHistoryStore == null) {
            return null;
        }
        if ((finalContent == null || finalContent.isBlank()) && a2uiJson == null) {
            return null;
        }
        try {
            return conversationHistoryStore.appendAssistantMessage(
                    state.sessionId(),
                    finalContent != null ? finalContent : "",
                    reasoningSummary,
                    state.traceId(),
                    a2uiJson,
                    reactStepsJson);
        } catch (Exception e) {
            log.warn("助手消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    public void persistInjectionRecord(@Nullable String messageId,
                                       @Nullable String sessionId,
                                       @Nullable AgentLoopContext loopContext) {
        List<String> entityIds = loopContext != null
                ? loopContext.getInjectedEntityIds()
                : List.of();
        if (injectionRecordRepository == null || entityIds.isEmpty()
                || messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            injectionRecordRepository.save(messageId, sessionId, entityIds);
            log.debug("注入记录已持久化: messageId={}, entityCount={}", messageId, entityIds.size());
        } catch (Exception e) {
            log.warn("注入记录持久化失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    public void persistToolMediaAttachments(@Nullable String assistantMessageId,
                                            @Nullable String sessionId,
                                            List<MediaDataExtractor.MediaItem> toolMediaItems) {
        if (attachmentRepository == null || assistantMessageId == null || toolMediaItems.isEmpty()) {
            return;
        }
        for (var mediaItem : toolMediaItems) {
            try {
                String ext = guessExtension(mediaItem.mediaType());
                String fileName = mediaItem.fieldName() + "." + ext;
                String dataUri = "data:" + mediaItem.mediaType() + ";base64," + mediaItem.data();
                long sizeBytes = Math.round(mediaItem.data().length() * 0.75);
                attachmentRepository.save(assistantMessageId, sessionId,
                        fileName, "", sizeBytes, mediaItem.mediaType(), dataUri);
            } catch (Exception e) {
                log.warn("工具媒体附件持久化失败: field={}, error={}",
                        mediaItem.fieldName(), e.getMessage());
            }
        }
    }

    public void persistUserMediaAttachments(@Nullable String assistantMessageId,
                                            @Nullable String sessionId,
                                            @Nullable List<MediaContent> mediaContents) {
        if (attachmentRepository == null || assistantMessageId == null
                || mediaContents == null || mediaContents.isEmpty()) {
            return;
        }
        for (var mc : mediaContents) {
            try {
                String fileName = mc.fileName() != null ? mc.fileName()
                        : "media-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                        + "." + guessExtension(mc.mimeType());
                String dataUri = "data:" + mc.mimeType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(mc.data());
                attachmentRepository.save(assistantMessageId, sessionId,
                        fileName, "", mc.sizeBytes(), mc.mimeType(), dataUri);
            } catch (Exception e) {
                log.warn("用户媒体附件持久化失败: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }

    public void asyncPostProcess(ReactAgentState finalState) {
        Thread.startVirtualThread(() -> {
            try {
                sessionManager.saveSession(finalState);
            } catch (Exception e) {
                log.warn("会话快照持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            try {
                if (realtimeExtractor != null && finalState.finalOutput() != null) {
                    realtimeExtractor.extractAsync(
                            finalState.sessionId(),
                            finalState.goal(),
                            finalState.finalOutput());
                }
            } catch (Exception e) {
                log.warn("实时实体提取失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            TemporalEntity newExperience = null;
            try {
                if (experienceSummarizer != null) {
                    newExperience = experienceSummarizer.summarize(finalState);
                }
            } catch (Exception e) {
                log.warn("经验提炼失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (effectivenessTracker != null) {
                    effectivenessTracker.evaluate(finalState, finalState.traceId());
                }
            } catch (Exception e) {
                log.warn("效果评估失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (contrastiveLearner != null && newExperience != null) {
                    contrastiveLearner.learn(newExperience);
                }
            } catch (Exception e) {
                log.warn("对比学习失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (subtaskReflector != null) {
                    subtaskReflector.reflect(finalState);
                }
            } catch (Exception e) {
                log.warn("子任务反思失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
    }

    public void persistStreamingSystemError(@Nullable String sessionId,
                                            @Nullable String traceId,
                                            @Nullable Exception e) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            String detail = e != null ? e.getMessage() : "unknown";
            String content = "系统提示：模型服务暂时不可用，请稍后重试。\n（错误信息）" + detail;
            if (conversationHistoryStore != null) {
                conversationHistoryStore.appendSystemMessage(sessionId, content, traceId);
            }
        } catch (Exception ignore) {
            // 不影响主流程
        }
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
        var payload = new LinkedHashMap<String, Object>();
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
                    "等待工作流 " + workflowWait.workflowName() + " 完成";
            case SuspendReason.UserConfirmation confirmation ->
                    "等待用户确认高风险工具 " + confirmation.toolId();
            case SuspendReason.RemoteDelegation remoteDelegation ->
                    "等待远端代理返回任务结果: " + remoteDelegation.delegatedGoal();
            case SuspendReason.ScheduledWakeup scheduledWakeup ->
                    "等待定时唤醒: " + scheduledWakeup.reason();
            case SuspendReason.ExternalDataWait externalDataWait ->
                    "等待外部数据就绪: " + externalDataWait.description();
        };
    }
}
