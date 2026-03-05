package com.lifepilot.interaction.web.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 消息反馈数据访问层。
 *
 * <p>基于 JdbcTemplate 操作 message_feedback 表，提供反馈的保存操作。
 *
 * @author zsg
 * @since 2026-02-28
 */
@Repository
public class MessageFeedbackRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageFeedbackRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public MessageFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存消息反馈。
     *
     * @param messageId 消息 ID
     * @param sessionId 会话 ID
     * @param type      反馈类型（'like' 或 'dislike'）
     * @param feedback  点踩时的反馈内容（可为 null）
     */
    public void save(String messageId, String sessionId, String type, String feedback) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO message_feedback (id, message_id, session_id, type, feedback, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, messageId, sessionId, type, feedback, createdAt);

        log.debug("保存消息反馈: messageId={}, type={}", messageId, type);
    }

    /**
     * 检查消息是否存在。
     *
     * @param messageId 消息 ID
     * @return 如果消息存在返回 true，否则返回 false
     */
    public boolean messageExists(String messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE id = ?",
                Integer.class, messageId);
        return count != null && count > 0;
    }

    /**
     * 根据消息 ID 获取会话 ID。
     *
     * @param messageId 消息 ID
     * @return 会话 ID，如果消息不存在返回 null
     */
    public String getSessionIdByMessageId(String messageId) {
        try {
            // 对话历史与记忆系统解耦：通过 chat_messages 直接获取 session_id
            return jdbcTemplate.queryForObject(
                    "SELECT session_id FROM chat_messages WHERE id = ?",
                    String.class, messageId);
        } catch (Exception e) {
            log.warn("获取消息的会话 ID 失败: messageId={}, error={}", messageId, e.getMessage());
            return null;
        }
    }
}
