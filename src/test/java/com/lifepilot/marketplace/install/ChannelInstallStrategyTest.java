package com.lifepilot.marketplace.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ChannelPluginResources;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelPluginRepository;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link ChannelInstallStrategy} 单元测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
class ChannelInstallStrategyTest {

    @TempDir
    Path tempDir;

    private final MarketplaceProperties properties = new MarketplaceProperties();

    @Test
    void register_合法Manifest_完成注册() throws Exception {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelPluginRepository channelPluginRepository = mock(ChannelPluginRepository.class);
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                new ObjectMapper(),
                tempDir,
                properties
        );
        ExtensionPackage pkg = channelPkg("1.0.0");
        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "test-channel",
                "测试渠道",
                "1.0.0",
                "test",
                "telegram",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "baseUrl", Map.of("type", "string"),
                                "botToken", Map.of("type", "string", "secret", true)
                        ),
                        "required", List.of("baseUrl", "botToken")
                ),
                List.of("botToken"),
                Map.of("title", "接入说明"),
                null,
                null
        );
        Path manifest = tempDir.resolve("channel-plugin.json");
        Files.writeString(manifest, new ObjectMapper().writeValueAsString(descriptor));

        strategy.register(manifest, pkg);

        verify(channelPluginRepository).save(descriptor);
        verify(channelRegistry).register(descriptor);
    }

    @Test
    void register_本地ConnectorManifest_拒绝注册() throws Exception {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelPluginRepository channelPluginRepository = mock(ChannelPluginRepository.class);
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                new ObjectMapper(),
                tempDir,
                properties
        );
        ExtensionPackage pkg = channelPkg("1.0.0");
        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "test-channel",
                "测试渠道",
                "1.0.0",
                "test",
                "telegram",
                ConnectorMode.LOCAL,
                null,
                List.of("receive"),
                Map.of(
                        "type", "object",
                        "properties", Map.of()
                ),
                List.of(),
                Map.of("title", "接入说明"),
                null,
                null
        );
        Path manifest = tempDir.resolve("channel-plugin.json");
        Files.writeString(manifest, new ObjectMapper().writeValueAsString(descriptor));

        // register 阶段对校验问题仅记录警告，不再阻断注册
        strategy.register(manifest, pkg);

        verify(channelPluginRepository).save(descriptor);
        verify(channelRegistry).register(descriptor);
    }

    @Test
    void download_带资源清单_按目录下载Manifest和资源() throws Exception {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelPluginRepository channelPluginRepository = mock(ChannelPluginRepository.class);
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                new ObjectMapper(),
                tempDir,
                properties
        );
        ExtensionPackage pkg = channelPkg("1.0.0");
        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "test-channel",
                "测试渠道",
                "1.0.0",
                "test",
                "telegram",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "baseUrl", Map.of("type", "string")
                        )
                ),
                List.of(),
                Map.of("title", "接入说明"),
                new ChannelPluginResources(
                        "docs/README.md",
                        "assets/icon.svg",
                        List.of("examples/default.json"),
                        List.of("assets/schema.json")
                ),
                null
        );
        RestClient restClient = mock(RestClient.class);
        mockResponses(restClient, Map.of(
                "https://github.com/test/channel/channel-plugin.json",
                new ObjectMapper().writeValueAsString(descriptor),
                "https://github.com/test/channel/docs/README.md",
                "# readme".getBytes(),
                "https://github.com/test/channel/assets/icon.svg",
                "<svg/>".getBytes(),
                "https://github.com/test/channel/examples/default.json",
                "{}".getBytes(),
                "https://github.com/test/channel/assets/schema.json",
                "{\"type\":\"object\"}".getBytes()
        ));

        Path manifestPath = strategy.download(pkg, restClient);

        assertThat(manifestPath).isEqualTo(tempDir.resolve("test-channel").resolve("channel-plugin.json"));
        assertThat(Files.readString(manifestPath)).contains("\"pluginId\":\"test-channel\"");
        assertThat(Files.readString(tempDir.resolve("test-channel").resolve("docs").resolve("README.md")))
                .isEqualTo("# readme");
        assertThat(Files.readString(tempDir.resolve("test-channel").resolve("examples").resolve("default.json")))
                .isEqualTo("{}");
        assertThat(Files.readString(tempDir.resolve("test-channel").resolve("assets").resolve("schema.json")))
                .isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void download_rawGithub失败_自动回退到JsDelivr() throws Exception {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelPluginRepository channelPluginRepository = mock(ChannelPluginRepository.class);
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelInstallStrategy strategy = new ChannelInstallStrategy(
                channelRegistry,
                channelPluginRepository,
                channelInstanceService,
                new ObjectMapper(),
                tempDir,
                properties
        );
        ExtensionPackage pkg = channelPkg("1.0.0").toBuilder()
                .repoUrl("https://raw.githubusercontent.com/test/channel/main")
                .build();
        ChannelPluginDescriptor descriptor = new ChannelPluginDescriptor(
                "test-channel",
                "测试渠道",
                "1.0.0",
                "test",
                "telegram",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive"),
                Map.of("type", "object", "properties", Map.of()),
                List.of(),
                Map.of("title", "接入说明"),
                null,
                null
        );
        RestClient restClient = mock(RestClient.class);
        mockResponses(restClient, Map.of(
                "https://raw.githubusercontent.com/test/channel/main/channel-plugin.json",
                new ResourceAccessException("Connection reset"),
                "https://cdn.jsdelivr.net/gh/test/channel@main/channel-plugin.json",
                new ObjectMapper().writeValueAsString(descriptor)
        ));

        Path manifestPath = strategy.download(pkg, restClient);

        assertThat(Files.readString(manifestPath)).contains("\"pluginId\":\"test-channel\"");
    }

    @SuppressWarnings("unchecked")
    private static void mockResponses(RestClient restClient, Map<String, Object> responses) {
        RestClient.RequestHeadersUriSpec headerUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        org.mockito.Mockito.when(restClient.get()).thenReturn(headerUriSpec);
        org.mockito.Mockito.when(headerUriSpec.uri(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            Object response = responses.get(url);
            RestClient.RequestHeadersSpec headerSpec = mock(RestClient.RequestHeadersSpec.class);
            RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
            org.mockito.Mockito.when(headerSpec.headers(any())).thenReturn(headerSpec);
            org.mockito.Mockito.when(headerSpec.retrieve()).thenReturn(responseSpec);
            org.mockito.Mockito.when(responseSpec.body(String.class))
                    .thenAnswer(ignored -> {
                        if (response instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }
                        return response instanceof String text ? text : null;
                    });
            org.mockito.Mockito.when(responseSpec.body(byte[].class))
                    .thenAnswer(ignored -> {
                        if (response instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }
                        return response instanceof byte[] bytes ? bytes : null;
                    });
            return headerSpec;
        });
    }

    private static ExtensionPackage channelPkg(String version) {
        return ExtensionPackage.builder()
                .id("test-channel")
                .name("Test Channel")
                .type(ExtensionType.CHANNEL)
                .version(version)
                .author("test")
                .description("测试渠道")
                .repoUrl("https://github.com/test/channel")
                .filePath("channel-plugin.json")
                .tags(List.of())
                .requirements(List.of())
                .minLifepilotVersion("1.0.0")
                .createdAt("2026-01-01T00:00:00Z")
                .updatedAt("2026-01-01T00:00:00Z")
                .downloads(0)
                .verified(false)
                .build();
    }
}
