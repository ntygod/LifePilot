package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.model.AttachmentInfo;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.model.SessionConfigRequest;
import com.lifepilot.interaction.web.model.SessionDetailInfo;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository sessionRepository;
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final AttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;
    private final SessionTranscriptRepository transcriptRepository;
    @Nullable
    private final ChatTurnService chatTurnService;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                              AttachmentRepository attachmentRepository,
                              ObjectMapper objectMapper,
                              SessionTranscriptRepository transcriptRepository,
                              @Nullable ChatTurnService chatTurnService) {
        this.sessionRepository = sessionRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.attachmentRepository = attachmentRepository;
        this.objectMapper = objectMapper;
        this.transcriptRepository = transcriptRepository;
        this.chatTurnService = chatTurnService;
    }

    @Transactional
    public ChatSession createSession(String title) {
        ChatSession session = ChatSession.create(title);
        sessionRepository.save(session);
        log.info("\u521b\u5efa\u4f1a\u8bdd: id={}, title={}", session.id(), session.title());
        return session;
    }

    public List<SessionInfo> listSessions() {
        return sessionRepository.findAll().stream()
                .map(this::toSessionInfo)
                .toList();
    }

    public List<SessionInfo> listSessions(String q, Boolean pinned, Boolean archived,
                                          String timeRange, String sortBy, String order) {
        return sessionRepository.findByConditions(q, pinned, archived, timeRange, sortBy, order)
                .stream()
                .map(this::toSessionInfo)
                .toList();
    }

    public Optional<ChatSession> getSession(String id) {
        return isWebSessionId(id) ? sessionRepository.findById(id) : Optional.empty();
    }

    @Transactional
    public ChatSession updateTitle(String id, String title) {
        ChatSession session = requireWebSession(id);
        sessionRepository.updateTitle(id, title);
        log.info("\u66f4\u65b0\u4f1a\u8bdd\u6807\u9898: id={}, title={}", id, title);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public ChatSession updatePinned(String id, boolean isPinned) {
        ChatSession session = requireWebSession(id);
        sessionRepository.updatePinned(id, isPinned);
        log.info("\u66f4\u65b0\u4f1a\u8bdd\u7f6e\u9876\u72b6\u6001: id={}, pinned={}", id, isPinned);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public SessionInfo updateSession(String id, String title, Boolean pinned, Boolean archived) {
        ChatSession session = requireWebSession(id);

        if (title == null && pinned == null && archived == null) {
            log.debug("\u8df3\u8fc7\u7a7a\u4f1a\u8bdd\u66f4\u65b0: id={}", id);
            return toSessionInfo(session);
        }

        sessionRepository.updateFields(id, title, pinned, archived);
        log.info("\u66f4\u65b0\u4f1a\u8bdd: id={}, title={}, pinned={}, archived={}", id, title, pinned, archived);

        ChatSession updatedSession = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("\u4f1a\u8bdd\u66f4\u65b0\u540e\u6d88\u5931: id=" + id));
        return toSessionInfo(updatedSession);
    }

    @Transactional
    public void deleteSession(String id) {
        requireWebSession(id);
        sessionRepository.deleteById(id);
        log.info("\u5220\u9664\u4f1a\u8bdd: id={}", id);
    }

    @Transactional
    public void clearSessionMessages(String id) {
        requireWebSession(id);
        transcriptRepository.deleteBySessionId(id);
        attachmentRepository.deleteBySessionId(id);
        sessionRepository.clearMessages(id);
        log.info("\u6e05\u7a7a\u4f1a\u8bdd\u6d88\u606f: id={}", id);
    }

    @Transactional
    public void incrementMessageCount(String id) {
        sessionRepository.incrementMessageCount(id);
    }

    public List<MessageInfo> getSessionMessages(String id) {
        requireWebSession(id);
        return withAttachments(loadTranscriptMessages(id));
    }

    @Transactional
    public int batchUpdateSessions(String action, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.warn("\u8df3\u8fc7\u6279\u91cf\u4f1a\u8bdd\u66f4\u65b0: ID \u5217\u8868\u4e3a\u7a7a");
            return 0;
        }

        return switch (action) {
            case "pin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, true, null);
                log.info("\u6279\u91cf\u7f6e\u9876\u4f1a\u8bdd: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unpin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, false, null);
                log.info("\u6279\u91cf\u53d6\u6d88\u7f6e\u9876\u4f1a\u8bdd: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "archive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, true);
                log.info("\u6279\u91cf\u5f52\u6863\u4f1a\u8bdd: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unarchive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, false);
                log.info("\u6279\u91cf\u53d6\u6d88\u5f52\u6863\u4f1a\u8bdd: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "delete" -> {
                int count = sessionRepository.batchDelete(sessionIds);
                log.info("\u6279\u91cf\u5220\u9664\u4f1a\u8bdd: count={}, ids={}", count, sessionIds);
                yield count;
            }
            default -> throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u6279\u91cf\u64cd\u4f5c: " + action);
        };
    }

    public SessionDetailInfo getSessionDetail(String id) {
        ChatSession session = requireWebSession(id);
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(id);
        Map<String, Object> sessionConfig = sessionRepository.getConfig(id);

        return new SessionDetailInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                SessionConfigKeys.resolvePreferredProviderId(sessionConfig),
                SessionConfigKeys.getDouble(sessionConfig, SessionConfigKeys.TEMPERATURE),
                SessionConfigKeys.getInteger(sessionConfig, SessionConfigKeys.MAX_TOKENS),
                SessionConfigKeys.getInteger(sessionConfig, SessionConfigKeys.MAX_STEPS),
                SessionConfigKeys.getInteger(sessionConfig, SessionConfigKeys.MAX_DURATION_SECONDS),
                knowledgeBaseIds,
                session.messageCount(),
                0L,
                session.summary()
        );
    }

    @Transactional
    public SessionInfo forkSession(String originalSessionId, String fromEntryId, String newTitle) {
        ChatSession originalSession = requireSession(originalSessionId);
        List<SessionTranscriptRepository.TranscriptMessageViewRow> transcriptMessages =
                transcriptRepository.findUserConversationRowsBySessionId(originalSessionId);
        return forkFromTranscript(originalSession, transcriptMessages, fromEntryId, newTitle);
    }

    @Transactional
    public void updateSessionConfig(String id, SessionConfigRequest request) {
        requireWebSession(id);

        Map<String, Object> config = new HashMap<>();
        config.put(SessionConfigKeys.PREFERRED_PROVIDER, SessionConfigKeys.normalizeString(request.preferredProviderId()));
        config.put(SessionConfigKeys.LEGACY_MODEL_ID, null);
        config.put(SessionConfigKeys.TEMPERATURE, request.temperature());
        config.put(SessionConfigKeys.MAX_TOKENS, request.maxTokens());
        config.put(SessionConfigKeys.MAX_STEPS, request.maxSteps());
        config.put(SessionConfigKeys.MAX_DURATION_SECONDS, request.maxDurationSeconds());

        sessionRepository.updateConfig(id, config);
        log.info("\u66f4\u65b0\u4f1a\u8bdd\u914d\u7f6e: sessionId={}, config={}", id, config);

        if (request.knowledgeBaseIds() != null) {
            sessionKnowledgeBaseRepository.setAssociations(id, request.knowledgeBaseIds());
            log.info("\u66f4\u65b0\u4f1a\u8bdd\u77e5\u8bc6\u5e93\u5173\u8054: sessionId={}, knowledgeBaseIds={}",
                    id, request.knowledgeBaseIds());
        }
    }

    private List<MessageInfo> loadTranscriptMessages(String sessionId) {
        Map<String, ChatTurnRecord> turnsByTurnId = new HashMap<>();
        if (chatTurnService != null) {
            for (ChatTurnRecord turn : chatTurnService.findBySessionId(sessionId)) {
                turnsByTurnId.put(turn.turnId(), turn);
            }
        }
        return transcriptRepository.findUserConversationRowsBySessionId(sessionId).stream()
                .map(row -> toMessageInfo(row, turnsByTurnId))
                .toList();
    }

    private MessageInfo toMessageInfo(SessionTranscriptRepository.TranscriptMessageViewRow row,
                                      Map<String, ChatTurnRecord> turnsByTurnId) {
        var tree = A2uiPayloadSupport.deserializeStoredTree(row.a2uiComponentsJson(), objectMapper);
        ChatTurnRecord turn = row.turnId() != null ? turnsByTurnId.get(row.turnId()) : null;
        var rowCompletionMode = parseCompletionMode(row.completionMode());
        var effectiveCompletionMode = parseCompletionMode(
                turn != null && turn.completionMode() != null ? turn.completionMode() : row.completionMode());
        ChatTurnStatus effectiveTurnStatus = resolveMessageTurnStatus(turn, rowCompletionMode);
        return new MessageInfo(
                row.entryId(),
                row.turnId(),
                row.role(),
                row.content(),
                tree != null ? tree.components() : null,
                row.createdAt(),
                row.reasoningSummary(),
                row.traceId(),
                null,
                deserializeReactSteps(row.reactStepsJson()),
                effectiveCompletionMode,
                turn != null && turn.resumedFromTraceId() != null ? turn.resumedFromTraceId() : row.resumedFromTraceId(),
                effectiveTurnStatus,
                resolveMessageErrorMessage(turn, rowCompletionMode)
        );
    }

    @Nullable
    private ChatTurnStatus resolveMessageTurnStatus(@Nullable ChatTurnRecord turn,
                                                    @Nullable com.lifepilot.agent.model.CompletionMode rowCompletionMode) {
        if (rowCompletionMode == com.lifepilot.agent.model.CompletionMode.SUSPENDED) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (rowCompletionMode == com.lifepilot.agent.model.CompletionMode.DEGRADED) {
            return ChatTurnStatus.DEGRADED;
        }
        return turn != null ? turn.status() : null;
    }

    @Nullable
    private String resolveMessageErrorMessage(@Nullable ChatTurnRecord turn,
                                              @Nullable com.lifepilot.agent.model.CompletionMode rowCompletionMode) {
        if (rowCompletionMode == com.lifepilot.agent.model.CompletionMode.SUSPENDED) {
            return null;
        }
        return turn != null ? turn.lastErrorMessage() : null;
    }

    private List<MessageInfo> withAttachments(List<MessageInfo> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        var entryIds = messages.stream().map(MessageInfo::id).toList();
        var attachmentMap = attachmentRepository.findByEntryIds(entryIds);
        if (attachmentMap.isEmpty()) {
            return messages;
        }
        return messages.stream().map(msg -> {
            var records = attachmentMap.get(msg.id());
            if (records == null || records.isEmpty()) {
                return msg;
            }
            var attachments = records.stream()
                    .map(r -> new AttachmentInfo(r.id(), r.fileName(), r.fileSize(), r.mimeType(), r.url()))
                    .toList();
            return new MessageInfo(
                    msg.id(),
                    msg.turnId(),
                    msg.role(),
                    msg.content(),
                    msg.a2uiComponents(),
                    msg.timestamp(),
                    msg.reasoningSummary(),
                    msg.traceId(),
                    attachments,
                    msg.reactSteps(),
                    msg.completionMode(),
                    msg.resumedFromTraceId(),
                    msg.turnStatus(),
                    msg.errorMessage()
            );
        }).toList();
    }

    private SessionInfo forkFromTranscript(ChatSession originalSession,
                                           List<SessionTranscriptRepository.TranscriptMessageViewRow> transcriptMessages,
                                           String fromEntryId,
                                           String newTitle) {
        int messageIndex = -1;
        for (int i = 0; i < transcriptMessages.size(); i++) {
            if (transcriptMessages.get(i).entryId().equals(fromEntryId)) {
                messageIndex = i;
                break;
            }
        }
        if (messageIndex == -1) {
            throw new IllegalArgumentException("\u5206\u53c9\u8d77\u70b9\u6761\u76ee\u4e0d\u5b58\u5728: entryId=" + fromEntryId);
        }

        List<SessionTranscriptRepository.TranscriptMessageViewRow> messagesToCopy =
                transcriptMessages.subList(0, messageIndex + 1);
        if (messagesToCopy.isEmpty()) {
            throw new IllegalArgumentException("\u6ca1\u6709\u53ef\u5206\u53c9\u7684\u6d88\u606f");
        }

        ChatSession newSession = createForkSession(originalSession, newTitle);
        Map<String, String> copiedEntryIds = new LinkedHashMap<>();
        for (SessionTranscriptRepository.TranscriptMessageViewRow originalMsg : messagesToCopy) {
            SessionTranscriptRepository.SessionTranscriptEntryRow sourceRow = transcriptRepository.findById(originalMsg.entryId())
                    .orElseThrow(() -> new IllegalStateException("transcript \u6761\u76ee\u4e0d\u5b58\u5728: id=" + originalMsg.entryId()));
            String copiedEntryId = transcriptRepository.copyEntry(newSession.id(), sourceRow);
            copiedEntryIds.put(originalMsg.entryId(), copiedEntryId);
            sessionRepository.appendMessageMeta(newSession.id(), sourceRow.createdAt(), preview(originalMsg.content()));
        }
        attachmentRepository.copyForFork(copiedEntryIds, newSession.id());
        copyKnowledgeBaseBindings(originalSession.id(), newSession.id());

        log.info("\u5206\u53c9\u4f1a\u8bdd(transcript): originalSessionId={}, newSessionId={}, messageCount={}",
                originalSession.id(), newSession.id(), messagesToCopy.size());
        return toSessionInfo(sessionRepository.findById(newSession.id()).orElse(newSession));
    }

    private ChatSession createForkSession(ChatSession originalSession, String newTitle) {
        String title = newTitle != null && !newTitle.isBlank()
                ? newTitle
                : originalSession.title() + " (fork)";
        ChatSession newSession = ChatSession.create(title);
        sessionRepository.save(newSession);
        return newSession;
    }

    private void copyKnowledgeBaseBindings(String sourceSessionId, String targetSessionId) {
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sourceSessionId);
        for (String knowledgeBaseId : knowledgeBaseIds) {
            sessionKnowledgeBaseRepository.addAssociation(targetSessionId, knowledgeBaseId);
        }
    }

    @Nullable
    private CompletionMode parseCompletionMode(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return CompletionMode.valueOf(rawValue.strip());
        } catch (IllegalArgumentException e) {
            log.warn("completion_mode \u53cd\u5e8f\u5217\u5316\u5931\u8d25: value={}", rawValue);
            return null;
        }
    }

    @Nullable
    private List<Map<String, Object>> deserializeReactSteps(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            log.warn("react_steps_json \u53cd\u5e8f\u5217\u5316\u5931\u8d25: error={}", e.getMessage());
            return null;
        }
    }

    private ChatSession requireWebSession(String id) {
        if (!isWebSessionId(id)) {
            throw new IllegalArgumentException("\u4ec5\u652f\u6301\u8bbf\u95ee Web \u4f1a\u8bdd: id=" + id);
        }
        return requireSession(id);
    }

    private boolean isWebSessionId(String id) {
        return id != null && !id.isBlank() && !id.contains(":");
    }

    private ChatSession requireSession(String id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("\u4f1a\u8bdd\u4e0d\u5b58\u5728: id=" + id));
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        if (text.length() <= 100) {
            return text;
        }
        return text.substring(0, 100) + "...";
    }

    private SessionInfo toSessionInfo(ChatSession session) {
        return new SessionInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                session.summary(),
                session.lastMessageAt()
        );
    }
}
