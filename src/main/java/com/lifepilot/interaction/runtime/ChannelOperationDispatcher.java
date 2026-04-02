package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationResponse;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渠道操作分发器。
 *
 * <p>负责将通用操作请求（消息更新、撤回、文件上传/下载等）分发到外部 connector 的
 * {@code /instances/{instanceId}/operate} 端点。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class ChannelOperationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelOperationDispatcher.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final RestClient restClient;
    private final ConnectorManager connectorManager;

    public ChannelOperationDispatcher(ChannelRegistry channelRegistry,
                                      ChannelInstanceEventService channelInstanceEventService,
                                      RestClient restClient,
                                      ConnectorManager connectorManager) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceEventService = channelInstanceEventService;
        this.restClient = restClient;
        this.connectorManager = connectorManager;
    }

    /**
     * 将操作请求分发到外部 connector。
     *
     * @param instance 渠道实例
     * @param request  操作请求
     * @return 操作响应
     * @throws IllegalArgumentException 渠道插件未注册
     * @throws IllegalStateException    connector 地址或 token 未配置
     */
    public ChannelRuntimeOperationResponse execute(ChannelInstance instance,
                                                    ChannelRuntimeOperationRequest request) {
        var plugin = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));
        String baseUrl = resolveConnectorBaseUrl(instance, plugin);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 connector baseUrl: " + instance.instanceId());
        }
        String instanceToken = resolveInstanceToken(instance);
        if (instanceToken == null || instanceToken.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 runtimeToken: " + instance.instanceId());
        }

        String operateUrl = normalizeBaseUrl(baseUrl)
                + "/instances/" + instance.instanceId()
                + ChannelRuntimeProtocol.OPERATION_PATH;

        try {
            ChannelRuntimeOperationResponse response = restClient.post()
                    .uri(operateUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN, instanceToken)
                    .body(request)
                    .retrieve()
                    .body(ChannelRuntimeOperationResponse.class);

            channelInstanceEventService.record(
                    instance.instanceId(),
                    "OPERATION_DISPATCHED",
                    "操作请求已投递到 connector",
                    buildOperationPayload(request, true, null)
            );
            log.debug("操作请求已投递到外部 connector: instanceId={}, operationId={}, operationType={}",
                    instance.instanceId(), request.operationId(), request.operationType());

            return response != null ? response : new ChannelRuntimeOperationResponse(
                    true, request.operationId(), Map.of(), null
            );
        } catch (RuntimeException e) {
            channelInstanceEventService.record(
                    instance.instanceId(),
                    "OPERATION_FAILED",
                    "操作请求投递失败: " + e.getMessage(),
                    buildOperationPayload(request, false, e.getMessage())
            );
            log.warn("操作请求投递到外部 connector 失败: instanceId={}, operationId={}, error={}",
                    instance.instanceId(), request.operationId(), e.getMessage());
            throw e;
        }
    }

    @Nullable
    private String resolveConnectorBaseUrl(ChannelInstance instance,
                                           ChannelPluginDescriptor plugin) {
        String managedBaseUrl = connectorManager.resolveBaseUrl(instance, plugin, true);
        if (managedBaseUrl != null && !managedBaseUrl.isBlank()) {
            return managedBaseUrl;
        }
        if (plugin.connectorSpec() == null) {
            return null;
        }
        Object fromSpec = plugin.connectorSpec().get("baseUrl");
        if (fromSpec instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        Object fromSpecUrl = plugin.connectorSpec().get("url");
        return fromSpecUrl instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    @Nullable
    private String resolveInstanceToken(ChannelInstance instance) {
        if (instance.secretConfig() == null) {
            return null;
        }
        Object raw = instance.secretConfig().get(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY);
        return raw instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private Map<String, Object> buildOperationPayload(ChannelRuntimeOperationRequest request,
                                                       boolean success,
                                                       @Nullable String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", request.operationId());
        payload.put("operationType", request.operationType());
        payload.put("success", success);
        if (errorMessage != null && !errorMessage.isBlank()) {
            payload.put("errorMessage", errorMessage);
        }
        return Map.copyOf(payload);
    }
}
