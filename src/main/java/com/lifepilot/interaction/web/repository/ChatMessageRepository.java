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
