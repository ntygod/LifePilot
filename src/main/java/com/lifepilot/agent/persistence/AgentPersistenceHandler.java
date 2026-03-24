package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.CompactionEngine;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.conversation.transcript.TranscriptStore;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent \u6301\u4e45\u5316\u5904\u7406\u5668\u3002
 * <p>\u8d1f\u8d23\u5c06\u4e3b\u5bf9\u8bdd\u94fe\u8def\u4e2d\u7684 transcript\u3001\u9644\u4ef6\u3001\u6ce8\u5165\u8bb0\u5f55\u3001\u5de5\u4f5c\u533a\u72b6\u6001\u548c\u5f02\u6b65\u8bb0\u5fc6\u540e\u5904\u7406\u7edf\u4e00\u843d\u76d8\u3002</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class AgentPersistenceHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentPersistenceHandler.class);

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
            @Nullable CompactionEngine compactionEngine) {
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
    }

    public void saveWorkspaceForSuspend(ReactAgentState state) {
        if (workspaceService == null || state.suspendReason() == null) {
            return;
        }
        try {
            switch (state.suspendReason()) {
                case SuspendReason.UserConfirmation confirmation ->
                        workspaceService.savePendingDecision(state.sessionId(), new PendingDecisionItem(
                                "\u7b49\u5f85\u786e\u8ba4",
                                "\u5de5\u5177\u8c03\u7528\u7b49\u5f85\u7528\u6237\u786e\u8ba4: " + confirmation.toolId(),
                                buildSuspendPayload(state.suspendReason()),
                                100,
                                state.traceId(),
                                state.traceId(),
                                null));
                default ->
                        workspaceService.saveTaskState(state.sessionId(), new TaskStateItem(
                                "\u4efb\u52a1\u5df2\u6682\u505c",
                                formatSuspendSummary(state.suspendReason()),
                                buildSuspendPayload(state.suspendReason()),
                                60,
                                state.traceId(),
                                state.traceId(),
                                null));
            }
        } catch (Exception e) {
            log.warn("\u4fdd\u5b58\u6682\u505c\u5de5\u4f5c\u533a\u5931\u8d25: sessionId={}, error={}", state.sessionId(), e.getMessage());
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
            log.warn("\u89e3\u6790\u5de5\u4f5c\u533a\u8f68\u8ff9\u5f15\u7528\u5931\u8d25: sessionId={}, traceId={}, error={}",
                    sessionId, traceId, e.getMessage());
        }
    }

    public void persistUserMessage(ReactAgentState state) {
        if (state.goal() == null || state.goal().isBlank()) {
            return;
        }
        try {
            transcriptStore.appendUserMessage(state.sessionId(), state.goal(), state.traceId(), null);
        } catch (Exception e) {
            log.warn("\u5199\u5165\u7528\u6237\u6d88\u606f\u5931\u8d25: sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    @Nullable
    public String persistUserMessageReturningId(ReactAgentState state) {
        if (state.goal() == null || state.goal().isBlank()) {
            return null;
        }
        try {
            return transcriptStore.appendUserMessage(state.sessionId(), state.goal(), state.traceId(), null);
        } catch (Exception e) {
            log.warn("\u5199\u5165\u7528\u6237\u6d88\u606f\u5931\u8d25: sessionId={}, error={}", state.sessionId(), e.getMessage());
            return null;
        }
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
            log.warn("\u5199\u5165\u52a9\u624b\u6d88\u606f\u5931\u8d25: sessionId={}, error={}", state.sessionId(), e.getMessage());
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
            log.warn("\u5199\u5165\u52a9\u624b\u6d88\u606f\u5931\u8d25: sessionId={}, error={}", state.sessionId(), e.getMessage());
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
            log.debug("\u6ce8\u5165\u8bb0\u5f55\u5df2\u6301\u4e45\u5316: sourceEntryId={}, entityCount={}", sourceEntryId, entityIds.size());
        } catch (Exception e) {
            log.warn("\u6301\u4e45\u5316\u6ce8\u5165\u8bb0\u5f55\u5931\u8d25: sourceEntryId={}, error={}", sourceEntryId, e.getMessage());
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
                log.warn("\u4fdd\u5b58\u5de5\u5177\u5a92\u4f53\u9644\u4ef6\u5931\u8d25: field={}, error={}",
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
                log.warn("\u4fdd\u5b58\u7528\u6237\u5a92\u4f53\u9644\u4ef6\u5931\u8d25: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }

    public void asyncPostProcess(ReactAgentState finalState) {
        Thread.startVirtualThread(() -> {
            try {
                if (compactionEngine != null) {
                    compactionEngine.compactIfNeeded(finalState.sessionId(), finalState.traceId());
                }
            } catch (Exception e) {
                log.warn("\u4f1a\u8bdd\u538b\u7f29\u540e\u5904\u7406\u5931\u8d25: sessionId={}, error={}",
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
                log.warn("\u5b9e\u65f6\u8bb0\u5fc6\u62bd\u53d6\u5931\u8d25: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            TemporalEntity newExperience = null;
            try {
                if (experienceSummarizer != null) {
                    newExperience = experienceSummarizer.summarize(finalState);
                }
            } catch (Exception e) {
                log.warn("\u7ecf\u9a8c\u603b\u7ed3\u5931\u8d25: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (effectivenessTracker != null) {
                    effectivenessTracker.evaluate(finalState, finalState.traceId());
                }
            } catch (Exception e) {
                log.warn("\u6548\u679c\u8bc4\u4f30\u5931\u8d25: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (contrastiveLearner != null && newExperience != null) {
                    contrastiveLearner.learn(newExperience);
                }
            } catch (Exception e) {
                log.warn("\u5bf9\u6bd4\u5b66\u4e60\u5931\u8d25: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }

            try {
                if (subtaskReflector != null) {
                    subtaskReflector.reflect(finalState);
                }
            } catch (Exception e) {
                log.warn("\u5b50\u4efb\u52a1\u53cd\u601d\u5931\u8d25: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
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
                    "\u5de5\u4f5c\u6d41\u7b49\u5f85: " + workflowWait.workflowName();
            case SuspendReason.UserConfirmation confirmation ->
                    "\u7528\u6237\u786e\u8ba4\u7b49\u5f85: " + confirmation.toolId();
            case SuspendReason.RemoteDelegation remoteDelegation ->
                    "\u8fdc\u7a0b\u59d4\u6d3e\u7b49\u5f85: " + remoteDelegation.delegatedGoal();
            case SuspendReason.ScheduledWakeup scheduledWakeup ->
                    "\u5b9a\u65f6\u5524\u9192\u7b49\u5f85: " + scheduledWakeup.reason();
            case SuspendReason.ExternalDataWait externalDataWait ->
                    "\u5916\u90e8\u6570\u636e\u7b49\u5f85: " + externalDataWait.description();
        };
    }
}
