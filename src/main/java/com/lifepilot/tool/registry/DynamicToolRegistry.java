package com.lifepilot.tool.registry;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.event.ToolRegistryEvent.*;
import com.lifepilot.tool.model.ToolCategory;
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
 * <p>统一管理两层工具（Java 原生 + MCP 外部），对 AgentLoop 完全透明。
 * 线程安全，支持运行时动态注册/注销。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class DynamicToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolRegistry.class);
    private static final Comparator<ToolContract> SNAPSHOT_ORDER =
            Comparator.comparing(ToolContract::id);

    /** 工具存储：toolId → ToolContract。 */
    private final ConcurrentHashMap<String, ToolContract> tools = new ConcurrentHashMap<>();

    /** 工具层次索引：toolId → ToolLayer。 */
    private final ConcurrentHashMap<String, ToolLayer> toolLayers = new ConcurrentHashMap<>();

    /** MCP Server 工具索引：serverName → 工具 ID 列表。 */
    private final ConcurrentHashMap<String, List<String>> serverToolIndex = new ConcurrentHashMap<>();

    /** 分组索引：category → 工具 ID 集合。 */
    private final ConcurrentHashMap<ToolCategory, Set<String>> categoryIndex = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;
    /** 启动期命名校验器；为 null 时跳过校验（测试场景或显式关闭）。 */
    @org.springframework.lang.Nullable
    private final com.lifepilot.tool.validation.ToolValidator validator;

    /** 快照缓存锁。 */
    private final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();
    private volatile List<ToolContract> cachedSnapshot = List.of();

    public DynamicToolRegistry(ApplicationEventPublisher eventPublisher) {
        this(eventPublisher, null);
    }

    public DynamicToolRegistry(ApplicationEventPublisher eventPublisher,
                               @org.springframework.lang.Nullable
                               com.lifepilot.tool.validation.ToolValidator validator) {
        this.eventPublisher = eventPublisher;
        this.validator = validator;
    }

    // ─────────────────────────────────────────────
    //  注册方法
    // ─────────────────────────────────────────────

    /**
     * 注册 Java 原生工具（最高优先级）。
     *
     * @param tool Java 原生工具
     */
    public void registerBuiltinTool(ToolContract tool) {
        if (validator != null) {
            try {
                validator.validate(tool);
            } catch (IllegalStateException e) {
                log.error("拒绝注册不符合命名规范的工具: toolId={}, reason={}", tool.id(), e.getMessage());
                throw e;
            }
        }
        if (registerWithPriority(tool, ToolLayer.JAVA_NATIVE, "builtin")) {
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsRegistered(
                    List.of(tool.id()), ToolLayer.JAVA_NATIVE, "builtin"));
        }
    }

    /**
     * 批量注册 MCP 外部工具（最低优先级）。
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
            categoryIndex.computeIfAbsent(tool.category(), k -> ConcurrentHashMap.newKeySet())
                    .add(id);
            log.debug("工具注册成功: id={}, layer={}, source={}", id, layer, source);
            return true;
        }

        if (layer.overrides(existingLayer)) {
            // 覆盖注册时，先清理旧工具的 category 索引（category 可能不同）
            ToolContract oldTool = tools.get(id);
            if (oldTool != null) {
                Set<String> oldCategorySet = categoryIndex.get(oldTool.category());
                if (oldCategorySet != null) {
                    oldCategorySet.remove(id);
                }
            }
            tools.put(id, tool);
            toolLayers.put(id, layer);
            categoryIndex.computeIfAbsent(tool.category(), k -> ConcurrentHashMap.newKeySet())
                    .add(id);
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
     * 注销指定 ID 的 BuiltinTool。
     *
     * @param toolId 工具 ID
     * @return 是否成功注销
     */
    public boolean unregisterBuiltinTool(String toolId) {
        return unregisterToolInternal(toolId, "builtin", "BuiltinTool 注销完成");
    }

    /**
     * 注销任意层级的工具。
     *
     * <p>用于评测或临时覆盖场景下的工具替换，不区分 Builtin / MCP。</p>
     *
     * @param toolId 工具 ID
     * @return 是否成功注销
     */
    public boolean unregisterTool(String toolId) {
        return unregisterToolInternal(toolId, "dynamic", "工具注销完成");
    }

    /**
     * 注销指定 MCP Server 的所有工具。
     *
     * @param serverName MCP 服务器名称
     */
    public void unregisterMcpTools(String serverName) {
        List<String> toolIds = serverToolIndex.remove(serverName);
        if (toolIds != null && !toolIds.isEmpty()) {
            toolIds.forEach(id -> {
                ToolContract removed = tools.remove(id);
                if (removed != null) {
                    removeCategoryIndex(removed.category(), id);
                }
                toolLayers.remove(id);
            });
            invalidateSnapshot();
            eventPublisher.publishEvent(new ToolsUnregistered(
                    List.copyOf(toolIds), serverName));
            log.info("MCP 工具注销完成: server={}, count={}", serverName, toolIds.size());
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
     * 查询工具所属层级。
     *
     * @param toolId 工具 ID
     * @return 工具层级，不存在时为空
     */
    public Optional<ToolLayer> getToolLayer(String toolId) {
        return Optional.ofNullable(toolLayers.get(toolId));
    }

    /**
     * 获取所有已注册的 MCP Server 名称。
     *
     * @return 不可变的 Server 名称集合
     */
    public Set<String> getRegisteredServerNames() {
        return Set.copyOf(serverToolIndex.keySet());
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
        return buildStableSnapshot();
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
                    snapshot = buildStableSnapshot();
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

    /**
     * 按分组查询工具 ID 集合。
     *
     * @param category 工具元能力分组
     * @return 不可变的工具 ID 集合
     */
    public Set<String> getToolIdsByCategory(ToolCategory category) {
        return Set.copyOf(categoryIndex.getOrDefault(category, Set.of()));
    }

    /**
     * 获取所有分组及其工具数量。
     *
     * @return 不可变的分组 → 工具数量映射
     */
    public Map<ToolCategory, Integer> getToolCountByCategory() {
        var counts = new EnumMap<ToolCategory, Integer>(ToolCategory.class);
        categoryIndex.forEach((cat, ids) -> counts.put(cat, ids.size()));
        return Map.copyOf(counts);
    }

    /** 从分组索引中移除工具 ID。 */
    private void removeCategoryIndex(ToolCategory category, String toolId) {
        Set<String> ids = categoryIndex.get(category);
        if (ids != null) {
            ids.remove(toolId);
        }
    }

    private boolean unregisterToolInternal(String toolId, String source, String logPrefix) {
        ToolContract removed = tools.remove(toolId);
        if (removed == null) {
            return false;
        }
        toolLayers.remove(toolId);
        removeCategoryIndex(removed.category(), toolId);
        removeFromServerToolIndex(toolId);
        invalidateSnapshot();
        eventPublisher.publishEvent(new ToolsUnregistered(
                List.of(toolId), source));
        log.info("{}: id={}", logPrefix, toolId);
        return true;
    }

    private void removeFromServerToolIndex(String toolId) {
        serverToolIndex.replaceAll((server, ids) -> ids.stream()
                .filter(id -> !Objects.equals(id, toolId))
                .toList());
        serverToolIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** 使快照缓存失效。 */
    private void invalidateSnapshot() {
        cachedSnapshot = List.of();
    }

    private List<ToolContract> buildStableSnapshot() {
        return tools.values().stream()
                .sorted(SNAPSHOT_ORDER)
                .toList();
    }
}
