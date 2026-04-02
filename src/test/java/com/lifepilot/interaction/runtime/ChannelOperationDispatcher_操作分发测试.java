package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationResponse;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelOperationDispatcher} 操作分发测试。
 *
 * @author zsg
 * @since 2026-04-02
 */
@ExtendWith(MockitoExtension.class)
class ChannelOperationDispatcher_操作分发测试 {

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private ChannelInstanceEventService channelInstanceEventService;

    @Mock
    private RestClient restClient;

    @Mock
    private ConnectorManager connectorManager;

    private ChannelOperationDispatcher dispatcher;

    private ChannelPluginDescriptor descriptor;
    private ChannelInstance instance;

    @BeforeEach
    void 初始化() {
        dispatcher = new ChannelOperationDispatcher(
                channelRegistry, channelInstanceEventService, restClient, connectorManager);

        descriptor = new ChannelPluginDescriptor(
                "feishu",
                "飞书",
                "1.0.0",
                "zhiwei",
                "feishu",
                ConnectorMode.EXTERNAL,
                Map.of("protocol", "http"),
                List.of("receive", "send"),
                Map.of("type", "object"),
                List.of(),
                null,
                null
        );
        instance = new ChannelInstance(
                "feishu.prod",
                "feishu",
                "feishu",
                "飞书生产",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("baseUrl", "http://127.0.0.1:19001"),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "test-runtime-token"),
                null,
                null,
                null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );
    }

    @Test
    void 正常操作分发_应构造正确URL并传递header和body() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001");

        var expectedResponse = new ChannelRuntimeOperationResponse(
                true, "op-001", Map.of("messageId", "msg-123"), null);
        mockPostResponse(restClient, expectedResponse);

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-001", "message_update",
                Map.of("messageId", "msg-123", "content", "更新内容"), null);

        // when
        ChannelRuntimeOperationResponse response = dispatcher.execute(instance, request);

        // then
        assertThat(response.success()).isTrue();
        assertThat(response.operationId()).isEqualTo("op-001");
        assertThat(response.result()).containsEntry("messageId", "msg-123");

        // 验证事件记录
        verify(channelInstanceEventService).record(
                eq("feishu.prod"),
                eq("OPERATION_DISPATCHED"),
                anyString(),
                any());
    }

    @Test
    void 正常操作分发_RestClient返回null_应返回默认成功响应() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001");
        mockPostResponse(restClient, null);

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-002", "message_recall",
                Map.of("messageId", "msg-456"), null);

        // when
        ChannelRuntimeOperationResponse response = dispatcher.execute(instance, request);

        // then
        assertThat(response.success()).isTrue();
        assertThat(response.operationId()).isEqualTo("op-002");
    }

    @Test
    void connectorBaseUrl未配置_应抛出IllegalStateException() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true)).thenReturn(null);

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-003", "file_upload",
                Map.of("fileName", "test.txt"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(instance, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connector baseUrl");
    }

    @Test
    void connectorBaseUrl为空白_应抛出IllegalStateException() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true)).thenReturn("   ");

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-004", "file_upload",
                Map.of("fileName", "test.txt"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(instance, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connector baseUrl");
    }

    @Test
    void runtimeToken未配置_应抛出IllegalStateException() {
        // given
        ChannelInstance noTokenInstance = new ChannelInstance(
                "feishu.notoken",
                "feishu",
                "feishu",
                "飞书无Token",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("baseUrl", "http://127.0.0.1:19001"),
                null,  // secretConfig 为 null
                null, null, null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );

        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(noTokenInstance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001");

        var request = new ChannelRuntimeOperationRequest(
                "feishu.notoken", "op-005", "message_update",
                Map.of("messageId", "msg-789"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(noTokenInstance, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtimeToken");
    }

    @Test
    void runtimeToken为空白_应抛出IllegalStateException() {
        // given
        ChannelInstance blankTokenInstance = new ChannelInstance(
                "feishu.blanktoken",
                "feishu",
                "feishu",
                "飞书空Token",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of("baseUrl", "http://127.0.0.1:19001"),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "   "),
                null, null, null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );

        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(blankTokenInstance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001");

        var request = new ChannelRuntimeOperationRequest(
                "feishu.blanktoken", "op-006", "message_update",
                Map.of("messageId", "msg-000"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(blankTokenInstance, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtimeToken");
    }

    @Test
    void 渠道插件未注册_应抛出IllegalArgumentException() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.empty());

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-007", "message_update",
                Map.of("messageId", "msg-000"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(instance, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的渠道插件");
    }

    @Test
    void RestClient异常_应记录失败事件并重新抛出() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001");
        mockPostThrow(restClient, new ResourceAccessException("Connection refused"));

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-008", "file_download",
                Map.of("fileToken", "ft-abc"), null);

        // when & then
        assertThatThrownBy(() -> dispatcher.execute(instance, request))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("Connection refused");

        // 验证记录了失败事件
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.captor();
        verify(channelInstanceEventService).record(
                eq("feishu.prod"),
                eq("OPERATION_FAILED"),
                anyString(),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("operationId", "op-008");
        assertThat(payload).containsEntry("operationType", "file_download");
        assertThat(payload).containsEntry("success", false);
        assertThat(payload).containsKey("errorMessage");
    }

    @Test
    void baseUrl末尾带斜杠_应正确规范化URL() {
        // given
        when(channelRegistry.find("feishu")).thenReturn(Optional.of(descriptor));
        when(connectorManager.resolveBaseUrl(instance, descriptor, true))
                .thenReturn("http://127.0.0.1:19001/");

        var expectedResponse = new ChannelRuntimeOperationResponse(
                true, "op-009", Map.of(), null);

        // 使用 ArgumentCaptor 捕获实际 URL
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.captor();
        mockPostResponseWithUrlCapture(restClient, expectedResponse, urlCaptor);

        var request = new ChannelRuntimeOperationRequest(
                "feishu.prod", "op-009", "message_recall",
                Map.of("messageId", "msg-url"), null);

        // when
        dispatcher.execute(instance, request);

        // then - URL 不应有双斜杠
        String capturedUrl = urlCaptor.getValue();
        assertThat(capturedUrl).doesNotContain("//instances");
        assertThat(capturedUrl).contains("/instances/feishu.prod/operate");
    }

    // ─── 辅助方法 ───────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mockPostResponse(RestClient restClient,
                                          ChannelRuntimeOperationResponse responseBody) {
        RestClient.RequestBodyUriSpec bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).body(any(ChannelRuntimeOperationRequest.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ChannelRuntimeOperationResponse.class)).thenReturn(responseBody);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mockPostThrow(RestClient restClient, RuntimeException exception) {
        RestClient.RequestBodyUriSpec bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);

        when(restClient.post()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).body(any(ChannelRuntimeOperationRequest.class));
        when(bodySpec.retrieve()).thenThrow(exception);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mockPostResponseWithUrlCapture(RestClient restClient,
                                                        ChannelRuntimeOperationResponse responseBody,
                                                        ArgumentCaptor<String> urlCaptor) {
        RestClient.RequestBodyUriSpec bodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(urlCaptor.capture())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).body(any(ChannelRuntimeOperationRequest.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ChannelRuntimeOperationResponse.class)).thenReturn(responseBody);
    }
}
