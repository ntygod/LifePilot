package com.lifepilot.conversation.transcript;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC Transcript 写入实现。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Service
public class JdbcTranscriptStore implements TranscriptStore {

    private final SessionStoreRepository sessionStoreRepository;
    private final SessionTranscriptRepository sessionTranscriptRepository;
    @Nullable
    private final ChatSessionRepository chatSessionRepository;

    public JdbcTranscriptStore(SessionStoreRepository sessionStoreRepository,
                               SessionTranscriptRepository sessionTranscriptRepository) {
        this(sessionStoreRepository, sessionTranscriptRepository, null);
    }

    @Autowired
    public JdbcTranscriptStore(SessionStoreRepository sessionStoreRepository,
                               SessionTranscriptRepository sessionTranscriptRepository,
                               @Nullable ChatSessionRepository chatSessionRepository) {
        this.sessionStoreRepository = sessionStoreRepository;
        this.sessionTranscriptRepository = sessionTranscriptRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Override
    public String appendUserMessage(String sessionId,
                                    @Nullable String turnId,
                                    String content,
                                    @Nullable String traceId,
                                    @Nullable Instant createdAt) {
        return appendUserMessageInternal(sessionId, turnId, content, traceId, true, createdAt);
    }

    @Override
    public String appendUserMessage(String sessionId,
                                    @Nullable String turnId,
                                    String content,
                                    @Nullable String traceId,
                                    boolean visibleToUser,
                                    @Nullable Instant createdAt) {
        return appendUserMessageInternal(sessionId, turnId, content, traceId, visibleToUser, createdAt);
    }

    /**
     * 用户消息写入核心逻辑，visibleToUser 控制消息是否在聊天界面展示。
     *
     * <p>当 visibleToUser=false 时通过 {@code appendEntry} 直接写入条目，
     * 避免在消息预览摘要中留下信号交互的痕迹。
     */
    private String appendUserMessageInternal(String sessionId,
                                             @Nullable String turnId,
                                             String content,
                                             @Nullable String traceId,
                                             boolean visibleToUser,
                                             @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (visibleToUser) {
            String entryId = sessionTranscriptRepository.appendMessageEntry(
                    sessionId,
                    "user",
                    content,
                    null,
                    turnId,
                    traceId,
                    null,
                    null,
                    null,
                    null,
                    timestamp
            );
            appendMessageMeta(sessionId, timestamp, content);
            return entryId;
        }
        // 信号等不可见消息：保留在模型上下文中，但不展示给用户
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        String entryId = sessionTranscriptRepository.appendEntry(
                sessionId,
                TranscriptEntryType.USER_MESSAGE,
                "user",
                normalizeBlank(turnId),
                normalizeBlank(traceId),
                true,
                false,
                payload,
                timestamp
        );
        sessionStoreRepository.touchActivity(sessionId, timestamp);
        return entryId;
    }

    @Override
    public String appendAssistantMessage(String sessionId,
                                         @Nullable String turnId,
                                         String content,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson,
                                         @Nullable String reactStepsJson,
                                         @Nullable CompletionMode completionMode,
                                         @Nullable String resumedFromTraceId,
                                         @Nullable Instant createdAt) {
        return appendAssistantMessage(sessionId, turnId, content, reasoningSummary, traceId,
                a2uiComponentsJson, reactStepsJson, null, null, completionMode, resumedFromTraceId, createdAt);
    }

    @Override
    public String appendAssistantMessage(String sessionId,
                                         @Nullable String turnId,
                                         String content,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson,
                                         @Nullable String reactStepsJson,
                                         @Nullable String taskRecoveryJson,
                                         @Nullable CompletionMode completionMode,
                                         @Nullable String resumedFromTraceId,
                                         @Nullable Instant createdAt) {
        return appendAssistantMessage(sessionId, turnId, content, reasoningSummary, traceId,
                a2uiComponentsJson, reactStepsJson, null, taskRecoveryJson,
                completionMode, resumedFromTraceId, createdAt);
    }

    @Override
    public String appendAssistantMessage(String sessionId,
                                         @Nullable String turnId,
                                         String content,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson,
                                         @Nullable String reactStepsJson,
                                         @Nullable String toolsSummaryJson,
                                         @Nullable String taskRecoveryJson,
                                         @Nullable CompletionMode completionMode,
                                         @Nullable String resumedFromTraceId,
                                         @Nullable Instant createdAt) {
        return appendAssistantMessage(sessionId, turnId, content, reasoningSummary, traceId,
                a2uiComponentsJson, reactStepsJson, toolsSummaryJson, taskRecoveryJson, null,
                completionMode, resumedFromTraceId, createdAt);
    }

    @Override
    public String appendAssistantMessage(String sessionId,
                                         @Nullable String turnId,
                                         String content,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson,
                                         @Nullable String reactStepsJson,
                                         @Nullable String toolsSummaryJson,
                                         @Nullable String taskRecoveryJson,
                                         @Nullable String executionConstraintsJson,
                                         @Nullable CompletionMode completionMode,
                                         @Nullable String resumedFromTraceId,
                                         @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        String entryId = sessionTranscriptRepository.appendMessageEntry(
                sessionId,
                "assistant",
                content,
                reasoningSummary,
                turnId,
                traceId,
                a2uiComponentsJson,
                reactStepsJson,
                toolsSummaryJson,
                taskRecoveryJson,
                executionConstraintsJson,
                completionMode,
                resumedFromTraceId,
                timestamp
        );
        appendMessageMeta(sessionId, timestamp, content);
        return entryId;
    }

    @Override
    public String appendSystemMessage(String sessionId,
                                      String content,
                                      @Nullable String traceId,
                                      @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        String entryId = sessionTranscriptRepository.appendMessageEntry(
                sessionId,
                "system",
                content,
                null,
                null,
                traceId,
                null,
                null,
                null,
                null,
                timestamp
        );
        appendMessageMeta(sessionId, timestamp, content);
        return entryId;
    }

    @Override
    public String appendCustomMessage(String sessionId,
                                      @Nullable String turnId,
                                      String role,
                                      String content,
                                      @Nullable String traceId,
                                      boolean visibleToModel,
                                      boolean visibleToUser,
                                      @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        String entryId = sessionTranscriptRepository.appendEntry(
                sessionId,
                TranscriptEntryType.fromRole(role),
                role,
                turnId,
                normalizeBlank(traceId),
                visibleToModel,
                visibleToUser,
                payload,
                timestamp
        );
        if (visibleToUser) {
            appendMessageMeta(sessionId, timestamp, content);
        } else {
            sessionStoreRepository.touchActivity(sessionId, timestamp);
        }
        return entryId;
    }

    @Override
    public String appendToolCall(String sessionId,
                                 @Nullable String turnId,
                                 @Nullable String traceId,
                                 String toolId,
                                 @Nullable String callId,
                                 @Nullable String toolName,
                                 String inputJson,
                                 @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        sessionStoreRepository.touchActivity(sessionId, timestamp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", toolId);
        payload.put("inputJson", inputJson);
        if (callId != null && !callId.isBlank()) {
            payload.put("callId", callId);
        }
        if (toolName != null && !toolName.isBlank()) {
            payload.put("toolName", toolName);
        }
        return sessionTranscriptRepository.appendEntry(
                sessionId,
                TranscriptEntryType.TOOL_CALL,
                "tool",
                turnId,
                traceId,
                true,
                false,
                payload,
                timestamp
        );
    }

    @Override
    public String appendToolResult(String sessionId,
                                   @Nullable String turnId,
                                   @Nullable String traceId,
                                   String toolId,
                                   @Nullable String callId,
                                   boolean success,
                                   String outputJson,
                                   @Nullable String artifactId,
                                   boolean visibleToModel,
                                   boolean visibleToUser,
                                   @Nullable Instant createdAt) {
        ensureSessionExists(sessionId);
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        sessionStoreRepository.touchActivity(sessionId, timestamp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", toolId);
        payload.put("success", success);
        payload.put("outputJson", outputJson);
        if (callId != null && !callId.isBlank()) {
            payload.put("callId", callId);
        }
        if (artifactId != null && !artifactId.isBlank()) {
            payload.put("artifactId", artifactId);
        }
        return sessionTranscriptRepository.appendEntry(
                sessionId,
                TranscriptEntryType.TOOL_RESULT,
                "tool",
                turnId,
                traceId,
                visibleToModel,
                visibleToUser,
                payload,
                timestamp
        );
    }

    private void ensureSessionExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionStoreRepository.ensureSessionShell(sessionId);
        if (chatSessionRepository == null) {
            return;
        }
        if (chatSessionRepository.findById(sessionId).isPresent()) {
            return;
        }
        chatSessionRepository.save(ChatSession.createWithId(sessionId, deriveSessionTitle(sessionId)));
    }

    @Nullable
    private String truncatePreview(@Nullable String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.strip();
        if (normalized.length() <= 100) {
            return normalized;
        }
        return normalized.substring(0, 100) + "...";
    }

    private void appendMessageMeta(String sessionId, Instant timestamp, @Nullable String content) {
        String preview = truncatePreview(content);
        if (chatSessionRepository != null) {
            chatSessionRepository.appendMessageMeta(sessionId, timestamp, preview);
            return;
        }
        sessionStoreRepository.appendMessageMeta(sessionId, timestamp, preview);
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String deriveSessionTitle(String sessionId) {
        if (sessionId.startsWith("feishu:")) return "飞书对话";
        if (sessionId.startsWith("wecom:")) return "企微对话";
        if (sessionId.startsWith("dingtalk:")) return "钉钉对话";
        return "外部渠道对话";
    }
}
