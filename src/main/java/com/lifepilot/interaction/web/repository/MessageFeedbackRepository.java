package com.lifepilot.interaction.web.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 助手回复反馈数据访问层。
 *
 * <p>反馈对象是助手 transcript 条目。{@code message_feedback.entry_id}
 * 等价于 assistant transcript entry ID。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@Repository
public class MessageFeedbackRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageFeedbackRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MessageFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    @Autowired
    public MessageFeedbackRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存针对助手 transcript 条目的反馈。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @param sessionId 会话 ID
     * @param type 反馈类型，'like' 或 'dislike'
     * @param feedback 反馈文本，可为空
     */
    public void saveForEntry(String assistantEntryId, String sessionId, String type, String feedback) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO message_feedback (id, entry_id, session_id, type, feedback, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, assistantEntryId, sessionId, type, feedback, createdAt);

        log.debug("保存消息反馈: assistantEntryId={}, type={}", assistantEntryId, type);
    }

    /**
     * 检查助手 transcript 条目是否存在。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @return 如条目存在则返回 true
     */
    public boolean entryExists(String assistantEntryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_transcript_entries WHERE id = ?",
                Integer.class,
                assistantEntryId
        );
        return count != null && count > 0;
    }

    /**
     * 根据助手 transcript 条目 ID 获取所属会话。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @return 会话 ID，如条目不存在则返回 null
     */
    public String getSessionIdByEntryId(String assistantEntryId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT session_id FROM session_transcript_entries WHERE id = ?",
                    String.class,
                    assistantEntryId
            );
        } catch (Exception e) {
            log.warn("获取条目所属会话失败: assistantEntryId={}, error={}", assistantEntryId, e.getMessage());
            return null;
        }
    }

    /**
     * 根据助手 transcript 条目 ID 获取文本内容。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @return 文本内容，如不存在则返回 null
     */
    public String getEntryContentById(String assistantEntryId) {
        try {
            String payloadJson = jdbcTemplate.queryForObject(
                    "SELECT payload_json FROM session_transcript_entries WHERE id = ?",
                    String.class,
                    assistantEntryId
            );
            if (payloadJson == null || payloadJson.isBlank()) {
                return null;
            }
            var payload = objectMapper.readValue(
                    payloadJson,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            Object content = payload.get("content");
            return content != null ? content.toString() : null;
        } catch (Exception e) {
            log.warn("获取条目内容失败: assistantEntryId={}, error={}", assistantEntryId, e.getMessage());
            return null;
        }
    }

    /**
     * 查询指定助手条目的全部反馈记录。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     * @return 反馈记录列表，按创建时间升序
     */
    public List<Map<String, Object>> findByEntryId(String assistantEntryId) {
        return jdbcTemplate.queryForList(
                "SELECT type, feedback, created_at FROM message_feedback WHERE entry_id = ? ORDER BY created_at ASC",
                assistantEntryId
        );
    }
}
