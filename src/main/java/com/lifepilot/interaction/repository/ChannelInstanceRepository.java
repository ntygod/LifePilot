package com.lifepilot.interaction.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道实例仓储。
 *
 * <p>实例主记录与密钥配置分表存储，统一通过 left join 读回完整实例。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelInstanceRepository {

    private static final Logger log = LoggerFactory.getLogger(ChannelInstanceRepository.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String BASE_QUERY = """
            SELECT ci.instance_id, ci.plugin_id, ci.platform, ci.display_name, ci.enabled, ci.status,
                   ci.config_json, ci.routing_policy_json, ci.last_heartbeat_at, ci.last_error,
                   ci.created_at, ci.updated_at, cs.secret_json
            FROM channel_instances ci
            LEFT JOIN channel_instance_secrets cs ON cs.instance_id = ci.instance_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ChannelInstance> rowMapper = (rs, rowNum) -> new ChannelInstance(
            rs.getString("instance_id"),
            rs.getString("plugin_id"),
            rs.getString("platform"),
            rs.getString("display_name"),
            rs.getBoolean("enabled"),
            ChannelInstanceStatus.valueOf(rs.getString("status")),
            deserializeMap(rs.getString("config_json"), Map.of()),
            deserializeMap(rs.getString("secret_json"), null),
            deserializeMap(rs.getString("routing_policy_json"), null),
            parseInstant(rs.getString("last_heartbeat_at")),
            rs.getString("last_error"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public ChannelInstanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ChannelInstance save(ChannelInstance instance) {
        jdbcTemplate.update("""
                INSERT INTO channel_instances
                    (instance_id, plugin_id, platform, display_name, enabled, status,
                     config_json, routing_policy_json, last_heartbeat_at, last_error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(instance_id) DO UPDATE SET
                    plugin_id = excluded.plugin_id,
                    platform = excluded.platform,
                    display_name = excluded.display_name,
                    enabled = excluded.enabled,
                    status = excluded.status,
                    config_json = excluded.config_json,
                    routing_policy_json = excluded.routing_policy_json,
                    last_heartbeat_at = excluded.last_heartbeat_at,
                    last_error = excluded.last_error,
                    updated_at = excluded.updated_at
                """,
                instance.instanceId(),
                instance.pluginId(),
                instance.platform(),
                instance.displayName(),
                instance.enabled(),
                instance.status().name(),
                serializeMap(instance.config()),
                serializeMap(instance.routingPolicy()),
                formatInstant(instance.lastHeartbeatAt()),
                instance.lastError(),
                instance.createdAt().toString(),
                instance.updatedAt().toString()
        );

        upsertSecret(instance.instanceId(), instance.secretConfig(), instance.updatedAt());
        log.debug("渠道实例已持久化: instanceId={}, pluginId={}, status={}",
                instance.instanceId(), instance.pluginId(), instance.status());
        return instance;
    }

    public Optional<ChannelInstance> findById(String instanceId) {
        List<ChannelInstance> results = jdbcTemplate.query(
                BASE_QUERY + " WHERE ci.instance_id = ?",
                rowMapper,
                instanceId
        );
        return results.stream().findFirst();
    }

    public List<ChannelInstance> findAll() {
        return List.copyOf(jdbcTemplate.query(
                BASE_QUERY + " ORDER BY ci.created_at ASC",
                rowMapper
        ));
    }

    public List<ChannelInstance> findByPluginId(String pluginId) {
        return List.copyOf(jdbcTemplate.query(
                BASE_QUERY + " WHERE ci.plugin_id = ? ORDER BY ci.created_at ASC",
                rowMapper,
                pluginId
        ));
    }

    public boolean deleteById(String instanceId) {
        int affected = jdbcTemplate.update("DELETE FROM channel_instances WHERE instance_id = ?", instanceId);
        if (affected > 0) {
            log.debug("渠道实例已删除: instanceId={}", instanceId);
            return true;
        }
        return false;
    }

    private void upsertSecret(String instanceId,
                              @Nullable Map<String, Object> secretConfig,
                              Instant updatedAt) {
        if (secretConfig == null || secretConfig.isEmpty()) {
            jdbcTemplate.update("DELETE FROM channel_instance_secrets WHERE instance_id = ?", instanceId);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO channel_instance_secrets (instance_id, secret_json, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(instance_id) DO UPDATE SET
                    secret_json = excluded.secret_json,
                    updated_at = excluded.updated_at
                """,
                instanceId,
                serializeMap(secretConfig),
                updatedAt.toString()
        );
    }

    private String serializeMap(@Nullable Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("渠道配置序列化失败", e);
        }
    }

    @Nullable
    private Map<String, Object> deserializeMap(@Nullable String json,
                                               @Nullable Map<String, Object> defaultValue) {
        if (json == null || json.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("渠道配置反序列化失败", e);
        }
    }

    @Nullable
    private Instant parseInstant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Instant.parse(raw);
    }

    @Nullable
    private String formatInstant(@Nullable Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
