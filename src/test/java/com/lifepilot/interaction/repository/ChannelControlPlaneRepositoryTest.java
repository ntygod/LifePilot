package com.lifepilot.interaction.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceEvent;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道控制面仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
@SpringBootTest(classes = ChannelControlPlaneRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class ChannelControlPlaneRepositoryTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
    })
    @Import(com.lifepilot.config.DataSourceConfig.class)
    static class TestApp {
    }

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-channel-control-plane-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-channel-control-plane-vec-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ChannelPluginRepository channelPluginRepository;
    private ChannelInstanceRepository channelInstanceRepository;
    private ChannelInstanceEventRepository channelInstanceEventRepository;

    @BeforeEach
    void setUp() {
        channelPluginRepository = new ChannelPluginRepository(jdbcTemplate, objectMapper);
        channelInstanceRepository = new ChannelInstanceRepository(jdbcTemplate, objectMapper);
        channelInstanceEventRepository = new ChannelInstanceEventRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void channelPluginDescriptor_可持久化并覆盖更新() {
        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "plugin.test",
                "测试插件",
                "1.0.0",
                "zhiwei-test",
                "test-platform",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of(
                        "type", "object",
                        "properties", Map.of("baseUrl", Map.of("type", "string"))
                ),
                List.of("token"),
                Map.of("title", "接入说明"),
                null
        );

        channelPluginRepository.save(descriptor);
        ChannelPluginDescriptor updated = new ChannelPluginDescriptor(
                descriptor.pluginId(),
                descriptor.name(),
                "1.1.0",
                descriptor.vendor(),
                descriptor.platform(),
                descriptor.connectorMode(),
                Map.of("protocol", "http", "mode", "updated"),
                descriptor.capabilities(),
                descriptor.configSchema(),
                descriptor.secretFields(),
                descriptor.setupGuide(),
                descriptor.resources()
        );
        channelPluginRepository.save(updated);

        ChannelPluginDescriptor reloaded = channelPluginRepository.findByPluginId("plugin.test").orElseThrow();

        assertThat(reloaded.version()).isEqualTo("1.1.0");
        assertThat(reloaded.connectorSpec()).containsEntry("mode", "updated");
        assertThat(channelPluginRepository.findAll()).extracting(ChannelPluginDescriptor::pluginId)
                .contains("plugin.test");
    }

    @Test
    void channelInstance_可读写配置密钥并级联删除() {
        channelPluginRepository.save(new ChannelPluginDescriptor(
                "plugin.instance",
                "实例插件",
                "1.0.0",
                "zhiwei-test",
                "instance-platform",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of("type", "object", "properties", Map.of()),
                List.of("appSecret"),
                Map.of("title", "实例接入"),
                null
        ));

        Instant now = Instant.parse("2026-03-29T12:00:00Z");
        ChannelInstance instance = new ChannelInstance(
                "instance.test",
                "plugin.instance",
                "instance-platform",
                "测试实例",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("baseUrl", "http://localhost:9001", "appId", "app-1"),
                Map.of("appSecret", "secret-1", "runtimeToken", "token-1"),
                Map.of("workspaceId", "workspace-1"),
                now,
                null,
                now,
                now
        );

        channelInstanceRepository.save(instance);
        ChannelInstance reloaded = channelInstanceRepository.findById("instance.test").orElseThrow();

        assertThat(reloaded.platform()).isEqualTo("instance-platform");
        assertThat(reloaded.status()).isEqualTo(ChannelInstanceStatus.RUNNING);
        assertThat(reloaded.config()).containsEntry("appId", "app-1");
        assertThat(reloaded.secretConfig()).containsEntry("appSecret", "secret-1");
        assertThat(reloaded.routingPolicy()).containsEntry("workspaceId", "workspace-1");

        boolean deleted = channelInstanceRepository.deleteById("instance.test");

        assertThat(deleted).isTrue();
        assertThat(channelInstanceRepository.findById("instance.test")).isEmpty();
        Integer secretCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM channel_instance_secrets WHERE instance_id = ?",
                Integer.class,
                "instance.test"
        );
        assertThat(secretCount).isZero();
    }

    @Test
    void channelInstanceEvent_可按实例读取最近事件() {
        channelPluginRepository.save(new ChannelPluginDescriptor(
                "plugin.event",
                "事件插件",
                "1.0.0",
                "zhiwei-test",
                "event-platform",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive"),
                Map.of("type", "object", "properties", Map.of()),
                List.of(),
                Map.of("title", "事件接入"),
                null
        ));
        Instant now = Instant.parse("2026-03-29T12:30:00Z");
        channelInstanceRepository.save(new ChannelInstance(
                "instance.event",
                "plugin.event",
                "event-platform",
                "事件实例",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of(),
                Map.of("runtimeToken", "token-event"),
                null,
                now,
                null,
                now,
                now
        ));

        channelInstanceEventRepository.save(
                "instance.event",
                "INSTANCE_STARTED",
                "渠道实例已启动",
                Map.of("status", "RUNNING")
        );
        channelInstanceEventRepository.save(
                "instance.event",
                "INGRESS_EVENT_PROCESSED",
                "connector 入站事件处理完成",
                Map.of("eventId", "evt-1", "deliveryMode", "ASYNC_PUSH")
        );

        List<ChannelInstanceEvent> events = channelInstanceEventRepository.findRecentByInstanceId("instance.event", 10);

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().eventType()).isEqualTo("INGRESS_EVENT_PROCESSED");
        assertThat(events.getFirst().payload()).containsEntry("eventId", "evt-1");
        assertThat(events.get(1).eventType()).isEqualTo("INSTANCE_STARTED");
    }
}
