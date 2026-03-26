package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话轮次服务。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Service
public class ChatTurnService {

    private final ChatTurnRepository chatTurnRepository;
    private final SessionTranscriptRepository transcriptRepository;
    private final ObjectMapper objectMapper;

    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper) {
        this.chatTurnRepository = chatTurnRepository;
        this.transcriptRepository = transcriptRepository;
        this.objectMapper = objectMapper;
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
                request.preferredProvider()
        ));
        chatTurnRepository.create(turnId, sessionId, ChatTurnAction.SEND, ChatTurnStatus.PENDING, payloadJson, now);
        return new ResolvedTurnRequest(
                turnId,
                ChatTurnAction.SEND,
                request.content(),
                request.attachmentIds(),
                request.preferredProvider()
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
        chatTurnRepository.markAttemptStarted(sessionId, turnId, action, now);
        TurnRequestSnapshot snapshot = deserializeSnapshot(turn.requestPayloadJson());
        String resolvedContent = snapshot.content();
        if (action == ChatTurnAction.RESUME && request.hasContent()) {
            resolvedContent = buildResumeContent(snapshot.content(), request.content());
        }
        return new ResolvedTurnRequest(
                turnId,
                action,
                resolvedContent,
                mergeAttachmentIds(snapshot.attachmentIds(), request.attachmentIds()),
                snapshot.preferredProvider()
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

    private record TurnRequestSnapshot(
            String content,
            @Nullable List<String> attachmentIds,
            @Nullable String preferredProvider
    ) {}

    public record ResolvedTurnRequest(
            String turnId,
            ChatTurnAction action,
            String content,
            @Nullable List<String> attachmentIds,
            @Nullable String preferredProvider
    ) {}
}
