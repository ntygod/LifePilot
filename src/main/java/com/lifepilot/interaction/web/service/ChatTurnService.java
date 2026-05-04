package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.project.context.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;
    @Nullable
    private final ChatSessionRepository chatSessionRepository;
    @Nullable
    private final ProjectContextResolver projectContextResolver;

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
                           @Nullable ProjectContextResolver projectContextResolver) {
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
    }

    /** 便捷构造器（测试用）— 由 Spring 管理时使用主构造器。 */
    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher) {
        this(chatTurnRepository, transcriptRepository, objectMapper, eventPublisher,
                null, null, null, null, null, null);
    }

    /** 旧版测试构造器 — 不提供 ProjectContext 依赖时使用，保持二进制兼容。 */
    public ChatTurnService(ChatTurnRepository chatTurnRepository,
                           SessionTranscriptRepository transcriptRepository,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                           @Nullable ChatTurnMemorySnapshotRepository chatTurnMemorySnapshotRepository,
                           @Nullable MemorySpaceRepository memorySpaceRepository) {
        this(chatTurnRepository, transcriptRepository, objectMapper, eventPublisher,
                null, null, chatTurnMemorySnapshotRepository, memorySpaceRepository, null, null);
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
        String projectSpaceId = resolveProjectSpaceId(sessionId);
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository != null
                ? sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId)
                : List.of();
        boolean knowledgeBound = !knowledgeBaseIds.isEmpty();
        List<String> domainReadSpaceIds = resolveDomainReadSpaceIds(knowledgeBaseIds);
        String domainWriteSpaceId = resolveDomainWriteSpaceId(knowledgeBaseIds);
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
        if (projectSpaceId != null) {
            resolutionSource.put("resolvedProjectSpaceId", projectSpaceId);
        }
        ChatTurnMemorySnapshot snapshot = new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                personalSpace.id(),
                experienceSpace.id(),
                domainWriteSpaceId,
                projectSpaceId,
                readSpaceIds,
                knowledgeBaseIds,
                !knowledgeBound,
                false,
                true,
                resolutionSource,
                now
        );
        chatTurnMemorySnapshotRepository.save(snapshot);
    }

    /**
     * 解析当前会话对应的项目 MemorySpace id。
     *
     * <p>语义：仅 ISOLATED 项目对话返回非空；SHARED 项目 / 主账户对话 / 解析异常均返回 null，
     * 下游写入路径据此走"主账户默认空间"fallback，避免污染项目域或因 resolver 缺失阻断对话。</p>
     */
    @Nullable
    private String resolveProjectSpaceId(String sessionId) {
        if (chatSessionRepository == null || projectContextResolver == null) {
            return null;
        }
        try {
            Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
            if (session.isEmpty()) {
                return null;
            }
            String projectId = session.get().projectId();
            if (projectId == null) {
                return null;
            }
            var ctx = projectContextResolver.resolve(projectId);
            return ctx.isolated() ? ctx.projectSpaceId() : null;
        } catch (Exception e) {
            log.debug("解析项目空间失败，走主账户 fallback: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
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
     * <p>仅在对应字段非空 / 非默认值时写入；旧 payload 反序列化时缺这些字段
     * 走 {@code Map.get} 默认 null，向后兼容自然成立。
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
