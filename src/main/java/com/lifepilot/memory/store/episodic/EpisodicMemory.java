package com.lifepilot.memory.store.episodic;

import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.ConversationSnippetRecord;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.retrieval.SQLiteFtsQueryNormalizer;
import com.lifepilot.memory.store.support.MemoryQuerySignals;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 情景记忆的主读路径已经切换到会话层（`session_store/session_transcript_entries`）。
 *
 * <p>当前职责主要是提供对话检索、片段回忆和会话级读取能力。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemory.class);
    private static final int DEFAULT_SEARCH_LIMIT = 200;
    private static final int RECALL_CONTEXT_TURNS = 1;
    private static final int RECALL_CANDIDATE_MULTIPLIER = 6;

    private final JdbcTemplate jdbcTemplate;

    @Nullable
    private Runnable writeCallback;

    public EpisodicMemory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setWriteCallback(@Nullable Runnable writeCallback) {
        this.writeCallback = writeCallback;
    }

    static String escapeFts5Query(String query) {
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    @Transactional
    public void save(ConversationRecord record) {
        if (record == null) {
            return;
        }
        String sessionId = normalizeBlank(record.sessionId()) != null ? record.sessionId() : record.id();
        Instant lastMessageAt = record.messages().isEmpty()
                ? record.updatedAt()
                : record.messages().getLast().createdAt();
        // 记忆导入通道没有会话级 ProjectContext，默认 project_id = NULL，归属主账户。
        // 真实对话的项目归属由 TranscriptStore / ChatTurnService 写入。
        jdbcTemplate.update("""
                        INSERT INTO session_store (
                            session_id, channel, chat_type, title, summary, message_count,
                            is_pinned, archived, last_message_at, created_at, updated_at, last_activity_at,
                            active_branch_id, project_id
                        ) VALUES (?, 'memory-import', 'chat', ?, ?, ?, 0, 0, ?, ?, ?, ?, 'main', NULL)
                        ON CONFLICT(session_id) DO UPDATE SET
                            title = excluded.title,
                            summary = excluded.summary,
                            message_count = excluded.message_count,
                            last_message_at = excluded.last_message_at,
                            updated_at = excluded.updated_at,
                            last_activity_at = excluded.last_activity_at
                        """,
                sessionId,
                normalizeBlank(record.goal()) != null ? record.goal() : sessionId,
                record.summary(),
                record.messages().size(),
                lastMessageAt != null ? lastMessageAt.toString() : null,
                record.createdAt().toString(),
                record.updatedAt().toString(),
                lastMessageAt != null ? lastMessageAt.toString() : record.updatedAt().toString());
        jdbcTemplate.update("DELETE FROM session_transcript_entries WHERE session_id = ?", sessionId);

        for (var msg : record.messages()) {
            String payloadJson = "{\"content\":\"" + escapeJson(msg.content()) + "\"}";
            jdbcTemplate.update("""
                            INSERT INTO session_transcript_entries (
                                id, session_id, branch_id, entry_type, role, turn_id, trace_id,
                                visible_to_model, visible_to_user, payload_json, token_estimate, created_at
                            ) VALUES (?, ?, 'main', ?, ?, NULL, NULL, 1, 1, ?, ?, ?)
                            """,
                    msg.id(),
                    sessionId,
                    TranscriptEntryType.fromRole(msg.role()).value(),
                    msg.role(),
                    payloadJson,
                    Math.max(0, msg.tokenCount()),
                    msg.createdAt().toString());
        }

        notifyWriteCallback();
        log.debug("情景记忆已写入 transcript 读模型: sessionId={}, messages={}",
                sessionId, record.messageCount());
    }

    public List<ConversationRecord> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
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
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                WHERE COALESCE(last_activity_at, last_message_at, created_at) >= ?
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
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
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                WHERE session_id = ?
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
        return loadTranscriptMessages(sessionId);
    }

    public List<ConversationSnippetRecord> searchSnippetsExcludingSession(String query,
                                                                          String excludeSessionId,
                                                                          int limit) {
        if (query == null || query.isBlank() || excludeSessionId == null || excludeSessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            int candidateLimit = Math.max(limit * RECALL_CANDIDATE_MULTIPLIER, limit);
            List<RecallHitRow> hits = searchRecallHits(query, excludeSessionId, candidateLimit);

            if (hits.isEmpty()) {
                if (!shouldUseRecentRecallFallback(query)) {
                    return List.of();
                }
                hits = searchRecentRecallHits(excludeSessionId, candidateLimit);
                if (hits.isEmpty()) {
                    return List.of();
                }
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
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                WHERE title LIKE ? OR summary LIKE ?
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
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
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM session_store", Long.class);
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
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
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
        int rows = jdbcTemplate.update("DELETE FROM session_store WHERE session_id = ?", conversationId);
        if (rows > 0) {
            log.info("已从 transcript 会话读模型删除会话: sessionId={}", conversationId);
        }
        return rows > 0;
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
        List<MessageRecord> messages = loadTranscriptMessages(row.id());
        return new ConversationRecord(
                row.id(),
                row.id(),
                row.title() != null ? row.title() : row.id(),
                row.summary(),
                messages,
                row.createdAt(),
                row.updatedAt());
    }

    private List<MessageRecord> loadTranscriptMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT e.id,
                       e.session_id,
                       e.role,
                       json_extract(e.payload_json, '$.content') AS content,
                       e.created_at
                FROM session_transcript_entries e
                WHERE e.session_id = ?
                  AND e.entry_type IN ('user_message', 'assistant_message')
                  AND e.visible_to_user = 1
                  AND trim(COALESCE(json_extract(e.payload_json, '$.content'), '')) <> ''
                ORDER BY e.created_at, e.rowid
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

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<TimelineMessage> loadTimelineMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id,
                       session_id,
                       role,
                       json_extract(payload_json, '$.content') AS content,
                       created_at
                FROM session_transcript_entries
                WHERE session_id = ?
                  AND entry_type IN ('user_message', 'assistant_message')
                  AND visible_to_user = 1
                  AND trim(COALESCE(json_extract(payload_json, '$.content'), '')) <> ''
                ORDER BY created_at, rowid
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
        LinkedHashSet<String> hitSessionIds = new LinkedHashSet<>();
        searchSessionIdsByText(query, excludeSessionId, candidateLimit).forEach(hitSessionIds::add);
        String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(query);
        if (!normalizedQuery.isBlank()) {
            searchSessionIdsByFts(normalizedQuery, excludeSessionId, candidateLimit).forEach(hitSessionIds::add);
        }

        if (hitSessionIds.isEmpty()) {
            return List.of();
        }

        List<String> sessionIds = new ArrayList<>(hitSessionIds);
        if (limit > 0 && sessionIds.size() > limit) {
            return List.copyOf(sessionIds.subList(0, limit));
        }
        return List.copyOf(sessionIds);
    }

    private List<RecallHitRow> searchRecallHits(String query,
                                                String excludeSessionId,
                                                int candidateLimit) {
        LinkedHashSet<RecallHitRow> ordered = new LinkedHashSet<>();
        ordered.addAll(searchRecallHitsByText(query, excludeSessionId, candidateLimit));
        String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(query);
        if (!normalizedQuery.isBlank()) {
            ordered.addAll(searchRecallHitsByFts(normalizedQuery, excludeSessionId, candidateLimit));
        }
        return List.copyOf(ordered);
    }

    private boolean shouldUseRecentRecallFallback(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (!MemoryQuerySignals.highSignalTerms(query).isEmpty()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains("别的会话")
                || normalized.contains("其他会话")
                || normalized.contains("跨会话")
                || normalized.contains("历史会话")
                || normalized.contains("之前的会话")
                || normalized.contains("以前的会话")
                || normalized.contains("之前聊")
                || normalized.contains("以前聊")
                || normalized.contains("上次聊")
                || normalized.contains("聊过什么")
                || normalized.contains("我们聊过")
                || normalized.contains("previous session")
                || normalized.contains("past conversation");
    }

    private List<RecallHitRow> searchRecentRecallHits(String excludeSessionId, int candidateLimit) {
        return jdbcTemplate.query(
                """
                SELECT e.id AS entry_id,
                       e.session_id AS session_id,
                       COALESCE(s.title, '') AS session_title,
                       COALESCE(s.summary, '') AS session_summary,
                       e.created_at AS created_at
                FROM session_transcript_entries e
                LEFT JOIN session_store s ON s.session_id = e.session_id
                WHERE e.session_id <> ?
                  AND e.entry_type IN ('user_message', 'assistant_message')
                  AND e.visible_to_user = 1
                  AND trim(COALESCE(json_extract(e.payload_json, '$.content'), '')) <> ''
                  AND NOT EXISTS (
                      SELECT 1
                      FROM session_transcript_entries newer
                      WHERE newer.session_id = e.session_id
                        AND newer.entry_type IN ('user_message', 'assistant_message')
                        AND newer.visible_to_user = 1
                        AND trim(COALESCE(json_extract(newer.payload_json, '$.content'), '')) <> ''
                        AND (
                            newer.created_at > e.created_at
                            OR (newer.created_at = e.created_at AND newer.rowid > e.rowid)
                        )
                  )
                ORDER BY e.created_at DESC, e.rowid DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RecallHitRow(
                        rs.getString("entry_id"),
                        rs.getString("session_id"),
                        normalizeBlank(rs.getString("session_title")),
                        normalizeBlank(rs.getString("session_summary")),
                        Instant.parse(rs.getString("created_at"))),
                excludeSessionId,
                candidateLimit);
    }

    private List<RecallHitRow> searchRecallHitsByFts(String normalizedQuery,
                                                     String excludeSessionId,
                                                     int candidateLimit) {
        return jdbcTemplate.query(
                """
                SELECT e.id AS entry_id,
                       e.session_id AS session_id,
                       COALESCE(s.title, '') AS session_title,
                       COALESCE(s.summary, '') AS session_summary,
                       e.created_at AS created_at
                FROM session_transcript_entries_fts
                JOIN session_transcript_entries e ON e.rowid = session_transcript_entries_fts.rowid
                LEFT JOIN session_store s ON s.session_id = e.session_id
                WHERE session_transcript_entries_fts MATCH ?
                  AND e.session_id <> ?
                ORDER BY bm25(session_transcript_entries_fts), e.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RecallHitRow(
                        rs.getString("entry_id"),
                        rs.getString("session_id"),
                        normalizeBlank(rs.getString("session_title")),
                        normalizeBlank(rs.getString("session_summary")),
                        Instant.parse(rs.getString("created_at"))),
                normalizedQuery,
                excludeSessionId,
                candidateLimit);
    }

    private List<RecallHitRow> searchRecallHitsByText(String query,
                                                      String excludeSessionId,
                                                      int candidateLimit) {
        List<String> terms = MemoryQuerySignals.lookupTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String contentClause = MemoryQuerySignals.likeWhereClause(
                "json_extract(e.payload_json, '$.content')", terms.size());
        String sql = """
                SELECT e.id AS entry_id,
                       e.session_id AS session_id,
                       COALESCE(s.title, '') AS session_title,
                       COALESCE(s.summary, '') AS session_summary,
                       e.created_at AS created_at
                FROM session_transcript_entries e
                LEFT JOIN session_store s ON s.session_id = e.session_id
                WHERE e.session_id <> ?
                  AND e.entry_type IN ('user_message', 'assistant_message')
                  AND e.visible_to_user = 1
                  AND trim(COALESCE(json_extract(e.payload_json, '$.content'), '')) <> ''
                  AND (%s)
                ORDER BY e.created_at DESC
                LIMIT ?
                """.formatted(contentClause);
        List<Object> args = new ArrayList<>();
        args.add(excludeSessionId);
        for (String term : terms) {
            args.add(MemoryQuerySignals.likePattern(term));
        }
        args.add(candidateLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RecallHitRow(
                rs.getString("entry_id"),
                rs.getString("session_id"),
                normalizeBlank(rs.getString("session_title")),
                normalizeBlank(rs.getString("session_summary")),
                Instant.parse(rs.getString("created_at"))), args.toArray());
    }

    private List<String> searchSessionIdsByFts(String normalizedQuery,
                                               @Nullable String excludeSessionId,
                                               int candidateLimit) {
        if (excludeSessionId == null) {
            return jdbcTemplate.query(
                    """
                    SELECT e.session_id
                    FROM session_transcript_entries_fts
                    JOIN session_transcript_entries e ON e.rowid = session_transcript_entries_fts.rowid
                    WHERE session_transcript_entries_fts MATCH ?
                    ORDER BY bm25(session_transcript_entries_fts), e.created_at DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> rs.getString("session_id"),
                    normalizedQuery,
                    candidateLimit);
        }
        return jdbcTemplate.query(
                """
                SELECT e.session_id
                FROM session_transcript_entries_fts
                JOIN session_transcript_entries e ON e.rowid = session_transcript_entries_fts.rowid
                WHERE session_transcript_entries_fts MATCH ?
                  AND e.session_id <> ?
                ORDER BY bm25(session_transcript_entries_fts), e.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getString("session_id"),
                normalizedQuery,
                excludeSessionId,
                candidateLimit);
    }

    private List<String> searchSessionIdsByText(String query,
                                                @Nullable String excludeSessionId,
                                                int candidateLimit) {
        List<String> terms = MemoryQuerySignals.lookupTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String contentClause = MemoryQuerySignals.likeWhereClause(
                "json_extract(e.payload_json, '$.content')", terms.size());
        String excludeClause = excludeSessionId == null ? "" : "AND e.session_id <> ?";
        String sql = """
                SELECT e.session_id
                FROM session_transcript_entries e
                WHERE e.entry_type IN ('user_message', 'assistant_message')
                  AND e.visible_to_user = 1
                  AND trim(COALESCE(json_extract(e.payload_json, '$.content'), '')) <> ''
                  %s
                  AND (%s)
                ORDER BY e.created_at DESC
                LIMIT ?
                """.formatted(excludeClause, contentClause);
        List<Object> args = new ArrayList<>();
        if (excludeSessionId != null) {
            args.add(excludeSessionId);
        }
        for (String term : terms) {
            args.add(MemoryQuerySignals.likePattern(term));
        }
        args.add(candidateLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("session_id"), args.toArray());
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
            if (turns.get(i).stream().anyMatch(message -> message.id().equals(hit.entryId()))) {
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
                hit.entryId(),
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
            String entryId,
            String sessionId,
            @Nullable String sessionTitle,
            @Nullable String sessionSummary,
            Instant createdAt
    ) {
    }
}
