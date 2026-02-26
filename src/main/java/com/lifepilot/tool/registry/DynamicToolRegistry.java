package com.lifepilot.tool.registry;

import com.lifepilot.guardrail.GuardrailPolicy;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.event.ToolRegistryEvent.*;
import com.lifepilot.tool.model.ToolLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 动态工具注册中心。
 *
 * <p>统一管理来自三个层次的工具，对 AgentLoop 完全透明。
 * 线程安全，支持运行时动态注册/注销。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class DynamicToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolRegistry.class);

    /** 工具存储：toolId → ToolContract。 */
    private final ConcurrentHashMap<String, ToolContract> tools = new ConcurrentHashMap<>();

    /** 工具层次索引：toolId → ToolLayer。 */
    private final ConcurrentHashMap<String, ToolLayer> toolLayers = new ConcurrentHashMap<>();

    /** MCP Server 工具索引：serverName → 工具 ID 列表。 */
    private final ConcurrentHashMap<String, List<String>> serverToolIndex = new ConcurrentHashMap<>();

    /** YAML 工具 ID 集合。 */
    private final ConcurrentHashMap<String, Boolean> yamlToolIds = new ConcurrentHashMap<>();

    private final GuardrailPolicy guardrailPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /** 快照缓存锁。 */
    private final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();
    private volatile List<ToolContract> cachedSnapshot = List.of();

    public DynamicToolRegistry(
            GuardrailPolicy guardrailPolicy,
            ApplicationEventPublisher eventPublisher) {
        this.guardrailPolicy = guardrailPolicy;
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────────────────────────────
    //  注册方法
    // ─────────────────────────────────────────────

    /**
     * 注册 Java 原生工具（Layer 3，最高优先级）。
     *
     * @param tool Java 原生工具
     */
    public void registerBuiltinTool(ToolContract tool) {
        if (registerWithPriority(tool, ToolLayer.JAVA_NATIVE, "builtin")) {
            guardrailPolicy.addAllowedTools(List.of(tool.id()));
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsRegistered(
                    List.of(tool.id()), ToolLayer.JAVA_NATIVE, "builtin"));
        }
    }

    /**
     * 批量注册 YAML 声明式工具（Layer 2）。
     *
     * @param yamlTools YAML 工具列表
     */
    public void registerYamlTools(List<ToolContract> yamlTools) {
        List<String> registeredIds = new ArrayList<>();
        for (ToolContract tool : yamlTools) {
            if (registerWithPriority(tool, ToolLayer.YAML_DECLARATIVE, "yaml")) {
                yamlToolIds.put(tool.id(), Boolean.TRUE);
                registeredIds.add(tool.id());
            }
        }
        if (!registeredIds.isEmpty()) {
            guardrailPolicy.addAllowedTools(registeredIds);
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsRegistered(
                    List.copyOf(registeredIds), ToolLayer.YAML_DECLARATIVE, "yaml"));
            log.info("YAML 工具注册完成: count={}", registeredIds.size());
        }
    }

    /**
     * 批量注册 MCP 外部工具（Layer 1，最低优先级）。
     *
     * @param serverName MCP 服务器名称
     * @param mcpTools MCP 工具列表
     */
    public void registerMcpTools(String serverName, List<ToolContract> mcpTools) {
        List<String> registeredIds = new ArrayList<>();
        for (ToolContract tool : mcpTools) {
            if (registerWithPriority(tool, ToolLayer.MCP_EXTERNAL, serverName)) {
                registeredIds.add(tool.id());
            }
        }
        serverToolIndex.put(serverName, List.copyOf(registeredIds));
        if (!registeredIds.isEmpty()) {
            guardrailPolicy.addAllowedTools(registeredIds);
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsRegistered(
                    List.copyOf(registeredIds), ToolLayer.MCP_EXTERNAL, serverName));
            log.info("MCP 工具注册完成: server={}, count={}", serverName, registeredIds.size());
        }
    }

    /** 带优先级的工具注册。 */
    private boolean registerWithPriority(ToolContract tool, ToolLayer layer, String source) {
        String id = tool.id();
        ToolLayer existingLayer = toolLayers.get(id);

        if (existingLayer == null) {
            tools.put(id, tool);
            toolLayers.put(id, layer);
            log.debug("工具注册成功: id={}, layer={}, source={}", id, layer, source);
            return true;
        }

        if (layer.overrides(existingLayer)) {
            tools.put(id, tool);
            toolLayers.put(id, layer);
            eventPublisher.publishEvent(new ToolConflictDetected(
                    id, existingLayer, layer, "高层覆盖低层"));
            log.info("工具覆盖注册: id={}, {} -> {}", id, existingLayer, layer);
            return true;
        }

        eventPublisher.publishEvent(new ToolConflictDetected(
                id, existingLayer, layer, "同层或低层冲突，跳过"));
        log.warn("工具 ID 冲突，跳过注册: id={}, existing={}, new={}", id, existingLayer, layer);
        return false;
    }

    // ─────────────────────────────────────────────
    //  注销方法
    // ─────────────────────────────────────────────

    /**
     * 注销指定 MCP Server 的所有工具。
     *
     * @param serverName MCP 服务器名称
     */
    public void unregisterMcpTools(String serverName) {
        List<String> toolIds = serverToolIndex.remove(serverName);
        if (toolIds != null && !toolIds.isEmpty()) {
            toolIds.forEach(id -> {
                tools.remove(id);
                toolLayers.remove(id);
            });
            guardrailPolicy.removeAllowedTools(toolIds);
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsUnregistered(
                    List.copyOf(toolIds), serverName));
            log.info("MCP 工具注销完成: server={}, count={}", serverName, toolIds.size());
        }
    }

    /** 注销所有 YAML 工具（热加载前调用）。 */
    public void unregisterYamlTools() {
        List<String> toolIds = new ArrayList<>(yamlToolIds.keySet());
        if (!toolIds.isEmpty()) {
            toolIds.forEach(id -> {
                tools.remove(id);
                toolLayers.remove(id);
            });
            yamlToolIds.clear();
            guardrailPolicy.removeAllowedTools(toolIds);
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsUnregistered(
                    List.copyOf(toolIds), "yaml-reload"));
            log.info("YAML 工具注销完成: count={}", toolIds.size());
        }
    }

    // ─────────────────────────────────────────────
    //  查询方法
    // ─────────────────────────────────────────────

    /** 按 ID 解析工具。 */
    public Optional<ToolContract> resolve(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    /**
     * 获取指定 MCP Server 注册的工具列表。
     *
     * @param serverName MCP 服务器名称
     * @return 该 Server 注册的工具列表，Server 不存在时返回空列表
     */
    public List<ToolContract> getToolsByServer(String serverName) {
        List<String> toolIds = serverToolIndex.getOrDefault(serverName, List.of());
        return toolIds.stream()
                .map(tools::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 获取所有可用工具（不可变列表）。 */
    public List<ToolContract> getAllTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 获取工具快照（缓存版本，高频调用优化）。
     *
     * <p>快照在工具注册/注销时失效并重建。</p>
     */
    public List<ToolContract> getToolSnapshot() {
        List<ToolContract> snapshot = cachedSnapshot;
        if (snapshot.isEmpty() && !tools.isEmpty()) {
            snapshotLock.writeLock().lock();
            try {
                snapshot = cachedSnapshot;
                if (snapshot.isEmpty() && !tools.isEmpty()) {
                    snapshot = List.copyOf(tools.values());
                    cachedSnapshot = snapshot;
                }
            } finally {
                snapshotLock.writeLock().unlock();
            }
        }
        return snapshot;
    }

    /** 获取各层次工具数量统计。 */
    public Map<ToolLayer, Integer> getToolCountByLayer() {
        var counts = new EnumMap<ToolLayer, Integer>(ToolLayer.class);
        for (ToolLayer layer : ToolLayer.values()) {
            counts.put(layer, 0);
        }
        toolLayers.values().forEach(layer ->
                counts.merge(layer, 1, Integer::sum));
        return Map.copyOf(counts);
    }

    /** 使快照缓存失效。 */
    private void invalidateSnapshot() {
        cachedSnapshot = List.of();
    }
}
