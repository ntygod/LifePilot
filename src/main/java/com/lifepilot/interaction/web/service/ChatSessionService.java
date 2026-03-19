package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.model.AttachmentInfo;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.model.SessionConfigRequest;
import com.lifepilot.interaction.web.model.SessionDetailInfo;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final AttachmentRepository attachmentRepository;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository chatMessageRepository,
                              SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                              AttachmentRepository attachmentRepository) {
        this.sessionRepository = sessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.attachmentRepository = attachmentRepository;
    }

    @Transactional
    public ChatSession createSession(String title) {
        ChatSession session = ChatSession.create(title);
        sessionRepository.save(session);
        log.info("创建会话: id={}, title={}", session.id(), session.title());
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
        return sessionRepository.findById(id);
    }

    @Transactional
    public ChatSession updateTitle(String id, String title) {
        ChatSession session = requireSession(id);
        sessionRepository.updateTitle(id, title);
        log.info("更新会话标题: id={}, title={}", id, title);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public ChatSession updatePinned(String id, boolean isPinned) {
        ChatSession session = requireSession(id);
        sessionRepository.updatePinned(id, isPinned);
        log.info("更新会话置顶状态: id={}, pinned={}", id, isPinned);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public SessionInfo updateSession(String id, String title, Boolean pinned, Boolean archived) {
        ChatSession session = requireSession(id);

        if (title == null && pinned == null && archived == null) {
            log.debug("跳过空会话更新: id={}", id);
            return toSessionInfo(session);
        }

        sessionRepository.updateFields(id, title, pinned, archived);
        log.info("更新会话: id={}, title={}, pinned={}, archived={}", id, title, pinned, archived);

        ChatSession updatedSession = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("会话更新后消失: id=" + id));
        return toSessionInfo(updatedSession);
    }

    @Transactional
    public void deleteSession(String id) {
        sessionRepository.deleteById(id);
        log.info("删除会话: id={}", id);
    }

    @Transactional
    public void clearSessionMessages(String id) {
        requireSession(id);
        chatMessageRepository.deleteBySessionId(id);
        sessionRepository.clearMessages(id);
        log.info("清空会话消息: id={}", id);
    }

    @Transactional
    public void incrementMessageCount(String id) {
        sessionRepository.incrementMessageCount(id);
    }

    public List<MessageInfo> getSessionMessages(String id) {
        requireSession(id);
        var messages = chatMessageRepository.findMessageInfosBySessionId(id);
        if (messages.isEmpty()) {
            return messages;
        }
        // 批量查询所有消息的附件，避免 N+1
        var messageIds = messages.stream().map(MessageInfo::id).toList();
        var attachmentMap = attachmentRepository.findByMessageIds(messageIds);
        if (attachmentMap.isEmpty()) {
            return messages;
        }
        // 将附件信息填充到 MessageInfo
        return messages.stream().map(msg -> {
            var records = attachmentMap.get(msg.id());
            if (records == null || records.isEmpty()) {
                return msg;
            }
            var attachments = records.stream()
                    .map(r -> new AttachmentInfo(r.id(), r.fileName(), r.fileSize(), r.mimeType(), r.url()))
                    .toList();
            return new MessageInfo(msg.id(), msg.role(), msg.content(), msg.a2uiComponents(),
                    msg.timestamp(), msg.reasoningSummary(), msg.traceId(), attachments, msg.reactSteps());
        }).toList();
    }

    @Transactional
    public int batchUpdateSessions(String action, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.warn("跳过批量会话更新: ID 列表为空");
            return 0;
        }

        return switch (action) {
            case "pin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, true, null);
                log.info("批量置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unpin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, false, null);
                log.info("批量取消置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "archive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, true);
                log.info("批量归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unarchive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, false);
                log.info("批量取消归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "delete" -> {
                int count = sessionRepository.batchDelete(sessionIds);
                log.info("批量删除会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            default -> throw new IllegalArgumentException("不支持的批量操作: " + action);
        };
    }

    public SessionDetailInfo getSessionDetail(String id) {
        ChatSession session = requireSession(id);
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
                getDoubleConfig(sessionConfig, SessionConfigKeys.TEMPERATURE),
                getIntegerConfig(sessionConfig, SessionConfigKeys.MAX_TOKENS),
                knowledgeBaseIds,
                session.messageCount(),
                0L,
                session.summary()
        );
    }

    @Transactional
    public SessionInfo forkSession(String originalSessionId, String fromMessageId, String newTitle) {
        ChatSession originalSession = requireSession(originalSessionId);
        List<? extends ChatMessageRepository.ChatMessageRow> allMessages =
                chatMessageRepository.findRowsBySessionId(originalSessionId);

        int messageIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).id().equals(fromMessageId)) {
                messageIndex = i;
                break;
            }
        }

        if (messageIndex == -1) {
            throw new IllegalArgumentException("消息不存在: messageId=" + fromMessageId);
        }

        List<? extends ChatMessageRepository.ChatMessageRow> messagesToCopy = allMessages.subList(0, messageIndex + 1);
        if (messagesToCopy.isEmpty()) {
            throw new IllegalArgumentException("没有可分叉的消息");
        }

        String title = newTitle != null && !newTitle.isBlank()
                ? newTitle
                : originalSession.title() + " (fork)";
        ChatSession newSession = ChatSession.create(title);
        sessionRepository.save(newSession);

        for (ChatMessageRepository.ChatMessageRow originalMsg : messagesToCopy) {
            chatMessageRepository.insert(
                    newSession.id(),
                    originalMsg.role(),
                    originalMsg.content(),
                    originalMsg.reasoningSummary(),
                    originalMsg.traceId(),
                    originalMsg.createdAt(),
                    originalMsg.a2uiComponentsJson(),
                    null
            );
        }

        for (ChatMessageRepository.ChatMessageRow originalMsg : messagesToCopy) {
            sessionRepository.appendMessageMeta(newSession.id(), originalMsg.createdAt(), preview(originalMsg.content()));
        }

        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(originalSessionId);
        for (String knowledgeBaseId : knowledgeBaseIds) {
            sessionKnowledgeBaseRepository.addAssociation(newSession.id(), knowledgeBaseId);
        }

        log.info("分叉会话: originalSessionId={}, newSessionId={}, messageCount={}",
                originalSessionId, newSession.id(), messagesToCopy.size());

        return toSessionInfo(newSession);
    }

    @Transactional
    public void updateSessionConfig(String id, SessionConfigRequest request) {
        requireSession(id);

        Map<String, Object> config = new HashMap<>();
        config.put(SessionConfigKeys.PREFERRED_PROVIDER, SessionConfigKeys.normalizeString(request.preferredProviderId()));
        config.put(SessionConfigKeys.LEGACY_MODEL_ID, null);
        config.put(SessionConfigKeys.TEMPERATURE, request.temperature());
        config.put(SessionConfigKeys.MAX_TOKENS, request.maxTokens());

        sessionRepository.updateConfig(id, config);
        log.info("更新会话配置: sessionId={}, config={}", id, config);

        if (request.knowledgeBaseIds() != null) {
            sessionKnowledgeBaseRepository.setAssociations(id, request.knowledgeBaseIds());
            log.info("更新会话知识库关联: sessionId={}, knowledgeBaseIds={}",
                    id, request.knowledgeBaseIds());
        }
    }

    private ChatSession requireSession(String id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));
    }

    @Nullable
    private Double getDoubleConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private Integer getIntegerConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
