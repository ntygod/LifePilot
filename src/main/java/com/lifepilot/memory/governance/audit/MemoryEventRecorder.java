package com.lifepilot.memory.governance.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 记忆事件记录器 — 将 {@link MemoryEvent} 写入数据库表 memory_events。
 *
 * @author zsg
 * @since 2026-03-05
 */
public class MemoryEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemoryEventRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemoryEventRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(MemoryEvent event) {
        // 跳过测试会话的记忆事件（以 "test:" 前缀标识的会话 ID）
        if (event.sessionId() != null && event.sessionId().startsWith("test:")) {
            log.debug("跳过测试会话的记忆事件记录: sessionId={}, action={}", event.sessionId(), event.action());
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO memory_events
                (id, event_type, layer, session_id, conversation_id, entity_id, action, description, metadata_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                event.id(),
                event.eventType(),
                event.layer(),
                event.sessionId(),
                event.conversationId(),
                event.entityId(),
                event.action(),
                event.description(),
                toJson(event.metadata()),
                event.createdAt().toString()
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata != null ? metadata : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆事件 metadata JSON 序列化失败", e);
        }
    }
}
