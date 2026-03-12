package com.lifepilot.memory.episodic;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L2 情景记忆服务 — 管理对话记录的持久化存储与检索。
 *
 * <p>使用 JdbcTemplate 执行所有数据库操作，支持 FTS5 全文搜索。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemory.class);

    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;

    /** 记忆写入回调 — 通知检索引擎数据已变更（重置 knownEmpty 短路标记）。 */
    @Nullable
    private Runnable writeCallback;

    public EpisodicMemory(JdbcTemplate jdbcTemplate, MemoryProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 设置记忆写入回调，用于在对话记录写入后通知检索引擎重置空数据标记。
     *
     * @param writeCallback 写入回调
     */
    public void setWriteCallback(@Nullable Runnable writeCallback) {
        this.writeCallback = writeCallback;
    }

    /**
     * 转义 FTS5 查询字符串，防止特殊字符（冒号、引号等）被解析为 FTS5 语法。
     *
     * <p>转义策略：用双引号包裹整个查询，内部双引号转义为两个双引号。</p>
     *
     * @param query 原始查询字符串
     * @return 转义后的 FTS5 安全查询字符串
     */
    static String escapeFts5Query(String query) {
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    /**
     * 保存对话记录（事务内写入 conversations + messages）。
     *
     * @param record 对话记录
     */
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

        // 通知检索引擎数据已变更，重置 knownEmpty 短路标记
        if (writeCallback != null) {
            try {
                writeCallback.run();
            } catch (Exception e) {
                log.warn("情景记忆: writeCallback 执行失败, error={}", e.getMessage());
            }
        }
        log.debug("保存对话记录: id={}, 消息数={}", record.id(), record.messageCount());
    }

    /**
     * 获取最近的对话记录（按 created_at 降序）。
     *
     * @param limit 最大返回数量
     * @return 对话记录列表
     */
    public List<ConversationRecord> getRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at " +
                        "FROM conversations ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        List.of(),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                limit);

        // 填充每个对话的消息列表
        return conversations.stream()
                .map(c -> new ConversationRecord(c.id(), c.sessionId(), c.goal(), c.summary(),
                        loadMessages(c.id()), c.createdAt(), c.updatedAt()))
                .toList();
    }

    /**
     * 按时间窗口获取最近的对话记录（按 created_at 降序）。
     *
     * <p>用于巩固管线和分析“最近 N 天/小时”的对话片段。</p>
     *
     * @param duration 回溯时间窗口（如 Duration.ofDays(7)）
     * @return 对话记录列表
     */
    public List<ConversationRecord> getRecent(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return List.of();
        }
        String since = Instant.now().minus(duration).toString();
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at " +
                        "FROM conversations WHERE created_at >= ? ORDER BY created_at DESC",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        List.of(),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                since);

        return conversations.stream()
                .map(c -> new ConversationRecord(c.id(), c.sessionId(), c.goal(), c.summary(),
                        loadMessages(c.id()), c.createdAt(), c.updatedAt()))
                .toList();
    }

    /**
     * FTS5 全文搜索对话记录。
     *
     * @param query 搜索关键词
     * @return 匹配的对话记录列表（按 BM25 排序）
     */
    public List<ConversationRecord> search(String query) {
        try {
            // 通过 FTS5 搜索消息，获取关联的对话 ID
            var conversationIds = jdbcTemplate.queryForList(
                    "SELECT DISTINCT m.conversation_id FROM messages m JOIN messages_fts fts ON m.rowid = fts.rowid WHERE messages_fts MATCH ? ORDER BY bm25(messages_fts)",
                    String.class, escapeFts5Query(query));

            if (conversationIds.isEmpty()) {
                return List.of();
            }

            return conversationIds.stream()
                    .map(this::getById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        } catch (Exception e) {
            log.warn("FTS5 搜索失败: query={}, 错误={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据对话 ID 获取完整对话记录。
     *
     * @param conversationId 对话 ID
     * @return 对话记录，不存在时返回 Optional.empty()
     */
    public Optional<ConversationRecord> getById(String conversationId) {
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at FROM conversations WHERE id = ?",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        loadMessages(conversationId),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                conversationId);

        return conversations.isEmpty() ? Optional.empty() : Optional.of(conversations.getFirst());
    }

    /**
     * 根据会话 ID 获取所有消息记录。
     *
     * @param sessionId 会话 ID
     * @return 消息记录列表
     */
    public List<MessageRecord> getMessagesBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        var conversationIds = jdbcTemplate.queryForList(
                "SELECT id FROM conversations WHERE session_id = ? ORDER BY created_at",
                String.class, sessionId);
        return conversationIds.stream()
                .flatMap(cid -> loadMessages(cid).stream())
                .toList();
    }

    /**
     * 语义检索其他会话中的相关消息，排除指定 sessionId。
     *
     * <p>基于 FTS5 全文检索，JOIN conversations 表过滤 session_id，
     * 按 BM25 相关度排序返回最相关的消息记录。</p>
     *
     * @param query            查询文本
     * @param excludeSessionId 排除的会话 ID
     * @param limit            最大返回数
     * @return 相关消息列表，异常时返回空列表
     */
    public List<MessageRecord> searchExcludingSession(String query, String excludeSessionId, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            float minBm25 = properties.getRetrieval().getMinCrossSessionBm25Score();
            return jdbcTemplate.query(
                    "SELECT m.id, m.conversation_id, m.role, m.content, m.compressed_content, " +
                            "m.compression_level, m.is_pinned, m.tool_call_json, m.token_count, m.created_at " +
                            "FROM messages m " +
                            "JOIN messages_fts fts ON m.rowid = fts.rowid " +
                            "JOIN conversations c ON m.conversation_id = c.id " +
                            "WHERE messages_fts MATCH ? AND c.session_id != ? " +
                            "AND -bm25(messages_fts) > ? " +
                            "ORDER BY bm25(messages_fts) " +
                            "LIMIT ?",
                    (rs, rowNum) -> new MessageRecord(
                            rs.getString("id"),
                            rs.getString("conversation_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("compressed_content"),
                            CompressionLevel.fromLevel(rs.getInt("compression_level")),
                            rs.getInt("is_pinned") == 1,
                            rs.getString("tool_call_json"),
                            rs.getInt("token_count"),
                            Instant.parse(rs.getString("created_at"))),
                    escapeFts5Query(query), excludeSessionId, minBm25, limit);
        } catch (Exception e) {
            log.warn("跨会话排除检索失败: query={}, excludeSessionId={}, error={}",
                    query, excludeSessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按意图（goal 模糊匹配）获取最近的对话记录。
     *
     * <p>例如 intentType="待办" 用于检索与待办事项相关的历史对话。</p>
     *
     * @param intentType 意图关键词
     * @param limit      最大返回数量
     * @return 匹配的对话记录列表
     */
    public List<ConversationRecord> getByIntent(String intentType, int limit) {
        if (intentType == null || intentType.isBlank() || limit <= 0) {
            return List.of();
        }
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at " +
                        "FROM conversations WHERE goal LIKE ? ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        List.of(),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                "%" + intentType + "%", limit);

        return conversations.stream()
                .map(c -> new ConversationRecord(c.id(), c.sessionId(), c.goal(), c.summary(),
                        loadMessages(c.id()), c.createdAt(), c.updatedAt()))
                .toList();
    }

    /**
     * 对指定对话执行渐进式压缩。
     *
     * <p>仅更新未 pinned 且当前压缩层级低于目标层级的消息。</p>
     *
     * @param conversationId 对话 ID
     * @param targetLevel    目标压缩层级
     * @param compressedTexts 消息 ID → 压缩后内容映射
     */
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
        log.info("情景记忆压缩: conversationId={}, level={}, count={}",
                conversationId, targetLevel, compressedTexts.size());
    }

    /**
     * 加载指定对话的所有消息。
     */
    private List<MessageRecord> loadMessages(String conversationId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, compressed_content, compression_level, is_pinned, tool_call_json, token_count, created_at " +
                        "FROM messages WHERE conversation_id = ? ORDER BY created_at",
                (rs, rowNum) -> new MessageRecord(
                        rs.getString("id"),
                        rs.getString("conversation_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("compressed_content"),
                        CompressionLevel.fromLevel(rs.getInt("compression_level")),
                        rs.getInt("is_pinned") == 1,
                        rs.getString("tool_call_json"),
                        rs.getInt("token_count"),
                        Instant.parse(rs.getString("created_at"))),
                conversationId);
    }
}
