package com.lifepilot.memory.episodic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    public EpisodicMemory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

        log.debug("保存对话记录: id={}, 消息数={}", record.id(), record.messageCount());
    }

    /**
     * 获取最近的对话记录（按 created_at 降序）。
     *
     * @param limit 最大返回数量
     * @return 对话记录列表
     */
    public List<ConversationRecord> getRecent(int limit) {
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at FROM conversations ORDER BY created_at DESC LIMIT ?",
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
                    String.class, query);

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
     * 按意图模糊匹配对话记录。
     *
     * @param goal 意图关键词
     * @return 匹配的对话记录列表
     */
    public List<ConversationRecord> getByIntent(String goal) {
        var conversations = jdbcTemplate.query(
                "SELECT id, session_id, goal, summary, created_at, updated_at FROM conversations WHERE goal LIKE ? ORDER BY created_at DESC",
                (rs, rowNum) -> new ConversationRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("goal"),
                        rs.getString("summary"),
                        List.of(),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))),
                "%" + goal + "%");

        return conversations.stream()
                .map(c -> new ConversationRecord(c.id(), c.sessionId(), c.goal(), c.summary(),
                        loadMessages(c.id()), c.createdAt(), c.updatedAt()))
                .toList();
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
     * 压缩对话中的非 pinned 消息。
     *
     * @param conversationId 对话 ID
     * @param level 目标压缩层级
     */
    public void compress(String conversationId, CompressionLevel level) {
        int updated = jdbcTemplate.update(
                "UPDATE messages SET compression_level = ?, compressed_content = '[已压缩]' WHERE conversation_id = ? AND is_pinned = 0",
                level.level(), conversationId);

        log.debug("压缩对话消息: conversationId={}, 层级={}, 更新数={}", conversationId, level, updated);
    }

    /**
     * 加载指定对话的所有消息。
     */
    private List<MessageRecord> loadMessages(String conversationId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, compressed_content, compression_level, is_pinned, tool_call_json, token_count, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at",
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
