package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.config.ConnectorManagerProperties;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstalledExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectorManager} 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
class ConnectorManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void decorate_官方插件工作区存在_应标记可自动托管() throws Exception {
        Path pluginsRoot = tempDir.resolve("ZhiWei-plugins");
        Path connectorDir = pluginsRoot.resolve("connectors").resolve("feishu-connector");
        Files.createDirectories(connectorDir);
        Files.writeString(connectorDir.resolve("pom.xml"), "<project/>");

        ConnectorManagerProperties properties = new ConnectorManagerProperties();
        properties.setWorkspaceRoot(pluginsRoot.toString());
        ConnectorManager manager = new ConnectorManager(
                properties,
                new MockEnvironment().withProperty("server.port", "8080"),
                RestClient.create(),
                null
        );

        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "feishu",
                "飞书",
                "1.0.0",
                "zhiwei",
                "feishu",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of("type", "object", "properties", Map.of("appId", Map.of("type", "string"))),
                List.of(),
                null,
                null
        );

        ChannelPluginDescriptor decorated = manager.decorate(descriptor);

        assertThat(decorated.connectorSpec()).isNotNull();
        assertThat(decorated.connectorSpec()).containsKey("managed");
        @SuppressWarnings("unchecked")
        Map<String, Object> managed = (Map<String, Object>) decorated.connectorSpec().get("managed");
        assertThat(managed)
                .containsEntry("available", true)
                .containsEntry("workspace", "connectors/feishu-connector");
    }

    @Test
    void resolveBaseUrl_用户显式配置BaseUrl_应优先返回手动地址() {
        ConnectorManagerProperties properties = new ConnectorManagerProperties();
        ConnectorManager manager = new ConnectorManager(
                properties,
                new MockEnvironment().withProperty("server.port", "8080"),
                RestClient.create(),
                null
        );

        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "feishu",
                "飞书",
                "1.0.0",
                "zhiwei",
                "feishu",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of("type", "object", "properties", Map.of("baseUrl", Map.of("type", "string"))),
                List.of(),
                null,
                null
        );
        ChannelInstance instance = new ChannelInstance(
                "feishu.manual",
                "feishu",
                "feishu",
                "飞书手动地址",
                true,
                ChannelInstanceStatus.CREATED,
                Map.of("baseUrl", "http://127.0.0.1:29091/"),
                null,
                null,
                null,
                null,
                Instant.parse("2026-03-30T10:00:00Z"),
                Instant.parse("2026-03-30T10:00:00Z")
        );

        String baseUrl = manager.resolveBaseUrl(instance, descriptor, false);

        assertThat(baseUrl).isEqualTo("http://127.0.0.1:29091");
    }

    @Test
    void decorate_官方插件已安装Jar产物_应标记可自动托管() throws Exception {
        Path installRoot = tempDir.resolve("installed").resolve("feishu");
        Files.createDirectories(installRoot.resolve("dist"));
        Files.writeString(installRoot.resolve("dist").resolve("feishu-connector.jar"), "jar");

        InstalledExtensionRepository repository = mock(InstalledExtensionRepository.class);
        when(repository.findByPackageId("feishu")).thenReturn(java.util.Optional.of(new InstalledExtension(
                "id-1",
                "feishu",
                ExtensionType.CHANNEL,
                "飞书",
                "1.0.0",
                "https://index.example.com/index.json",
                "https://repo.example.com/feishu",
                installRoot.resolve("channel-plugin.json").toString(),
                installRoot.toString(),
                null,
                null,
                null,
                Instant.parse("2026-03-30T10:00:00Z"),
                Instant.parse("2026-03-30T10:00:00Z")
        )));

        ConnectorManager manager = new ConnectorManager(
                new ConnectorManagerProperties(),
                new MockEnvironment().withProperty("server.port", "8080"),
                RestClient.create(),
                repository
        );

        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "feishu",
                "飞书",
                "1.0.0",
                "zhiwei",
                "feishu",
                ConnectorMode.EXTERNAL,
                Map.of(
                        "protocol", "http",
                        "managed", Map.of(
                                "strategy", "installed-jar",
                                "artifactPath", "dist/feishu-connector.jar"
                        )
                ),
                List.of("receive", "send"),
                Map.of("type", "object", "properties", Map.of("appId", Map.of("type", "string"))),
                List.of(),
                null,
                null
        );

        ChannelPluginDescriptor decorated = manager.decorate(descriptor);

        assertThat(decorated.connectorSpec()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> managed = (Map<String, Object>) decorated.connectorSpec().get("managed");
        assertThat(managed)
                .containsEntry("available", true)
                .containsEntry("artifactPath", "dist/feishu-connector.jar")
                .containsEntry("resolution", "installed-artifact");
    }
}
