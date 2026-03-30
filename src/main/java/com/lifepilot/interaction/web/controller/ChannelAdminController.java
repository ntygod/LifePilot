package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceEvent;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.ChannelRuntimeProtocol;
import com.lifepilot.interaction.runtime.ConnectorManager;
import com.lifepilot.interaction.runtime.ConnectorRuntimeManager;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.interaction.web.model.CreateChannelInstanceRequest;
import com.lifepilot.interaction.web.model.UpdateChannelInstanceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 渠道控制面管理接口。
 *
 * <p>当前提供插件与实例的查询、创建、更新、启停、重载、健康检查和删除能力。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
@RestController
@RequestMapping("/api/channels")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ChannelAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChannelAdminController.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceService channelInstanceService;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final ConnectorRuntimeManager connectorRuntimeManager;
    private final ConnectorManager connectorManager;

    public ChannelAdminController(ChannelRegistry channelRegistry,
                                  ChannelInstanceService channelInstanceService,
                                  ChannelInstanceEventService channelInstanceEventService,
                                  ConnectorRuntimeManager connectorRuntimeManager,
                                  ConnectorManager connectorManager) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceService = channelInstanceService;
        this.channelInstanceEventService = channelInstanceEventService;
        this.connectorRuntimeManager = connectorRuntimeManager;
        this.connectorManager = connectorManager;
    }

    @GetMapping("/plugins")
    public ResponseEntity<List<ChannelPluginDescriptor>> listPlugins() {
        return ResponseEntity.ok(channelRegistry.listAll().stream()
                .map(connectorManager::decorate)
                .toList());
    }

    @GetMapping("/instances")
    public ResponseEntity<List<ChannelInstance>> listInstances() {
        return ResponseEntity.ok(channelInstanceService.listAll().stream()
                .map(this::maskSecrets)
                .toList());
    }

    @GetMapping("/instances/{instanceId}")
    public ResponseEntity<ChannelInstance> getInstance(@PathVariable String instanceId) {
        return channelInstanceService.find(instanceId)
                .map(this::maskSecrets)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/instances")
    public ResponseEntity<?> createInstance(@RequestBody CreateChannelInstanceRequest request) {
        if (request.pluginId() == null || request.pluginId().isBlank()) {
            return ResponseEntity.badRequest().body("pluginId 不能为空");
        }
        Optional<ChannelPluginDescriptor> plugin = channelRegistry.find(request.pluginId());
        if (plugin.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("渠道插件不存在: " + request.pluginId());
        }

        String instanceId = request.instanceId() != null && !request.instanceId().isBlank()
                ? request.instanceId().trim()
                : request.pluginId() + "." + UUID.randomUUID().toString().substring(0, 8);
        if (channelInstanceService.find(instanceId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("渠道实例已存在: " + instanceId);
        }

        String displayName = request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim()
                : plugin.get().name() + " 实例";
        Map<String, Object> secretConfig = prepareSecretConfig(request, plugin.get());
        Instant now = Instant.now();
        ChannelInstance instance = new ChannelInstance(
                instanceId,
                plugin.get().pluginId(),
                plugin.get().platform(),
                displayName,
                request.enabled(),
                ChannelInstanceStatus.CREATED,
                request.config(),
                secretConfig,
                request.routingPolicy(),
                null,
                null,
                now,
                now
        );
        ChannelInstance saved = channelInstanceService.save(instance);
        channelInstanceEventService.record(
                saved.instanceId(),
                "INSTANCE_CREATED",
                "渠道实例已创建",
                Map.of(
                        "pluginId", saved.pluginId(),
                        "platform", saved.platform(),
                        "enabled", saved.enabled()
                )
        );
        if (saved.enabled()) {
            saved = connectorRuntimeManager.start(saved.instanceId());
        }
        log.info("渠道实例创建完成: instanceId={}, pluginId={}", saved.instanceId(), saved.pluginId());
        return ResponseEntity.status(HttpStatus.CREATED).body(maskSecrets(saved));
    }

    @PutMapping("/instances/{instanceId}")
    public ResponseEntity<?> updateInstance(@PathVariable String instanceId,
                                            @RequestBody UpdateChannelInstanceRequest request) {
        ChannelInstance existing = channelInstanceService.find(instanceId).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        ChannelPluginDescriptor plugin = channelRegistry.find(existing.pluginId())
                .orElseThrow(() -> new IllegalStateException("渠道插件不存在: " + existing.pluginId()));
        Map<String, Object> mergedSecretConfig = mergeSecretConfig(existing, request.secretConfig(), plugin);
        ChannelInstance updated = new ChannelInstance(
                existing.instanceId(),
                existing.pluginId(),
                existing.platform(),
                request.displayName() != null && !request.displayName().isBlank()
                        ? request.displayName().trim()
                        : existing.displayName(),
                request.enabled() != null ? request.enabled() : existing.enabled(),
                existing.status(),
                request.config() != null ? request.config() : existing.config(),
                mergedSecretConfig,
                request.routingPolicy() != null ? request.routingPolicy() : existing.routingPolicy(),
                existing.lastHeartbeatAt(),
                existing.lastError(),
                existing.createdAt(),
                Instant.now()
        );
        ChannelInstance saved = channelInstanceService.update(updated);
        channelInstanceEventService.record(
                saved.instanceId(),
                "INSTANCE_UPDATED",
                "实例配置已更新",
                Map.of(
                        "enabled", saved.enabled(),
                        "status", saved.status().name()
                )
        );
        if (saved.enabled()) {
            saved = connectorRuntimeManager.reload(saved.instanceId());
        } else if (saved.status() == ChannelInstanceStatus.RUNNING) {
            saved = connectorRuntimeManager.stop(saved.instanceId());
        }
        return ResponseEntity.ok(maskSecrets(saved));
    }

    @GetMapping("/instances/{instanceId}/events")
    public ResponseEntity<List<ChannelInstanceEvent>> listInstanceEvents(@PathVariable String instanceId,
                                                                         @RequestParam(defaultValue = "20") int limit) {
        limit = Math.min(Math.max(limit, 1), 200);
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(channelInstanceEventService.listRecent(instanceId, limit));
    }

    @PatchMapping("/instances/{instanceId}/enabled")
    public ResponseEntity<?> updateEnabled(@PathVariable String instanceId,
                                           @RequestParam boolean enabled) {
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ChannelInstance updated = channelInstanceService.updateEnabled(instanceId, enabled);
        if (enabled) {
            updated = connectorRuntimeManager.start(instanceId);
        } else {
            updated = connectorRuntimeManager.stop(instanceId);
        }
        return ResponseEntity.ok(maskSecrets(updated));
    }

    @PostMapping("/instances/{instanceId}/start")
    public ResponseEntity<?> startInstance(@PathVariable String instanceId) {
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        channelInstanceService.updateEnabled(instanceId, true);
        return ResponseEntity.ok(maskSecrets(connectorRuntimeManager.start(instanceId)));
    }

    @PostMapping("/instances/{instanceId}/stop")
    public ResponseEntity<?> stopInstance(@PathVariable String instanceId) {
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        channelInstanceService.updateEnabled(instanceId, false);
        return ResponseEntity.ok(maskSecrets(connectorRuntimeManager.stop(instanceId)));
    }

    @PostMapping("/instances/{instanceId}/reload")
    public ResponseEntity<?> reloadInstance(@PathVariable String instanceId) {
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(maskSecrets(connectorRuntimeManager.reload(instanceId)));
    }

    @GetMapping("/instances/{instanceId}/health")
    public ResponseEntity<?> health(@PathVariable String instanceId) {
        if (channelInstanceService.find(instanceId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(connectorRuntimeManager.health(instanceId));
    }

    @DeleteMapping("/instances/{instanceId}")
    public ResponseEntity<?> deleteInstance(@PathVariable String instanceId) {
        ChannelInstance existing = channelInstanceService.find(instanceId).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if ("web.default".equalsIgnoreCase(instanceId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("内建 Web 实例不可删除");
        }
        if (existing.status() == ChannelInstanceStatus.RUNNING) {
            connectorRuntimeManager.stop(instanceId);
        }
        channelInstanceService.delete(instanceId);
        return ResponseEntity.noContent().build();
    }

    @Nullable
    private Map<String, Object> prepareSecretConfig(CreateChannelInstanceRequest request,
                                                    ChannelPluginDescriptor plugin) {
        Map<String, Object> secrets = request.secretConfig() != null
                ? new LinkedHashMap<>(request.secretConfig())
                : new LinkedHashMap<>();
        if (plugin.connectorMode() == ConnectorMode.EXTERNAL) {
            secrets.computeIfAbsent(
                    ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY,
                    ignored -> UUID.randomUUID().toString().replace("-", "")
            );
        }
        return secrets.isEmpty() ? null : Map.copyOf(secrets);
    }

    @Nullable
    private Map<String, Object> mergeSecretConfig(ChannelInstance existing,
                                                  @Nullable Map<String, Object> incoming,
                                                  ChannelPluginDescriptor plugin) {
        Map<String, Object> secrets = existing.secretConfig() != null
                ? new LinkedHashMap<>(existing.secretConfig())
                : new LinkedHashMap<>();
        if (incoming != null) {
            // 保护系统自动生成的 runtimeToken，防止被用户输入覆盖
            incoming.forEach((key, value) -> {
                if (ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY.equals(key)) {
                    return;
                }
                secrets.put(key, value);
            });
        }
        if (plugin.connectorMode() == ConnectorMode.EXTERNAL) {
            secrets.computeIfAbsent(
                    ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY,
                    ignored -> UUID.randomUUID().toString().replace("-", "")
            );
        }
        return secrets.isEmpty() ? null : Map.copyOf(secrets);
    }

    private ChannelInstance maskSecrets(ChannelInstance instance) {
        if (instance.secretConfig() == null || instance.secretConfig().isEmpty()) {
            return instance;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        instance.secretConfig().forEach((key, value) -> {
            if (value instanceof String text && !text.isBlank()) {
                copy.put(key, maskSecret(text));
            } else {
                copy.put(key, value);
            }
        });
        return instance.withSecretConfig(Map.copyOf(copy));
    }

    private String maskSecret(String raw) {
        if (raw.length() <= 4) {
            return "****";
        }
        return "****" + raw.substring(raw.length() - 4);
    }
}
