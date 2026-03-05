package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.MessageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Web UI 对话历史消息数据访问层。
 *
 * <p>对话历史与记忆系统物理分离：该仓库只操作 {@code chat_messages} 表。</p>
 *
 * @author zsg
 * @since 2026-03-03
 */
@Repository
public class ChatMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ChatMessageRow(
            String id,
            String sessionId,
            String role,
            String content,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            Instant createdAt
    ) {}

    /**
     * 插入一条历史消息。
     *
     * @return messageId
     */
    public String insert(String sessionId,
                         String role,
                         String content,
                         @Nullable String reasoningSummary,
                         @Nullable String traceId,
                         Instant createdAt) {
        String id = UUID.randomUUID().toString();
        Instant ts = createdAt != null ? createdAt : Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO chat_messages
                        (id, session_id, role, content, reasoning_summary, trace_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id, sessionId, role, content,
                reasoningSummary, traceId, ts.toString());
        log.debug("插入 chat_messages: id={}, sessionId={}, role={}", id, sessionId, role);
        return id;
    }

    /** 按时间顺序查询会话消息。 */
    public List<MessageInfo> findMessageInfosBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, reasoning_summary, created_at
                        FROM chat_messages
                        WHERE session_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> new MessageInfo(
                        rs.getString("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        null,
                        Instant.parse(rs.getString("created_at")),
                        rs.getString("reasoning_summary")
                ),
                sessionId);
    }

    /** 按时间顺序查询会话消息（带内部字段）。 */
    public List<ChatMessageRow> findRowsBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                        SELECT id, session_id, role, content, reasoning_summary, trace_id, created_at
                        FROM chat_messages
                        WHERE session_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> new ChatMessageRow(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("reasoning_summary"),
                        rs.getString("trace_id"),
                        Instant.parse(rs.getString("created_at"))
                ),
                sessionId);
    }

    public boolean messageExists(String messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE id = ?",
                Integer.class, messageId);
        return count != null && count > 0;
    }

    @Nullable
    public String findSessionIdByMessageId(String messageId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT session_id FROM chat_messages WHERE id = ?",
                    String.class, messageId);
        } catch (Exception e) {
            return null;
        }
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
    }

    /** 删除单条消息（用于分叉/回收等扩展场景）。 */
    public int deleteById(String messageId) {
        return jdbcTemplate.update("DELETE FROM chat_messages WHERE id = ?", messageId);
    }
}

