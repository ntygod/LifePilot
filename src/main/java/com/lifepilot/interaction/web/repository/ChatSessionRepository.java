package com.lifepilot.interaction.web.repository;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web \u4f1a\u8bdd\u8bfb\u5199\u4ed3\u5e93\u3002
 *
 * <p>\u5f53\u524d\u5b9e\u73b0\u5b8c\u5168\u57fa\u4e8e {@code session_store}\u3002</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@Repository
public class ChatSessionRepository {

    private final SessionStoreRepository sessionStoreRepository;

    public ChatSessionRepository(SessionStoreRepository sessionStoreRepository) {
        this.sessionStoreRepository = sessionStoreRepository;
    }

    public void save(ChatSession session) {
        sessionStoreRepository.save(session);
    }

    public void appendMessageMeta(String sessionId, Instant messageAt, String lastMessagePreview) {
        sessionStoreRepository.appendMessageMeta(sessionId, messageAt, lastMessagePreview);
    }

    public Optional<ChatSession> findById(String id) {
        return sessionStoreRepository.findBySessionId(id).map(this::mapRow);
    }

    public List<ChatSession> findAll() {
        return sessionStoreRepository.findWebSessions().stream()
                .map(this::mapRow)
                .toList();
    }

    public List<ChatSession> findByConditions(String q,
                                              Boolean pinned,
                                              Boolean archived,
                                              String timeRange,
                                              String sortBy,
                                              String order) {
        return sessionStoreRepository.findWebSessionsByConditions(q, pinned, archived, timeRange, sortBy, order)
                .stream()
                .map(this::mapRow)
                .toList();
    }

    public void deleteById(String id) {
        sessionStoreRepository.deleteById(id);
    }

    public void updateTitle(String id, String title) {
        sessionStoreRepository.updateTitle(id, title);
    }

    public void updatePinned(String id, boolean pinned) {
        sessionStoreRepository.updatePinned(id, pinned);
    }

    public void updateArchived(String id, boolean archived) {
        sessionStoreRepository.updateArchived(id, archived);
    }

    public void updateFields(String id, String title, Boolean pinned, Boolean archived) {
        sessionStoreRepository.updateFields(id, title, pinned, archived);
    }

    public void incrementMessageCount(String id) {
        sessionStoreRepository.incrementMessageCount(id);
    }

    public void clearMessages(String id) {
        sessionStoreRepository.clearMessages(id);
    }

    public int batchUpdateFields(List<String> ids, String title, Boolean pinned, Boolean archived) {
        return sessionStoreRepository.batchUpdateFields(ids, title, pinned, archived);
    }

    public int batchDelete(List<String> ids) {
        return sessionStoreRepository.batchDelete(ids);
    }

    public void updateConfig(String id, Map<String, Object> config) {
        sessionStoreRepository.updateConfig(id, config);
    }

    public Map<String, Object> getConfig(String id) {
        return sessionStoreRepository.getConfig(id);
    }

    private ChatSession mapRow(SessionStoreRepository.SessionStoreRow row) {
        return new ChatSession(
                row.sessionId(),
                row.title(),
                row.summary(),
                row.messageCount(),
                row.pinned(),
                row.archived(),
                row.lastMessageAt(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
