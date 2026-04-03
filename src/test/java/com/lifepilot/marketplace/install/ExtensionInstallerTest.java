package com.lifepilot.marketplace.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ChannelPluginResources;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelPluginRepository;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstallResult;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.model.RiskLevel;
import com.lifepilot.marketplace.model.SecurityReport;
import com.lifepilot.marketplace.security.SecurityScanner;
import com.lifepilot.marketplace.version.VersionResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExtensionInstaller} 单元测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
@ExtendWith(MockitoExtension.class)
class ExtensionInstallerTest {

    @TempDir
    Path tempDir;

    @Mock
    private IndexManager indexManager;

    @Mock
    private SecurityScanner securityScanner;

    @Mock
    private InstalledExtensionRepository installedExtensionRepository;

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private ChannelPluginRepository channelPluginRepository;

    @Mock
    private ChannelInstanceService channelInstanceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void install_CHANNEL兼容版本不足_应直接失败且不触发下载安装注册() {
        MarketplaceProperties properties = marketplaceProperties("0.2.0");
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                objectMapper,
                tempDir,
                properties
        );
        ExtensionInstaller installer = createInstaller(properties, strategy);
        ExtensionPackage pkg = channelPackage("feishu", "1.0.0", "http://127.0.0.1:65535/feishu");

        when(indexManager.getPackage("feishu")).thenReturn(Optional.of(pkg));

        InstallResult result = installer.install("feishu", false);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Marketplace 兼容版本 0.2.0");
        assertThat(result.errorMessage()).contains("最低要求 1.0.0");
        verify(channelPluginRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(channelRegistry, never()).register(org.mockito.ArgumentMatchers.any());
        verify(installedExtensionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void install_CHANNEL兼容版本满足_应保存安装记录和资产清单() throws Exception {
        MarketplaceProperties properties = marketplaceProperties("1.0.0");
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                objectMapper,
                tempDir,
                properties
        );
        ExtensionInstaller installer = createInstaller(properties, strategy);
        ChannelPluginDescriptor descriptor = channelDescriptor("feishu");

        try (LocalChannelSource source = LocalChannelSource.start(descriptor, objectMapper)) {
            ExtensionPackage pkg = channelPackage("feishu", "1.0.0", source.baseUrl());
            when(indexManager.getPackage("feishu")).thenReturn(Optional.of(pkg));
            when(securityScanner.scan(org.mockito.ArgumentMatchers.eq(pkg), org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));

            InstallResult result = installer.install("feishu", false);

            assertThat(result.success()).isTrue();

            verify(channelPluginRepository).save(descriptor);
            verify(channelRegistry).register(descriptor);

            ArgumentCaptor<InstalledExtension> captor = ArgumentCaptor.forClass(InstalledExtension.class);
            verify(installedExtensionRepository).save(captor.capture());
            InstalledExtension installed = captor.getValue();
            assertThat(installed.packageId()).isEqualTo("feishu");
            assertThat(installed.type()).isEqualTo(ExtensionType.CHANNEL);
            assertThat(installed.installRootPath()).endsWith("feishu");
            assertThat(installed.assetsJson()).contains("docs/README.md");
            assertThat(installed.assetsJson()).contains("examples/webhook.json");
            assertThat(installed.assetsJson()).contains("assets/icon.svg");
            assertThat(installed.assetsJson()).contains("dist/feishu-connector.jar");
        }
    }

    private ExtensionInstaller createInstaller(MarketplaceProperties properties,
                                               ChannelInstallStrategy strategy) {
        return new ExtensionInstaller(
                indexManager,
                new VersionResolver(),
                securityScanner,
                installedExtensionRepository,
                properties,
                Map.of(ExtensionType.CHANNEL, strategy),
                RestClient.builder()
        );
    }

    private MarketplaceProperties marketplaceProperties(String compatibilityVersion) {
        MarketplaceProperties properties = new MarketplaceProperties();
        properties.setCompatibilityVersion(compatibilityVersion);
        properties.getInstallDirs().setChannels(tempDir.toString());
        properties.setIndexSources(List.of("https://raw.githubusercontent.com/ntygod/ZhiWei-index/main/index.json"));
        return properties;
    }

    private ExtensionPackage channelPackage(String id, String minVersion, String repoUrl) {
        return ExtensionPackage.builder()
                .id(id)
                .name("飞书")
                .type(ExtensionType.CHANNEL)
                .version("1.0.0")
                .author("zhiwei")
                .description("飞书渠道插件")
                .repoUrl(repoUrl)
                .filePath("channel-plugin.json")
                .tags(List.of("channel", "feishu"))
                .requirements(List.of("需要外部 connector"))
                .minLifepilotVersion(minVersion)
                .createdAt("2026-03-29T00:00:00Z")
                .updatedAt("2026-03-29T00:00:00Z")
                .downloads(0)
                .verified(true)
                .build();
    }

    private ChannelPluginDescriptor channelDescriptor(String pluginId) {
        return new ChannelPluginDescriptor(
                pluginId,
                "飞书",
                "1.0.0",
                "community",
                "feishu-ext",
                ConnectorMode.EXTERNAL,
                Map.of(
                        "protocol", "http",
                        "pathPrefix", "/instances/{instanceId}",
                        "managed", Map.of(
                                "strategy", "installed-jar",
                                "artifactPath", "dist/feishu-connector.jar",
                                "workspace", "connectors/feishu-connector"
                        )
                ),
                List.of("receive", "send", "image", "file"),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "baseUrl", Map.of("type", "string"),
                                "appId", Map.of("type", "string"),
                                "appSecret", Map.of("type", "string", "secret", true)
                        ),
                        "required", List.of("appId", "appSecret")
                ),
                List.of("appSecret"),
                Map.of("title", "飞书"),
                new ChannelPluginResources(
                        "docs/README.md",
                        "assets/icon.svg",
                        List.of("examples/webhook.json"),
                        List.of("dist/feishu-connector.jar")
                ),
                null
        );
    }

    private record LocalChannelSource(HttpServer server, String baseUrl) implements AutoCloseable {

        static LocalChannelSource start(ChannelPluginDescriptor descriptor,
                                        ObjectMapper objectMapper) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String manifest = objectMapper.writeValueAsString(descriptor);
            registerText(server, "/feishu/channel-plugin.json", manifest, "application/json; charset=utf-8");
            registerText(server, "/feishu/docs/README.md", "# 飞书插件", "text/markdown; charset=utf-8");
            registerText(server, "/feishu/examples/webhook.json", "{\n  \"connectionMode\": \"webhook\"\n}", "application/json; charset=utf-8");
            registerText(server, "/feishu/assets/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"></svg>", "image/svg+xml");
            registerBinary(server, "/feishu/dist/feishu-connector.jar", new byte[]{0x50, 0x4b, 0x03, 0x04}, "application/java-archive");
            server.start();
            return new LocalChannelSource(server,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/feishu");
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void registerText(HttpServer server,
                                         String path,
                                         String text,
                                         String contentType) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            registerBinary(server, path, body, contentType);
        }

        private static void registerBinary(HttpServer server,
                                           String path,
                                           byte[] body,
                                           String contentType) {
            server.createContext(path, exchange -> write(exchange, body, contentType));
        }

        private static void write(HttpExchange exchange,
                                  byte[] body,
                                  String contentType) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            } finally {
                exchange.close();
            }
        }
    }
}
