package com.lifepilot.interaction.web.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(ChatSessionRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存会话（upsert 语义）。
     *
     * @param session 会话实例
     */
    public void save(ChatSession session) {
        jdbcTemplate.update("""
                INSERT INTO chat_sessions (
                    id, title, summary, message_count, is_pinned, archived,
                    last_message_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    summary = excluded.summary,
                    message_count = excluded.message_count,
                    is_pinned = excluded.is_pinned,
                    archived = excluded.archived,
                    last_message_at = excluded.last_message_at,
                    updated_at = excluded.updated_at
                """,
                session.id(),
                session.title(),
                session.summary(),
                session.messageCount(),
                session.isPinned() ? 1 : 0,
                session.archived() ? 1 : 0,
                session.lastMessageAt() != null ? session.lastMessageAt().toString() : null,
                session.createdAt().toString(),
                session.updatedAt().toString());
    }

        /**
     * 追加一条消息带来的会话元数据变化：消息计数、最后消息时间、摘要预览。
     *
     * <p>摘要预览用于 Sidebar “最后一条消息预览”。</p>
     */
    public void appendMessageMeta(String sessionId, Instant messageAt, String lastMessagePreview) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String at = (messageAt != null ? messageAt : Instant.now()).toString();
        jdbcTemplate.update("""
                        UPDATE chat_sessions SET
                            message_count = message_count + 1,
                            last_message_at = ?,
                            summary = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                at,
                lastMessagePreview,
                at,
                sessionId);
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
     * 根据条件查询会话列表。
     *
     * @param q        关键词搜索（名称或摘要）
     * @param pinned   置顶状态过滤（null 表示不过滤）
     * @param archived 归档状态过滤（null 表示不过滤）
     * @param timeRange 时间范围（7d/30d，null 表示不过滤）
     * @param sortBy   排序字段（updatedAt/lastMessageAt）
     * @param order    排序方向（asc/desc）
     * @return 会话列表
     */
    @SuppressWarnings("null")
    public List<ChatSession> findByConditions(String q, Boolean pinned, Boolean archived,
                                               String timeRange, String sortBy, String order) {
        StringBuilder sql = new StringBuilder("SELECT * FROM chat_sessions WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();

        // 关键词搜索
        if (q != null && !q.isBlank()) {
            sql.append(" AND (title LIKE ? OR summary LIKE ?)");
            String searchPattern = "%" + q + "%";
            params.add(searchPattern);
            params.add(searchPattern);
        }

        // 置顶过滤
        if (pinned != null) {
            sql.append(" AND is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }

        // 归档过滤
        if (archived != null) {
            sql.append(" AND archived = ?");
            params.add(archived ? 1 : 0);
        }

        // 时间范围过滤
        if (timeRange != null && !timeRange.isBlank()) {
            Instant cutoff;
            if ("7d".equals(timeRange)) {
                cutoff = Instant.now().minusSeconds(7 * 24 * 60 * 60);
            } else if ("30d".equals(timeRange)) {
                cutoff = Instant.now().minusSeconds(30 * 24 * 60 * 60);
            } else {
                cutoff = null;
            }
            if (cutoff != null) {
                sql.append(" AND updated_at >= ?");
                params.add(cutoff.toString());
            }
        }

        // 排序
        String sortField = "updatedAt".equals(sortBy) ? "updated_at" : "last_message_at";
        String sortOrder = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
        sql.append(" ORDER BY is_pinned DESC, ").append(sortField).append(" ").append(sortOrder);
        if ("last_message_at".equals(sortField)) {
            sql.append(" NULLS LAST");
        }
        sql.append(", updated_at DESC");

        Object[] args = params.toArray();
        return jdbcTemplate.query(sql.toString(), this::mapRow, args);
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
     * 更新会话归档状态。
     *
     * @param id       会话 ID
     * @param archived 是否归档
     */
    public void updateArchived(String id, boolean archived) {
        jdbcTemplate.update(
                "UPDATE chat_sessions SET archived = ?, updated_at = ? WHERE id = ?",
                archived ? 1 : 0, Instant.now().toString(), id);
    }

    /**
     * 更新会话的多个字段。
     *
     * @param id       会话 ID
     * @param title    新标题（null 表示不更新）
     * @param pinned   置顶状态（null 表示不更新）
     * @param archived 归档状态（null 表示不更新）
     */
    @SuppressWarnings("null")
    public void updateFields(String id, String title, Boolean pinned, Boolean archived) {
        StringBuilder sql = new StringBuilder("UPDATE chat_sessions SET updated_at = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(Instant.now().toString());

        if (title != null) {
            sql.append(", title = ?");
            params.add(title);
        }
        if (pinned != null) {
            sql.append(", is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }
        if (archived != null) {
            sql.append(", archived = ?");
            params.add(archived ? 1 : 0);
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        Object[] args = params.toArray();
        jdbcTemplate.update(sql.toString(), args);
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
     * 批量更新会话字段。
     *
     * @param ids    会话 ID 列表
     * @param title  新标题（null 表示不更新）
     * @param pinned 置顶状态（null 表示不更新）
     * @param archived 归档状态（null 表示不更新）
     * @return 更新的记录数
     */
    @SuppressWarnings("null")
    public int batchUpdateFields(List<String> ids, String title, Boolean pinned, Boolean archived) {
        if (ids.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("UPDATE chat_sessions SET updated_at = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(Instant.now().toString());

        if (title != null) {
            sql.append(", title = ?");
            params.add(title);
        }
        if (pinned != null) {
            sql.append(", is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }
        if (archived != null) {
            sql.append(", archived = ?");
            params.add(archived ? 1 : 0);
        }

        // 构建 IN 子句
        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        sql.append(" WHERE id IN (").append(placeholders).append(")");
        params.addAll(ids);

        Object[] args = params.toArray();
        return jdbcTemplate.update(sql.toString(), args);
    }

    /**
     * 批量删除会话。
     *
     * @param ids 会话 ID 列表
     * @return 删除的记录数
     */
    public int batchDelete(List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }

        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "DELETE FROM chat_sessions WHERE id IN (" + placeholders + ")";
        return jdbcTemplate.update(sql, ids.toArray(new Object[0]));
    }

    /**
     * 更新会话配置。
     *
     * <p>合并新的配置到现有配置中，只更新提供的字段。
     *
     * @param id      会话 ID
     * @param config  配置 Map（包含要更新的字段）
     */
    public void updateConfig(String id, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            log.debug("配置为空，跳过更新: sessionId={}", id);
            return;
        }

        // 读取现有配置
        Map<String, Object> existingConfig = getConfig(id);
        
        // 合并配置
        Map<String, Object> mergedConfig = new HashMap<>(existingConfig);
        mergedConfig.putAll(config);
        mergedConfig.entrySet().removeIf(entry -> entry.getValue() == null);
        
        // 序列化为 JSON
        String configJson = serializeConfig(mergedConfig);
        
        // 更新数据库
        jdbcTemplate.update(
                "UPDATE chat_sessions SET config_json = ?, updated_at = ? WHERE id = ?",
                configJson, Instant.now().toString(), id);
        
        log.debug("会话配置已更新: sessionId={}, config={}", id, config);
    }

    /**
     * 获取会话配置。
     *
     * @param id 会话 ID
     * @return 配置 Map（如果不存在或为空则返回空 Map）
     */
    public Map<String, Object> getConfig(String id) {
        try {
            String configJson = jdbcTemplate.queryForObject(
                    "SELECT config_json FROM chat_sessions WHERE id = ?",
                    String.class, id);
            return deserializeConfig(configJson);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 会话不存在或配置字段为 null，返回空 Map
            return new HashMap<>();
        }
    }

    /**
     * 序列化配置 Map 为 JSON 字符串。
     */
    private String serializeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.warn("配置序列化失败，使用空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 反序列化 JSON 字符串为配置 Map。
     */
    private Map<String, Object> deserializeConfig(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : new HashMap<>();
        } catch (JsonProcessingException e) {
            log.warn("配置反序列化失败，返回空 Map: json={}, error={}", json, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 将 ResultSet 行映射为 ChatSession record。
     */
    private ChatSession mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String lastMessageAtStr = rs.getString("last_message_at");
        Instant lastMessageAt = lastMessageAtStr != null && !lastMessageAtStr.isBlank()
                ? Instant.parse(lastMessageAtStr)
                : null;

        // 兼容旧数据：如果 archived 字段不存在，默认为 false
        boolean archived = false;
        try {
            int archivedValue = rs.getInt("archived");
            archived = archivedValue == 1;
        } catch (java.sql.SQLException e) {
            // archived 字段可能不存在（旧数据库），使用默认值 false
        }

        return new ChatSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getInt("message_count"),
                rs.getInt("is_pinned") == 1,
                archived,
                lastMessageAt,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
