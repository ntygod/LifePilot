package com.lifepilot.interaction.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelInstanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道实例事件仓储。
 *
 * <p>统一记录控制面与 connector runtime 的关键事件，供控制面最近事件面板查询。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelInstanceEventRepository {

    private static final Logger log = LoggerFactory.getLogger(ChannelInstanceEventRepository.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ChannelInstanceEvent> rowMapper = (rs, rowNum) -> new ChannelInstanceEvent(
            rs.getString("id"),
            rs.getString("instance_id"),
            rs.getString("event_type"),
            rs.getString("message"),
            deserializePayload(rs.getString("payload_json")),
            Instant.parse(rs.getString("created_at"))
    );

    public ChannelInstanceEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ChannelInstanceEvent save(String instanceId,
                                     String eventType,
                                     @Nullable String message,
                                     @Nullable Map<String, Object> payload) {
        ChannelInstanceEvent event = new ChannelInstanceEvent(
                UUID.randomUUID().toString(),
                instanceId,
                eventType,
                message,
                payload,
                Instant.now()
        );
        jdbcTemplate.update(
                """
                INSERT INTO channel_instance_events
                    (id, instance_id, event_type, message, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.instanceId(),
                event.eventType(),
                event.message(),
                serializePayload(event.payload()),
                event.createdAt().toString()
        );
        log.debug("渠道实例事件已记录: instanceId={}, eventType={}", instanceId, eventType);
        return event;
    }

    public List<ChannelInstanceEvent> findRecentByInstanceId(String instanceId, int limit) {
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT id, instance_id, event_type, message, payload_json, created_at
                FROM channel_instance_events
                WHERE instance_id = ?
                ORDER BY created_at DESC, rowid DESC
                LIMIT ?
                """,
                rowMapper,
                instanceId,
                limit
        ));
    }

    @Nullable
    private String serializePayload(@Nullable Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("渠道实例事件载荷序列化失败", e);
        }
    }

    @Nullable
    private Map<String, Object> deserializePayload(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("渠道实例事件载荷反序列化失败", e);
        }
    }
}
