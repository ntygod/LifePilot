package com.lifepilot.memory.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 记忆事件记录器 — 将 {@link MemoryEvent} 写入数据库表 memory_events。
 *
 * <p>该组件是轻量可选依赖：如果表不存在，记录器会降级为仅输出日志。</p>
 */
public class MemoryEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemoryEventRecorder.class);

    private final JdbcTemplate jdbcTemplate;

    public MemoryEventRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(MemoryEvent event) {
        try {
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
        } catch (Exception e) {
            log.debug("记忆事件记录失败，降级为日志: type={}, layer={}, action={}, error={}",
                    event.eventType(), event.layer(), event.action(), e.getMessage());
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            // 运行时通过 Spring 提供的 Jackson ObjectMapper 会更好，这里使用简单实现兜底
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : metadata.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":\"")
                        .append(String.valueOf(entry.getValue()).replace("\"", "\\\""))
                        .append("\"");
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            log.debug("记忆事件 metadata JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }
}

