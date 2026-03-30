package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectorRuntimeManager} 单元测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
class ConnectorRuntimeManagerTest {

    @Test
    void health_connector返回Unhealthy标记_主服务应保留异常状态() {
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelInstanceEventService channelInstanceEventService = mock(ChannelInstanceEventService.class);
        RestClient restClient = mock(RestClient.class);
        ConnectorManager connectorManager = mock(ConnectorManager.class);

        ChannelInstance instance = new ChannelInstance(
                "feishu.prod",
                "feishu",
                "feishu",
                "飞书生产机器人",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("baseUrl", "http://127.0.0.1:19001"),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "runtime-token"),
                null,
                null,
                null,
                Instant.parse("2026-03-29T12:00:00Z"),
                Instant.parse("2026-03-29T12:00:00Z")
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

        when(channelInstanceService.find("feishu.prod")).thenReturn(Optional.of(instance));
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true)).thenReturn("http://127.0.0.1:19001");
        mockHealthResponse(restClient, Map.of(
                "healthy", false,
                "reason", "connector paused"
        ));

        ConnectorRuntimeManager manager = new ConnectorRuntimeManager(
                channelInstanceService,
                channelRegistry,
                channelInstanceEventService,
                restClient,
                connectorManager
        );

        Map<String, Object> result = manager.health("feishu.prod");

        assertThat(result).containsEntry("healthy", false);
        assertThat(result).containsEntry("connectorMode", "EXTERNAL");
        assertThat(result).containsEntry("status", "RUNNING");
        assertThat(result).containsKey("details");
        assertThat((Map<String, Object>) result.get("details")).containsEntry("reason", "connector paused");
    }

    @Test
    void health_实例未配置BaseUrl但官方托管可用_应使用自动托管地址() {
        ChannelInstanceService channelInstanceService = mock(ChannelInstanceService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        ChannelInstanceEventService channelInstanceEventService = mock(ChannelInstanceEventService.class);
        RestClient restClient = mock(RestClient.class);
        ConnectorManager connectorManager = mock(ConnectorManager.class);

        ChannelInstance instance = new ChannelInstance(
                "feishu.auto",
                "feishu",
                "feishu",
                "飞书官方托管",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("connectionMode", "websocket"),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "runtime-token"),
                null,
                null,
                null,
                Instant.parse("2026-03-30T08:00:00Z"),
                Instant.parse("2026-03-30T08:00:00Z")
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

        when(channelInstanceService.find("feishu.auto")).thenReturn(Optional.of(instance));
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true)).thenReturn("http://127.0.0.1:19091");
        mockHealthResponse(restClient, Map.of("healthy", true));

        ConnectorRuntimeManager manager = new ConnectorRuntimeManager(
                channelInstanceService,
                channelRegistry,
                channelInstanceEventService,
                restClient,
                connectorManager
        );

        Map<String, Object> result = manager.health("feishu.auto");

        assertThat(result).containsEntry("healthy", true);
        assertThat(result).containsEntry("status", "RUNNING");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mockHealthResponse(RestClient restClient, Map<String, Object> responseBody) {
        RestClient.RequestHeadersUriSpec headersUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(headersUriSpec);
        when(headersUriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), org.mockito.ArgumentMatchers.<String[]>any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(responseBody);
    }
}
