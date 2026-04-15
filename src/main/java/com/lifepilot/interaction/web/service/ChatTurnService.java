package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final ChatTurnRepository chatTurnRepository;
    private final SessionTranscriptRepository transcriptRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    @Nullable
    private final NotificationProperties notificationProperties;
    @Nullable
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable
    private final SessionDatastoreRepository sessionDatastoreRepository;
    @Nullable
    private final ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;

    @Autowired
    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                           @Nullable SessionDatastoreRepository sessionDatastoreRepository,
                           @Nullable ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository,
                           @Nullable MemorySpaceRepository memorySpaceRepository) {
        this.chatTurnRepository = chatTurnRepository;
        this.transcriptRepository = transcriptRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.notificationProperties = notificationProperties;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.sessionDatastoreRepository = sessionDatastoreRepository;
        this.chatTurnMemorySnapshotRepository = chatTurnMemorySnapshotRepository;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    /** 便捷构造器（测试用）— 由 Spring 管理时使用主构造器。 */
    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher) {
        this(chatTurnRepository, transcriptRepository, objectMapper, eventPublisher,
                null, null, null, null, null);
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
                eventPublisher.publishEvent(
                        new ConversationCompletedEvent(this, userId, sessionId, summary));
            } catch (Exception e) {
                log.debug("对话完成事件发布跳过: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
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
                request.preferredProvider()
        ));
        chatTurnRepository.create(turnId, sessionId, ChatTurnAction.SEND, ChatTurnStatus.PENDING, payloadJson, now);
        persistTurnMemorySnapshot(sessionId, turnId, now);
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

    public Optional<ChatTurnMemorySnapshot> findMemorySnapshot(String turnId) {
        if (chatTurnMemorySnapshotRepository == null) {
            return Optional.empty();
        }
        return chatTurnMemorySnapshotRepository.findByTurnId(turnId);
    }

    private void persistTurnMemorySnapshot(String sessionId, String turnId, Instant now) {
        if (chatTurnMemorySnapshotRepository == null || memorySpaceRepository == null) {
            return;
        }
        var personalSpace = memorySpaceRepository.ensureDefaultPersonalSpace();
        var experienceSpace = memorySpaceRepository.ensureDefaultExperienceSpace();
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository != null
                ? sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId)
                : List.of();
        List<String> datastoreIds = sessionDatastoreRepository != null
                ? sessionDatastoreRepository.findDatastoreIdsBySessionId(sessionId)
                : List.of();
        boolean knowledgeBound = !knowledgeBaseIds.isEmpty() || !datastoreIds.isEmpty();
        List<String> domainReadSpaceIds = resolveDomainReadSpaceIds(knowledgeBaseIds, datastoreIds);
        String domainWriteSpaceId = resolveDomainWriteSpaceId(knowledgeBaseIds, datastoreIds);
        List<String> readSpaceIds = new ArrayList<>();
        readSpaceIds.add(personalSpace.id());
        readSpaceIds.add(experienceSpace.id());
        readSpaceIds.addAll(domainReadSpaceIds);
        Map<String, Object> resolutionSource = new java.util.LinkedHashMap<>();
        resolutionSource.put("source", "session_config");
        resolutionSource.put("knowledgeBound", knowledgeBound);
        resolutionSource.put("resolvedDomainReadSpaces", domainReadSpaceIds);
        if (domainWriteSpaceId != null && !domainWriteSpaceId.isBlank()) {
            resolutionSource.put("resolvedDomainWriteSpaceId", domainWriteSpaceId);
        }
        ChatTurnMemorySnapshot snapshot = new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                personalSpace.id(),
                experienceSpace.id(),
                domainWriteSpaceId,
                readSpaceIds,
                knowledgeBaseIds,
                datastoreIds,
                !knowledgeBound,
                false,
                true,
                resolutionSource,
                now
        );
        chatTurnMemorySnapshotRepository.save(snapshot);
    }

    private List<String> resolveDomainReadSpaceIds(List<String> knowledgeBaseIds, List<String> datastoreIds) {
        if (memorySpaceRepository == null) {
            return List.of();
        }
        List<String> readSpaceIds = new ArrayList<>();
        if (datastoreIds != null) {
            for (String datastoreId : datastoreIds) {
                MemorySpace space = memorySpaceRepository.ensureDatastoreDomainSpace(datastoreId);
                readSpaceIds.add(space.id());
            }
        }
        if ((datastoreIds == null || datastoreIds.isEmpty()) && knowledgeBaseIds != null) {
            for (String knowledgeBaseId : knowledgeBaseIds) {
                MemorySpace space = memorySpaceRepository.ensureKnowledgeBaseDomainSpace(knowledgeBaseId);
                readSpaceIds.add(space.id());
            }
        }
        return readSpaceIds.stream().distinct().toList();
    }

    @Nullable
    private String resolveDomainWriteSpaceId(List<String> knowledgeBaseIds, List<String> datastoreIds) {
        if (memorySpaceRepository == null) {
            return null;
        }
        if (datastoreIds != null && datastoreIds.size() == 1) {
            return memorySpaceRepository.ensureDatastoreDomainSpace(datastoreIds.getFirst()).id();
        }
        if ((datastoreIds == null || datastoreIds.isEmpty())
                && knowledgeBaseIds != null
                && knowledgeBaseIds.size() == 1) {
            return memorySpaceRepository.ensureKnowledgeBaseDomainSpace(knowledgeBaseIds.getFirst()).id();
        }
        return null;
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
