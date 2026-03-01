package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.ChatSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Web UI 会话数据访问层。
 *
 * <p>基于 JdbcTemplate 操作 chat_sessions 表，提供会话的 CRUD 操作。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Repository
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存会话（upsert 语义）。
     *
     * @param session 会话实例
     */
    public void save(ChatSession session) {
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (
                    id, title, summary, message_count, is_pinned,
                    last_message_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    summary = excluded.summary,
                    message_count = excluded.message_count,
                    is_pinned = excluded.is_pinned,
                    last_message_at = excluded.last_message_at,
                    updated_at = excluded.updated_at
                """,
                session.id(),
                session.title(),
                session.summary(),
                session.messageCount(),
                session.isPinned() ? 1 : 0,
                session.lastMessageAt() != null ? session.lastMessageAt().toString() : null,
                session.createdAt().toString(),
                session.updatedAt().toString());
    }

    /**
     * 根据 ID 查找会话。
     *
     * @param id 会话 ID
     * @return 会话 Optional
     */
    public Optional<ChatSession> findById(String id) {
        List<ChatSession> results = jdbcTemplate.query(
                "SELECT * FROM chat_sessions WHERE id = ?",
                this::mapRow, id);
        return results.stream().findFirst();
    }

    /**
     * 列出所有会话，按最后消息时间倒序。
     *
     * @return 会话列表
     */
    public List<ChatSession> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM chat_sessions ORDER BY is_pinned DESC, last_message_at DESC NULLS LAST, updated_at DESC",
                this::mapRow);
    }

    /**
     * 删除会话。
     *
     * @param id 会话 ID
     */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM chat_sessions WHERE id = ?", id);
    }

    /**
     * 更新会话标题。
     *
     * @param id    会话 ID
     * @param title 新标题
     */
    public void updateTitle(String id, String title) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?",
                title, Instant.now().toString(), id);
    }

    /**
     * 更新会话置顶状态。
     *
     * @param id       会话 ID
     * @param isPinned 是否置顶
     */
    public void updatePinned(String id, boolean isPinned) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET is_pinned = ?, updated_at = ? WHERE id = ?",
                isPinned ? 1 : 0, Instant.now().toString(), id);
    }

    /**
     * 增加会话消息计数并更新最后消息时间。
     *
     * @param id 会话 ID
     */
    public void incrementMessageCount(String id) {
        jdbcTemplate.update("""
                UPDATE chat_sessions SET
                    message_count = message_count + 1,
                    last_message_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                Instant.now().toString(), Instant.now().toString(), id);
    }

    /**
     * 清空会话消息（重置消息数和最后消息时间）。
     *
     * @param id 会话 ID
     */
    public void clearMessages(String id) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET message_count = 0, last_message_at = NULL, updated_at = ? WHERE id = ?",
                Instant.now().toString(), id);
    }

    /**
     * 将 ResultSet 行映射为 ChatSession record。
     */
    private ChatSession mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String lastMessageAtStr = rs.getString("last_message_at");
        Instant lastMessageAt = lastMessageAtStr != null && !lastMessageAtStr.isBlank()
                ? Instant.parse(lastMessageAtStr)
                : null;

        return new ChatSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getInt("message_count"),
                rs.getInt("is_pinned") == 1,
                lastMessageAt,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
