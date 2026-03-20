package com.lifepilot.memory.episodic;

import com.lifepilot.memory.config.MemoryProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 情景记忆的主读路径已经切换到会话层（`chat_sessions/chat_messages`）。
 *
 * <p>当前职责主要是提供对话检索、片段回忆和会话级读取能力。</p>
 */
public class EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemory.class);
    private static final int DEFAULT_SEARCH_LIMIT = 200;
    private static final int RECALL_CONTEXT_TURNS = 1;
    private static final int RECALL_CANDIDATE_MULTIPLIER = 6;

    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;

    @Nullable
    private Runnable writeCallback;

    public EpisodicMemory(JdbcTemplate jdbcTemplate, MemoryProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void setWriteCallback(@Nullable Runnable writeCallback) {
        this.writeCallback = writeCallback;
    }

    static String escapeFts5Query(String query) {
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    @Transactional
    public void save(ConversationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO conversations (id, session_id, goal, summary, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                record.id(), record.sessionId(), record.goal(), record.summary(),
                record.createdAt().toString(), record.updatedAt().toString());

        for (var msg : record.messages()) {
            jdbcTemplate.update(
                    "INSERT INTO messages (id, conversation_id, role, content, compressed_content, compression_level, is_pinned, tool_call_json, token_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    msg.id(), msg.conversationId(), msg.role(), msg.content(),
                    msg.compressedContent(), msg.compressionLevel().level(),
                    msg.isPinned() ? 1 : 0, msg.toolCallJson(), msg.tokenCount(),
                    msg.createdAt().toString());
        }

        notifyWriteCallback();
        log.debug("情景记忆已写入旧版对话记录: id={}, messages={}",
                record.id(), record.messageCount());
    }

    public List<ConversationRecord> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT id, title, summary, created_at, updated_at
                FROM chat_sessions
                ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                limit);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public List<ConversationRecord> getRecent(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return List.of();
        }
        String since = Instant.now().minus(duration).toString();
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT id, title, summary, created_at, updated_at
                FROM chat_sessions
                WHERE COALESCE(last_message_at, created_at) >= ?
                ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC
                """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                since);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public List<ConversationRecord> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> sessionIds = searchSessionIds(query, null, DEFAULT_SEARCH_LIMIT);
        return sessionIds.stream()
                .map(this::getById)
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<ConversationRecord> getById(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT id, title, summary, created_at, updated_at
                FROM chat_sessions
                WHERE id = ?
                """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                conversationId);
        return sessions.stream().findFirst().map(this::toConversationRecord);
    }

    public List<MessageRecord> getMessagesBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return loadChatMessages(sessionId);
    }

    public List<ConversationSnippetRecord> searchSnippetsExcludingSession(String query,
                                                                         String excludeSessionId,
                                                                         int limit) {
        if (query == null || query.isBlank() || excludeSessionId == null || excludeSessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            int candidateLimit = Math.max(limit * RECALL_CANDIDATE_MULTIPLIER, limit);
            List<RecallHitRow> hits = jdbcTemplate.query(
                    """
                    SELECT m.id AS message_id,
                           m.session_id AS session_id,
                           COALESCE(s.title, '') AS session_title,
                           COALESCE(s.summary, '') AS session_summary,
                           m.created_at AS created_at
                    FROM chat_messages_fts
                    JOIN chat_messages m ON m.rowid = chat_messages_fts.rowid
                    LEFT JOIN chat_sessions s ON s.id = m.session_id
                    WHERE chat_messages_fts MATCH ?
                      AND m.session_id <> ?
                    ORDER BY bm25(chat_messages_fts), m.created_at DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new RecallHitRow(
                            rs.getString("message_id"),
                            rs.getString("session_id"),
                            normalizeBlank(rs.getString("session_title")),
                            normalizeBlank(rs.getString("session_summary")),
                            Instant.parse(rs.getString("created_at"))),
                    escapeFts5Query(query),
                    excludeSessionId,
                    candidateLimit);

            if (hits.isEmpty()) {
                return List.of();
            }

            Map<String, List<TimelineMessage>> timelineCache = new HashMap<>();
            Map<String, ConversationSnippetRecord> snippets = new HashMap<>();
            int rank = 0;
            for (RecallHitRow hit : hits) {
                rank++;
                List<TimelineMessage> timeline = timelineCache.computeIfAbsent(
                        hit.sessionId(), this::loadTimelineMessages);
                ConversationSnippetRecord snippet = buildSnippet(hit, rank, timeline);
                if (snippet == null) {
                    continue;
                }
                ConversationSnippetRecord existing = snippets.get(snippet.id());
                if (existing == null || snippet.hitRank() < existing.hitRank()) {
                    snippets.put(snippet.id(), snippet);
                }
            }

            return snippets.values().stream()
                    .sorted(Comparator.comparingInt(ConversationSnippetRecord::hitRank)
                            .thenComparing(ConversationSnippetRecord::endedAt, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.warn("对话片段回忆失败: query={}, excludeSessionId={}, error={}",
                    query, excludeSessionId, e.getMessage());
            return List.of();
        }
    }

    public List<ConversationRecord> getByIntent(String intentType, int limit) {
        if (intentType == null || intentType.isBlank() || limit <= 0) {
            return List.of();
        }
        String pattern = "%" + intentType + "%";
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT id, title, summary, created_at, updated_at
                FROM chat_sessions
                WHERE title LIKE ? OR summary LIKE ?
                ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                pattern, pattern, limit);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public long countConversations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Long.class);
        return count != null ? count : 0L;
    }

    public List<ConversationRecord> listConversations(int page, int size) {
        if (size <= 0) {
            return List.of();
        }
        int safePage = Math.max(0, page);
        int offset = safePage * size;
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT id, title, summary, created_at, updated_at
                FROM chat_sessions
                ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new SessionRow(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                size, offset);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    @Transactional
    public boolean delete(String conversationId) {
        int rows = jdbcTemplate.update("DELETE FROM chat_sessions WHERE id = ?", conversationId);
        if (rows > 0) {
            log.info("已从情景记忆读模型删除会话: sessionId={}", conversationId);
        }
        return rows > 0;
    }

    @Transactional
    public void compress(String conversationId,
                         CompressionLevel targetLevel,
                         Map<String, String> compressedTexts) {
        if (compressedTexts == null || compressedTexts.isEmpty()) {
            return;
        }
        for (var entry : compressedTexts.entrySet()) {
            jdbcTemplate.update(
                    "UPDATE messages " +
                            "SET compressed_content = ?, compression_level = ? " +
                            "WHERE id = ? AND conversation_id = ? AND is_pinned = 0 AND compression_level < ?",
                    entry.getValue(),
                    targetLevel.level(),
                    entry.getKey(),
                    conversationId,
                    targetLevel.level());
        }
        log.info("已压缩旧版对话消息: conversationId={}, level={}, count={}",
                conversationId, targetLevel, compressedTexts.size());
    }

    private void notifyWriteCallback() {
        if (writeCallback == null) {
            return;
        }
        try {
            writeCallback.run();
        } catch (Exception e) {
            log.warn("情景记忆写回调执行失败: error={}", e.getMessage());
        }
    }

    private ConversationRecord toConversationRecord(SessionRow row) {
        List<MessageRecord> messages = loadChatMessages(row.id());
        return new ConversationRecord(
                row.id(),
                row.id(),
                row.title() != null ? row.title() : row.id(),
                row.summary(),
                messages,
                row.createdAt(),
                row.updatedAt());
    }

    private List<MessageRecord> loadChatMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, role, content, created_at
                FROM chat_messages
                WHERE session_id = ?
                ORDER BY created_at
                """,
                (rs, rowNum) -> new MessageRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        null,
                        CompressionLevel.ORIGINAL,
                        false,
                        null,
                        estimateTokenCount(rs.getString("content")),
                        Instant.parse(rs.getString("created_at"))),
                sessionId);
    }

    private List<TimelineMessage> loadTimelineMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, role, content, created_at
                FROM chat_messages
                WHERE session_id = ?
                ORDER BY created_at
                """,
                (rs, rowNum) -> new TimelineMessage(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        Instant.parse(rs.getString("created_at"))),
                sessionId);
    }

    private List<String> searchSessionIds(String query,
                                          @Nullable String excludeSessionId,
                                          int limit) {
        int candidateLimit = limit > 0 ? Math.max(limit * RECALL_CANDIDATE_MULTIPLIER, limit) : DEFAULT_SEARCH_LIMIT;
        List<String> hitSessionIds = excludeSessionId == null
                ? jdbcTemplate.query(
                """
                SELECT m.session_id
                FROM chat_messages_fts
                JOIN chat_messages m ON m.rowid = chat_messages_fts.rowid
                WHERE chat_messages_fts MATCH ?
                ORDER BY bm25(chat_messages_fts), m.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("session_id"),
                escapeFts5Query(query),
                candidateLimit)
                : jdbcTemplate.query(
                """
                SELECT m.session_id
                FROM chat_messages_fts
                JOIN chat_messages m ON m.rowid = chat_messages_fts.rowid
                WHERE chat_messages_fts MATCH ?
                  AND m.session_id <> ?
                ORDER BY bm25(chat_messages_fts), m.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("session_id"),
                escapeFts5Query(query),
                excludeSessionId,
                candidateLimit);

        if (hitSessionIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> ordered = new LinkedHashSet<>(hitSessionIds);
        List<String> sessionIds = new ArrayList<>(ordered);
        if (limit > 0 && sessionIds.size() > limit) {
            return List.copyOf(sessionIds.subList(0, limit));
        }
        return List.copyOf(sessionIds);
    }

    @Nullable
    private ConversationSnippetRecord buildSnippet(RecallHitRow hit,
                                                   int hitRank,
                                                   List<TimelineMessage> timeline) {
        if (timeline.isEmpty()) {
            return null;
        }
        List<List<TimelineMessage>> turns = groupTurnsForRecall(timeline);
        if (turns.isEmpty()) {
            return null;
        }

        int matchedTurnIndex = -1;
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).stream().anyMatch(message -> message.id().equals(hit.messageId()))) {
                matchedTurnIndex = i;
                break;
            }
        }
        if (matchedTurnIndex < 0) {
            return null;
        }

        int startTurn = Math.max(0, matchedTurnIndex - RECALL_CONTEXT_TURNS);
        int endTurn = Math.min(turns.size() - 1, matchedTurnIndex + RECALL_CONTEXT_TURNS);
        List<MessageRecord> snippetMessages = new ArrayList<>();
        for (int i = startTurn; i <= endTurn; i++) {
            for (TimelineMessage message : turns.get(i)) {
                snippetMessages.add(toMessageRecord(message));
            }
        }
        if (snippetMessages.isEmpty()) {
            return null;
        }

        return new ConversationSnippetRecord(
                hit.sessionId() + ":" + startTurn + ":" + endTurn,
                hit.sessionId(),
                hit.sessionTitle(),
                hit.sessionSummary(),
                hit.messageId(),
                hitRank,
                snippetMessages.get(0).createdAt(),
                snippetMessages.get(snippetMessages.size() - 1).createdAt(),
                snippetMessages);
    }

    private List<List<TimelineMessage>> groupTurnsForRecall(List<TimelineMessage> rows) {
        var turns = new ArrayList<List<TimelineMessage>>();
        var current = new ArrayList<TimelineMessage>();

        for (TimelineMessage row : rows) {
            if (isUserRole(row.role())) {
                if (!current.isEmpty()) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
                continue;
            }

            if (current.isEmpty()) {
                current = new ArrayList<>();
            }
            current.add(row);
        }

        if (!current.isEmpty()) {
            turns.add(List.copyOf(current));
        }
        return List.copyOf(turns);
    }

    private MessageRecord toMessageRecord(TimelineMessage message) {
        return new MessageRecord(
                message.id(),
                message.sessionId(),
                message.role(),
                message.content(),
                null,
                CompressionLevel.ORIGINAL,
                false,
                null,
                estimateTokenCount(message.content()),
                message.createdAt());
    }

    private boolean isUserRole(@Nullable String role) {
        return role != null && "user".equalsIgnoreCase(role.trim());
    }

    private int estimateTokenCount(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SessionRow(
            String id,
            @Nullable String title,
            @Nullable String summary,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record TimelineMessage(
            String id,
            String sessionId,
            String role,
            String content,
            Instant createdAt
    ) {
    }

    private record RecallHitRow(
            String messageId,
            String sessionId,
            @Nullable String sessionTitle,
            @Nullable String sessionSummary,
            Instant createdAt
    ) {
    }
}
