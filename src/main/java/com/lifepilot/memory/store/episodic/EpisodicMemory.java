package com.lifepilot.memory.store.episodic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Objects;
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
    private final ObjectMapper objectMapper;

    public EpisodicMemory(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    public EpisodicMemory(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    static String escapeFts5Query(String query) {
        Objects.requireNonNull(query, "FTS5 查询不能为空");
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    @Transactional
    public void save(ConversationRecord record) {
        validateConversationRecordForSave(record);
        String sessionId = record.sessionId();
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
                record.goal(),
                record.summary(),
                record.messages().size(),
                lastMessageAt != null ? lastMessageAt.toString() : null,
                record.createdAt().toString(),
                record.updatedAt().toString(),
                lastMessageAt != null ? lastMessageAt.toString() : record.updatedAt().toString());
        jdbcTemplate.update("DELETE FROM session_transcript_entries WHERE session_id = ?", sessionId);

        for (var msg : record.messages()) {
            String payloadJson = payloadJson(msg);
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
                    tokenEstimate(msg),
                    msg.createdAt().toString());
        }

        log.debug("情景记忆已写入 transcript 读模型: sessionId={}, messages={}",
                sessionId, record.messageCount());
    }

    public List<ConversationRecord> getRecent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("情景记忆最近会话 limit 必须大于 0: " + limit);
        }
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapSessionRow(rs),
                limit);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public List<ConversationRecord> getRecent(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("情景记忆最近会话 duration 必须为正数");
        }
        String since = Instant.now().minus(duration).toString();
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                WHERE COALESCE(last_activity_at, last_message_at, created_at) >= ?
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
                """,
                (rs, rowNum) -> mapSessionRow(rs),
                since);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public List<ConversationRecord> search(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("情景记忆搜索关键词不能为空");
        }
        List<String> sessionIds = searchSessionIds(query, null, DEFAULT_SEARCH_LIMIT);
        return sessionIds.stream()
                .map(sessionId -> getById(sessionId)
                        .orElseThrow(() -> new IllegalStateException("情景记忆搜索命中缺少会话记录: " + sessionId)))
                .toList();
    }

    public Optional<ConversationRecord> getById(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("情景记忆会话 ID 不能为空");
        }
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                WHERE session_id = ?
                """,
                (rs, rowNum) -> mapSessionRow(rs),
                conversationId);
        return sessions.stream().findFirst().map(this::toConversationRecord);
    }

    public List<MessageRecord> getMessagesBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("情景记忆会话 ID 不能为空");
        }
        return loadTranscriptMessages(sessionId);
    }

    public List<ConversationSnippetRecord> searchSnippetsExcludingSession(String query,
                                                                          String excludeSessionId,
                                                                          int limit) {
        if (query == null || query.isBlank() || excludeSessionId == null || excludeSessionId.isBlank() || limit <= 0) {
            throw new IllegalArgumentException("跨会话片段召回参数非法");
        }

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
    }

    public List<ConversationRecord> getByIntent(String intentType, int limit) {
        if (intentType == null || intentType.isBlank() || limit <= 0) {
            throw new IllegalArgumentException("情景记忆意图查询参数非法");
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
                (rs, rowNum) -> mapSessionRow(rs),
                pattern, pattern, limit);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    public long countConversations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM session_store", Long.class);
        if (count == null) {
            throw new IllegalStateException("情景记忆会话计数查询返回 null");
        }
        return count;
    }

    public List<ConversationRecord> listConversations(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("情景记忆分页 page 不能为负数: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("情景记忆分页 size 必须大于 0: " + size);
        }
        int offset = Math.multiplyExact(page, size);
        List<SessionRow> sessions = jdbcTemplate.query(
                """
                SELECT session_id AS id, title, summary, created_at, updated_at
                FROM session_store
                ORDER BY COALESCE(last_activity_at, last_message_at, updated_at, created_at) DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> mapSessionRow(rs),
                size, offset);
        return sessions.stream().map(this::toConversationRecord).toList();
    }

    @Transactional
    public boolean delete(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("情景记忆会话 ID 不能为空");
        }
        int rows = jdbcTemplate.update("DELETE FROM session_store WHERE session_id = ?", conversationId);
        if (rows > 0) {
            log.info("已从 transcript 会话读模型删除会话: sessionId={}", conversationId);
        }
        return rows > 0;
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
                (rs, rowNum) -> {
                    String content = requiredText(rs, "content", "情景记忆消息");
                    return new MessageRecord(
                            requiredText(rs, "id", "情景记忆消息"),
                            requiredText(rs, "session_id", "情景记忆消息"),
                            requireUserOrAssistantRole(requiredText(rs, "role", "情景记忆消息")),
                            content,
                            null,
                            CompressionLevel.ORIGINAL,
                            false,
                            null,
                            estimateTokenCount(content),
                            parseRequiredInstant(rs.getString("created_at"), "情景记忆消息.created_at"));
                },
                sessionId);
    }

    private String payloadJson(MessageRecord message) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", message.content()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("情景记忆消息 payload 序列化失败: " + message.id(), e);
        }
    }

    private int tokenEstimate(MessageRecord message) {
        if (message.tokenCount() < 0) {
            throw new IllegalArgumentException("情景记忆消息 tokenCount 不能为负数: " + message.id());
        }
        return message.tokenCount();
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
                (rs, rowNum) -> mapTimelineMessage(rs),
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
                (rs, rowNum) -> mapRecallHitRow(rs),
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
                (rs, rowNum) -> mapRecallHitRow(rs),
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
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRecallHitRow(rs), args.toArray());
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
                    (rs, rowNum) -> requiredCleanText(rs.getString("session_id"), "情景记忆搜索命中 session_id"),
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
                (rs, rowNum) -> requiredCleanText(rs.getString("session_id"), "情景记忆搜索命中 session_id"),
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
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> requiredCleanText(rs.getString("session_id"), "情景记忆搜索命中 session_id"),
                args.toArray());
    }

    private ConversationSnippetRecord buildSnippet(RecallHitRow hit,
                                                   int hitRank,
                                                   List<TimelineMessage> timeline) {
        if (timeline.isEmpty()) {
            throw new IllegalStateException("情景记忆召回命中缺少时间线: sessionId=" + hit.sessionId());
        }
        List<List<TimelineMessage>> turns = groupTurnsForRecall(timeline);
        if (turns.isEmpty()) {
            throw new IllegalStateException("情景记忆召回时间线无法分组: sessionId=" + hit.sessionId());
        }

        int matchedTurnIndex = -1;
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).stream().anyMatch(message -> message.id().equals(hit.entryId()))) {
                matchedTurnIndex = i;
                break;
            }
        }
        if (matchedTurnIndex < 0) {
            throw new IllegalStateException("情景记忆召回命中不在时间线中: entryId=" + hit.entryId());
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
            throw new IllegalStateException("情景记忆召回片段为空: entryId=" + hit.entryId());
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
        return "user".equals(role);
    }

    private void validateConversationRecordForSave(@Nullable ConversationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("情景记忆记录不能为空");
        }
        requiredArgumentCleanText(record.id(), "情景记忆记录 ID");
        requiredArgumentCleanText(record.sessionId(), "情景记忆会话 ID");
        requiredArgumentText(record.goal(), "情景记忆目标");
        Objects.requireNonNull(record.messages(), "情景记忆消息列表不能为空");
        Objects.requireNonNull(record.createdAt(), "情景记忆 createdAt 不能为空");
        Objects.requireNonNull(record.updatedAt(), "情景记忆 updatedAt 不能为空");
        for (MessageRecord message : record.messages()) {
            validateMessageForSave(message, record.sessionId());
        }
    }

    private void validateMessageForSave(@Nullable MessageRecord message, String sessionId) {
        if (message == null) {
            throw new IllegalArgumentException("情景记忆消息不能为空");
        }
        requiredArgumentCleanText(message.id(), "情景记忆消息 ID");
        String messageConversationId = requiredArgumentCleanText(message.conversationId(), "情景记忆消息会话 ID");
        if (!messageConversationId.equals(sessionId)) {
            throw new IllegalArgumentException("情景记忆消息会话 ID 与记录会话 ID 不一致: messageId=" + message.id());
        }
        requireUserOrAssistantRoleForSave(message.role());
        requiredArgumentText(message.content(), "情景记忆消息内容");
        Objects.requireNonNull(message.compressionLevel(), "情景记忆消息 compressionLevel 不能为空");
        Objects.requireNonNull(message.createdAt(), "情景记忆消息 createdAt 不能为空");
        tokenEstimate(message);
    }

    private SessionRow mapSessionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(
                requiredCleanText(rs.getString("id"), "情景记忆会话 ID"),
                normalizeBlank(rs.getString("title")),
                normalizeBlank(rs.getString("summary")),
                parseRequiredInstant(rs.getString("created_at"), "情景记忆会话.created_at"),
                parseRequiredInstant(rs.getString("updated_at"), "情景记忆会话.updated_at"));
    }

    private TimelineMessage mapTimelineMessage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TimelineMessage(
                requiredCleanText(rs.getString("id"), "情景记忆时间线消息 ID"),
                requiredCleanText(rs.getString("session_id"), "情景记忆时间线会话 ID"),
                requireUserOrAssistantRole(rs.getString("role")),
                requiredText(rs, "content", "情景记忆时间线内容"),
                parseRequiredInstant(rs.getString("created_at"), "情景记忆时间线.created_at"));
    }

    private RecallHitRow mapRecallHitRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecallHitRow(
                requiredCleanText(rs.getString("entry_id"), "情景记忆召回命中 entry_id"),
                requiredCleanText(rs.getString("session_id"), "情景记忆召回命中 session_id"),
                normalizeBlank(rs.getString("session_title")),
                normalizeBlank(rs.getString("session_summary")),
                parseRequiredInstant(rs.getString("created_at"), "情景记忆召回命中.created_at"));
    }

    private String requiredText(java.sql.ResultSet rs, String column, String field) throws java.sql.SQLException {
        return requiredText(rs.getString(column), field);
    }

    private String requiredText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + "不能为空");
        }
        return value;
    }

    private String requiredCleanText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalStateException(field + "不能包含首尾空白: " + value);
        }
        return value;
    }

    private String requireUserOrAssistantRole(@Nullable String role) {
        String value = requiredCleanText(role, "情景记忆消息角色");
        if (!value.equals("user") && !value.equals("assistant")) {
            throw new IllegalStateException("情景记忆消息角色非法: " + value);
        }
        return value;
    }

    private String requireUserOrAssistantRoleForSave(@Nullable String role) {
        String value = requiredArgumentCleanText(role, "情景记忆消息角色");
        if (!value.equals("user") && !value.equals("assistant")) {
            throw new IllegalArgumentException("情景记忆消息角色非法: " + value);
        }
        return value;
    }

    private String requiredArgumentText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private String requiredArgumentCleanText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能包含首尾空白: " + value);
        }
        return value;
    }

    private Instant parseRequiredInstant(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + "不能为空");
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalStateException(field + "解析失败: " + value, e);
        }
    }

    private int estimateTokenCount(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
