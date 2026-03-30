package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeInstanceCommandRequest;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接器运行时管理器。
 *
 * <p>当前先承载控制面状态流转，后续再接入真正的本地 / 外部 connector 启停协议。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ConnectorRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRuntimeManager.class);

    private final ChannelInstanceService channelInstanceService;
    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final RestClient restClient;
    private final ConnectorManager connectorManager;

    public ConnectorRuntimeManager(ChannelInstanceService channelInstanceService,
                                   ChannelRegistry channelRegistry,
                                   ChannelInstanceEventService channelInstanceEventService,
                                   RestClient restClient,
                                   ConnectorManager connectorManager) {
        this.channelInstanceService = channelInstanceService;
        this.channelRegistry = channelRegistry;
        this.channelInstanceEventService = channelInstanceEventService;
        this.restClient = restClient;
        this.connectorManager = connectorManager;
    }

    public ChannelInstance start(String instanceId) {
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        try {
            channelInstanceService.updateStatus(
                    instanceId,
                    ChannelInstanceStatus.STARTING,
                    null,
                    instance.lastHeartbeatAt()
            );
            invokeExternalCommand(instance, "/start", true);
            ChannelInstance updated = channelInstanceService.updateStatus(
                    instanceId, ChannelInstanceStatus.RUNNING, null, Instant.now());
            channelInstanceEventService.record(
                    instanceId,
                    "INSTANCE_STARTED",
                    "渠道实例已启动",
                    Map.of(
                            "platform", updated.platform(),
                            "pluginId", updated.pluginId(),
                            "status", updated.status().name()
                    )
            );
            log.info("渠道实例已启动: instanceId={}, platform={}", instanceId, updated.platform());
            return updated;
        } catch (RuntimeException e) {
            markError(instanceId, e.getMessage());
            throw e;
        }
    }

    public ChannelInstance stop(String instanceId) {
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        channelInstanceService.updateStatus(
                instanceId,
                ChannelInstanceStatus.STOPPING,
                null,
                instance.lastHeartbeatAt()
        );
        invokeExternalCommand(instance, "/stop", false);
        ChannelInstance updated = channelInstanceService.updateStatus(
                instanceId, ChannelInstanceStatus.STOPPED, null, null);
        channelInstanceEventService.record(
                instanceId,
                "INSTANCE_STOPPED",
                "渠道实例已停止",
                Map.of("status", updated.status().name())
        );
        log.info("渠道实例已停止: instanceId={}", instanceId);
        return updated;
    }

    public ChannelInstance reload(String instanceId) {
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        try {
            channelInstanceService.updateStatus(
                    instanceId,
                    ChannelInstanceStatus.STARTING,
                    null,
                    instance.lastHeartbeatAt()
            );
            invokeExternalCommand(instance, "/reload", true);
            ChannelInstance updated = channelInstanceService.updateStatus(
                    instanceId, ChannelInstanceStatus.RUNNING, null, Instant.now());
            channelInstanceEventService.record(
                    instanceId,
                    "INSTANCE_RELOADED",
                    "渠道实例已重载",
                    Map.of(
                            "status", updated.status().name(),
                            "platform", updated.platform()
                    )
            );
            log.info("渠道实例已重载: instanceId={}", instanceId);
            return updated;
        } catch (RuntimeException e) {
            markError(instanceId, e.getMessage());
            throw e;
        }
    }

    public ChannelInstance markHeartbeat(String instanceId) {
        ChannelInstance existing = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        ChannelInstance updated = channelInstanceService.updateStatus(
                instanceId, ChannelInstanceStatus.RUNNING, null, Instant.now());
        if (shouldRecordHeartbeat(existing.lastHeartbeatAt(), updated.lastHeartbeatAt())) {
            channelInstanceEventService.record(
                    instanceId,
                    "INSTANCE_HEARTBEAT",
                    "收到 connector 心跳",
                    Map.of("recordedAt", updated.lastHeartbeatAt() != null ? updated.lastHeartbeatAt().toString() : "")
            );
        }
        return updated;
    }

    public ChannelInstance markError(String instanceId, @Nullable String lastError) {
        ChannelInstance updated = channelInstanceService.updateStatus(
                instanceId, ChannelInstanceStatus.ERROR, lastError, null);
        channelInstanceEventService.record(
                instanceId,
                "INSTANCE_ERROR",
                lastError != null && !lastError.isBlank() ? lastError : "渠道实例进入异常状态",
                Map.of("status", updated.status().name())
        );
        log.warn("渠道实例进入异常状态: instanceId={}, error={}", instanceId, lastError);
        return updated;
    }

    public Map<String, Object> health(String instanceId) {
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        var plugin = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instance.instanceId());
        payload.put("pluginId", instance.pluginId());
        payload.put("platform", instance.platform());
        payload.put("enabled", instance.enabled());
        payload.put("status", instance.status().name());
        putIfNotNull(payload, "lastHeartbeatAt", instance.lastHeartbeatAt());
        putIfNotNull(payload, "lastError", instance.lastError());
        payload.put("connectorMode", plugin.connectorMode().name());

        if (plugin.connectorMode() != ConnectorMode.EXTERNAL) {
            payload.put("healthy", instance.enabled() && instance.status() == ChannelInstanceStatus.RUNNING);
            payload.put("details", Map.of("mode", "local"));
            Map<String, Object> result = Map.copyOf(payload);
            recordHealthEvent(instance, true, result);
            return result;
        }

        String baseUrl = resolveConnectorBaseUrl(instance, plugin, instance.enabled());
        if (baseUrl == null || baseUrl.isBlank()) {
            payload.put("healthy", false);
            payload.put("details", Map.of("error", "未配置 connector baseUrl，且当前没有可用的官方托管 connector"));
            Map<String, Object> result = Map.copyOf(payload);
            recordHealthEvent(instance, false, result);
            return result;
        }
        String instanceToken = resolveInstanceToken(instance);
        if (instanceToken == null || instanceToken.isBlank()) {
            payload.put("healthy", false);
            payload.put("details", Map.of("error", "未配置 runtimeToken"));
            Map<String, Object> result = Map.copyOf(payload);
            recordHealthEvent(instance, false, result);
            return result;
        }

        String healthUrl = normalizeBaseUrl(baseUrl) + "/instances/" + instance.instanceId() + "/health";
        try {
            Map<String, Object> healthDetails = restClient.get()
                    .uri(healthUrl)
                    .header(ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN, instanceToken)
                    .retrieve()
                    .body(Map.class);
            boolean healthy = readHealthyFlag(healthDetails);
            payload.put("healthy", healthy);
            payload.put("details", healthDetails != null ? healthDetails : Map.of());
            Map<String, Object> result = Map.copyOf(payload);
            recordHealthEvent(instance, healthy, result);
            return result;
        } catch (RuntimeException e) {
            channelInstanceEventService.record(
                instance.instanceId(),
                "HEALTH_CHECK",
                    "健康检查异常",
                    Map.of(
                            "healthy", false,
                            "status", instance.status().name(),
                            "connectorMode", plugin.connectorMode().name(),
                            "errorType", e.getClass().getSimpleName()
                    )
            );
            throw e;
        }
    }

    private void invokeExternalCommand(ChannelInstance instance, String suffix, boolean includeBody) {
        var plugin = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));
        if (plugin.connectorMode() != ConnectorMode.EXTERNAL) {
            return;
        }

        String baseUrl = resolveConnectorBaseUrl(instance, plugin, true);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 connector baseUrl: " + instance.instanceId());
        }
        String instanceToken = resolveInstanceToken(instance);
        if (instanceToken == null || instanceToken.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 runtimeToken: " + instance.instanceId());
        }

        String commandUrl = normalizeBaseUrl(baseUrl) + "/instances/" + instance.instanceId() + suffix;
        var requestSpec = restClient.post()
                .uri(commandUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN, instanceToken);
        if (includeBody) {
            requestSpec.body(new ChannelRuntimeInstanceCommandRequest(
                    instance.instanceId(),
                    instance.pluginId(),
                    instance.platform(),
                    instance.config(),
                    instance.secretConfig(),
                    instance.routingPolicy()
            ));
        }
        requestSpec.retrieve().toBodilessEntity();
    }

    @Nullable
    private String resolveConnectorBaseUrl(ChannelInstance instance,
                                           com.lifepilot.interaction.model.ChannelPluginDescriptor plugin,
                                           boolean ensureStarted) {
        String managedBaseUrl = connectorManager.resolveBaseUrl(instance, plugin, ensureStarted);
        if (managedBaseUrl != null && !managedBaseUrl.isBlank()) {
            return managedBaseUrl;
        }
        if (plugin.connectorSpec() == null) {
            return null;
        }
        Object fromSpec = plugin.connectorSpec().get("baseUrl");
        if (fromSpec instanceof String value && !value.isBlank()) {
            return normalizeBaseUrl(value.trim());
        }
        Object fromSpecUrl = plugin.connectorSpec().get("url");
        return fromSpecUrl instanceof String value && !value.isBlank() ? normalizeBaseUrl(value.trim()) : null;
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
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean shouldRecordHeartbeat(@Nullable Instant previousHeartbeatAt,
                                          @Nullable Instant currentHeartbeatAt) {
        if (currentHeartbeatAt == null) {
            return false;
        }
        if (previousHeartbeatAt == null) {
            return true;
        }
        return Duration.between(previousHeartbeatAt, currentHeartbeatAt).compareTo(Duration.ofMinutes(10)) >= 0;
    }

    private void recordHealthEvent(ChannelInstance instance, boolean healthy, Map<String, Object> payload) {
        channelInstanceEventService.record(
                instance.instanceId(),
                "HEALTH_CHECK",
                healthy ? "健康检查通过" : "健康检查异常",
                Map.of(
                        "healthy", healthy,
                        "status", payload.get("status"),
                        "connectorMode", payload.get("connectorMode")
                )
        );
    }

    private boolean readHealthyFlag(@Nullable Map<String, Object> healthDetails) {
        if (healthDetails == null) {
            return true;
        }
        Object raw = healthDetails.get("healthy");
        if (raw instanceof Boolean healthy) {
            return healthy;
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return true;
    }

    private void putIfNotNull(Map<String, Object> payload,
                              String key,
                              @Nullable Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
