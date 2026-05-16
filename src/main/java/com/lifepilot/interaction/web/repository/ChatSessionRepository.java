package com.lifepilot.interaction.web.repository;

import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web 会话读写仓库。
 *
 * <p>当前实现完全基于 {@code session_store}。</p>
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
        return findByConditions(q, pinned, archived, timeRange, sortBy, order,
                SessionStoreRepository.ProjectScope.mainAccount());
    }

    /**
     * 支持项目作用域的会话列表查询。
     */
    public List<ChatSession> findByConditions(String q,
                                              Boolean pinned,
                                              Boolean archived,
                                              String timeRange,
                                              String sortBy,
                                              String order,
                                              SessionStoreRepository.ProjectScope projectScope) {
        return sessionStoreRepository
                .findWebSessionsByConditions(q, pinned, archived, timeRange, sortBy, order, projectScope)
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

    public void updateSummary(String id, String summary) {
        sessionStoreRepository.updateSummary(id, summary);
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
        com.lifepilot.memory.support.SqliteBusyRetry.run(() -> sessionStoreRepository.updateConfig(id, config));
    }

    public Map<String, Object> getConfig(String id) {
        return sessionStoreRepository.getConfig(id);
    }

    public long[] getTokenUsage(String id) {
        return sessionStoreRepository.getTokenUsage(id);
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
                row.updatedAt(),
                row.projectId()
        );
    }
}
