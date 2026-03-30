package com.lifepilot.interaction.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 渠道插件描述仓储。
 *
 * <p>负责持久化已安装或内建渠道插件的 descriptor，供控制面在重启后恢复。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelPluginRepository {

    private static final Logger log = LoggerFactory.getLogger(ChannelPluginRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ChannelPluginDescriptor> rowMapper = (rs, rowNum) ->
            deserialize(rs.getString("descriptor_json"));

    public ChannelPluginRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(ChannelPluginDescriptor descriptor) {
        String descriptorJson = serialize(descriptor);
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO channel_plugins
                    (plugin_id, name, version, vendor, platform, connector_mode, descriptor_json, installed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(plugin_id) DO UPDATE SET
                    name = excluded.name,
                    version = excluded.version,
                    vendor = excluded.vendor,
                    platform = excluded.platform,
                    connector_mode = excluded.connector_mode,
                    descriptor_json = excluded.descriptor_json,
                    updated_at = excluded.updated_at
                """,
                descriptor.pluginId(),
                descriptor.name(),
                descriptor.version(),
                descriptor.vendor(),
                descriptor.platform(),
                descriptor.connectorMode().name(),
                descriptorJson,
                now,
                now
        );
        log.debug("渠道插件描述已保存: pluginId={}, platform={}",
                descriptor.pluginId(), descriptor.platform());
    }

    public Optional<ChannelPluginDescriptor> findByPluginId(String pluginId) {
        List<ChannelPluginDescriptor> results = jdbcTemplate.query(
                "SELECT * FROM channel_plugins WHERE plugin_id = ?",
                rowMapper,
                pluginId
        );
        return results.stream().findFirst();
    }

    public List<ChannelPluginDescriptor> findAll() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM channel_plugins ORDER BY installed_at ASC",
                rowMapper
        ));
    }

    public void deleteByPluginId(String pluginId) {
        jdbcTemplate.update("DELETE FROM channel_plugins WHERE plugin_id = ?", pluginId);
        log.debug("渠道插件描述已删除: pluginId={}", pluginId);
    }

    private String serialize(ChannelPluginDescriptor descriptor) {
        try {
            return objectMapper.writeValueAsString(descriptor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("渠道插件描述序列化失败: " + descriptor.pluginId(), e);
        }
    }

    private ChannelPluginDescriptor deserialize(String json) {
        try {
            return objectMapper.readValue(json, ChannelPluginDescriptor.class);
        } catch (IOException e) {
            throw new IllegalStateException("渠道插件描述反序列化失败", e);
        }
    }
}
