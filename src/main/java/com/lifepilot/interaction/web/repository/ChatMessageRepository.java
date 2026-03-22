package com.lifepilot.interaction.web.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.MessageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web 聊天消息持久化仓库。
 *
 * @author zsg
 * @since 2026-03-05
 */
@Repository
public class ChatMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatMessageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record ChatMessageRow(
            String id,
            String sessionId,
            String role,
            String content,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            @Nullable String a2uiComponentsJson,
            @Nullable String reactStepsJson,
            @Nullable CompletionMode completionMode,
            @Nullable String resumedFromTraceId,
            Instant createdAt
    ) {
    }

    public String insert(String sessionId,
                         String role,
                         String content,
                         @Nullable String reasoningSummary,
                         @Nullable String traceId,
                         Instant createdAt,
                         @Nullable String a2uiComponentsJson,
                         @Nullable String reactStepsJson,
                         @Nullable CompletionMode completionMode,
                         @Nullable String resumedFromTraceId) {
        String id = UUID.randomUUID().toString();
        Instant ts = createdAt != null ? createdAt : Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO chat_messages
                        (id, session_id, role, content, reasoning_summary, trace_id,
                         a2ui_components_json, react_steps_json, completion_mode, resumed_from_trace_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, sessionId, role, content,
                reasoningSummary, traceId, a2uiComponentsJson, reactStepsJson,
                completionMode != null ? completionMode.name() : null,
                resumedFromTraceId,
                ts.toString());
        log.debug("插入 chat_messages 记录: id={}, sessionId={}, role={}, hasA2ui={}, hasReactSteps={}, completionMode={}, resumed={}",
                id,
                sessionId,
                role,
                a2uiComponentsJson != null,
                reactStepsJson != null,
                completionMode,
                resumedFromTraceId != null && !resumedFromTraceId.isBlank());
        return id;
    }

    public List<MessageInfo> findMessageInfosBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, reasoning_summary, trace_id,
                               a2ui_components_json, react_steps_json, completion_mode, resumed_from_trace_id, created_at
                        FROM chat_messages
                        WHERE session_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> {
                    A2uiComponentTree tree = deserializeA2ui(rs.getString("a2ui_components_json"));
                    List<Map<String, Object>> reactSteps = deserializeReactSteps(rs.getString("react_steps_json"));
                    return new MessageInfo(
                            rs.getString("id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            tree != null ? tree.components() : null,
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("reasoning_summary"),
                            rs.getString("trace_id"),
                            null,
                            reactSteps,
                            parseCompletionMode(rs.getString("completion_mode")),
                            rs.getString("resumed_from_trace_id")
                    );
                },
                sessionId);
    }

    public List<ChatMessageRow> findRowsBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                        SELECT id, session_id, role, content, reasoning_summary, trace_id,
                               a2ui_components_json, react_steps_json, completion_mode, resumed_from_trace_id, created_at
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
                        rs.getString("a2ui_components_json"),
                        rs.getString("react_steps_json"),
                        parseCompletionMode(rs.getString("completion_mode")),
                        rs.getString("resumed_from_trace_id"),
                        Instant.parse(rs.getString("created_at"))
                ),
                sessionId);
    }

    public boolean messageExists(String messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE id = ?",
                Integer.class,
                messageId
        );
        return count != null && count > 0;
    }

    @Nullable
    public String findSessionIdByMessageId(String messageId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT session_id FROM chat_messages WHERE id = ?",
                    String.class,
                    messageId
            );
        } catch (Exception e) {
            return null;
        }
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
    }

    public int deleteById(String messageId) {
        return jdbcTemplate.update("DELETE FROM chat_messages WHERE id = ?", messageId);
    }

    /**
     * 更新消息的压缩摘要。
     *
     * @param messageId 消息 ID
     * @param compressedSummary 压缩摘要内容
     * @return 更新行数
     */
    public int updateCompressedSummary(String messageId, String compressedSummary) {
        return jdbcTemplate.update(
                "UPDATE chat_messages SET compressed_summary = ? WHERE id = ?",
                compressedSummary, messageId);
    }

    /**
     * 分页查询会话消息（按时间倒序，最新的在前）。
     *
     * @param sessionId 会话 ID
     * @param page 页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 消息列表
     */
    public List<MessageInfo> findMessageInfosPaged(String sessionId, int page, int pageSize) {
        int offset = (Math.max(1, page) - 1) * pageSize;
        return jdbcTemplate.query("""
                        SELECT id, role, content, reasoning_summary, trace_id,
                               a2ui_components_json, react_steps_json, compressed_summary,
                               completion_mode, resumed_from_trace_id, created_at
                        FROM chat_messages
                        WHERE session_id = ?
                        ORDER BY created_at DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, rowNum) -> {
                    A2uiComponentTree tree = deserializeA2ui(rs.getString("a2ui_components_json"));
                    var reactSteps = deserializeReactSteps(rs.getString("react_steps_json"));
                    return new MessageInfo(
                            rs.getString("id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            tree != null ? tree.components() : null,
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("reasoning_summary"),
                            rs.getString("trace_id"),
                            null,
                            reactSteps,
                            parseCompletionMode(rs.getString("completion_mode")),
                            rs.getString("resumed_from_trace_id")
                    );
                },
                sessionId, pageSize, offset);
    }

    /**
     * 查询会话消息总数。
     *
     * @param sessionId 会话 ID
     * @return 消息总数
     */
    public int countBySessionId(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
                Integer.class, sessionId);
        return count != null ? count : 0;
    }

    /**
     * 查询会话的摘要视图（优先返回压缩摘要，无摘要时返回原始内容截断）。
     *
     * @param sessionId 会话 ID
     * @param maxContentLength 无摘要时原始内容的最大截断长度
     * @return 消息摘要列表
     */
    public List<MessageInfo> findSummaryView(String sessionId, int maxContentLength) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, compressed_summary, reasoning_summary,
                               trace_id, a2ui_components_json, created_at
                        FROM chat_messages
                        WHERE session_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> {
                    String summary = rs.getString("compressed_summary");
                    String content = rs.getString("content");
                    // 优先使用压缩摘要，否则截断原始内容
                    String displayContent = summary != null ? summary
                            : (content != null && content.length() > maxContentLength
                                    ? content.substring(0, maxContentLength) + "..."
                                    : content);
                    A2uiComponentTree tree = deserializeA2ui(rs.getString("a2ui_components_json"));
                    return new MessageInfo(
                            rs.getString("id"),
                            rs.getString("role"),
                            displayContent,
                            tree != null ? tree.components() : null,
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("reasoning_summary"),
                            rs.getString("trace_id"),
                            null,
                            null,  // 摘要视图不加载 react_steps
                            null,  // completionMode
                            null   // resumedFromTraceId
                    );
                },
                sessionId);
    }

    @Nullable
    private CompletionMode parseCompletionMode(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return CompletionMode.valueOf(rawValue.strip());
        } catch (IllegalArgumentException e) {
            log.warn("completion_mode 反序列化失败: value={}", rawValue);
            return null;
        }
    }

    @Nullable
    private A2uiComponentTree deserializeA2ui(@Nullable String json) {
        return A2uiPayloadSupport.deserializeStoredTree(json, objectMapper);
    }

    /** 反序列化 react_steps_json 为 List<Map<String, Object>>。 */
    @Nullable
    private List<Map<String, Object>> deserializeReactSteps(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            log.warn("react_steps_json 反序列化失败: error={}", e.getMessage());
            return null;
        }
    }
}
