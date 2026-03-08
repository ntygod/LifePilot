package com.lifepilot.mcp.registry;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * MCP Server 注册中心 — 管理所有 MCP Server 连接的完整生命周期。
 *
 * <p>职责：创建、初始化、健康监控、自动重连和关闭。
 * 与 {@link DynamicToolRegistry}（工具层）和 {@link McpToolAdapter}（适配层）协作。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistry.class);

    private final ConcurrentHashMap<String, McpServerEntry> servers = new ConcurrentHashMap<>();

    private final McpToolAdapter toolAdapter;
    private final DynamicToolRegistry toolRegistry;
    private final ScheduledExecutorService scheduler;

    public McpServerRegistry(
            McpToolAdapter toolAdapter,
            DynamicToolRegistry toolRegistry) {
        this.toolAdapter = toolAdapter;
        this.toolRegistry = toolRegistry;
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    /**
     * 初始化所有配置的 MCP Server。
     *
     * @param configs 服务器配置列表
     */
    public void initializeAll(List<McpServerConfig> configs) {
        log.info("MCP Server 批量初始化: count={}", configs.size());
        for (McpServerConfig config : configs) {
            servers.put(config.name(), McpServerEntry.initial(config));
            if (config.autoConnect()) {
                connectServer(config);
            }
        }
    }

    /**
     * 连接单个 MCP Server。
     *
     * <p>完整流程：创建 McpClient → 初始化 → 工具发现 → 适配 → 注册到 DynamicToolRegistry。</p>
     *
     * @param config 服务器配置
     */
    public void connectServer(McpServerConfig config) {
        String name = config.name();
        log.info("MCP Server 连接开始: name={}, transport={}", name, config.transport());

        // 确保 entry 存在（外部直接调用 connectServer 时可能未经过 initializeAll）
        servers.putIfAbsent(name, McpServerEntry.initial(config));

        updateState(name, McpServerState.CONNECTING);

        CompletableFuture.runAsync(() -> {
            try {
                // 创建客户端
                var client = createClient(config);
                updateEntry(name, entry -> entry.toBuilder().client(client).build());

                // 初始化
                updateState(name, McpServerState.INITIALIZING);
                client.initialize().join();

                // 工具发现与注册
                var schemas = client.listTools().join();
                List<ToolContract> tools = toolAdapter.toToolContracts(name, schemas, client);
                toolRegistry.registerMcpTools(name, tools);

                // 更新状态为已连接
                updateEntry(name, entry -> entry.toBuilder()
                        .state(McpServerState.CONNECTED)
                        .serverInfo(client.getServerInfo())
                        .connectedSince(Instant.now())
                        .reconnectAttempts(0)
                        .lastError(null)
                        .build());

                log.info("MCP Server 连接成功: name={}, tools={}", name, tools.size());

                // 启动健康检查
                if (config.reconnect()) {
                    scheduleHealthCheck(name, config.healthCheckInterval());
                }

            } catch (Exception e) {
                log.error("MCP Server 连接失败: name={}, error={}", name, e.getMessage());
                updateEntry(name, entry -> entry.toBuilder()
                        .state(McpServerState.DISCONNECTED)
                        .lastError(e.getMessage())
                        .build());

                // 尝试重连
                if (config.reconnect()) {
                    scheduleReconnect(name, config);
                }
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 断开指定 MCP Server。
     *
     * @param serverName 服务器名称
     */
    public void disconnectServer(String serverName) {
        var entry = servers.get(serverName);
        if (entry == null) return;

        updateState(serverName, McpServerState.DISCONNECTING);

        if (entry.client() != null) {
            try {
                entry.client().shutdown().join();
            } catch (Exception e) {
                log.warn("MCP Server 关闭异常: name={}, error={}", serverName, e.getMessage());
            }
        }

        // 注销工具
        toolRegistry.unregisterMcpTools(serverName);

        updateEntry(serverName, e -> e.toBuilder()
                .state(McpServerState.DISCONNECTED)
                .client(null)
                .connectedSince(null)
                .build());

        log.info("MCP Server 已断开: name={}", serverName);
    }

    /** 获取指定 Server 的客户端。 */
    public Optional<McpClient> getClient(String serverName) {
        var entry = servers.get(serverName);
        if (entry != null && entry.state().isAvailable() && entry.client() != null) {
            return Optional.of(entry.client());
        }
        return Optional.empty();
    }

    /** 获取所有 Server 条目。 */
    public List<McpServerEntry> listServers() {
        return List.copyOf(servers.values());
    }

    /** 获取指定 Server 条目。 */
    public Optional<McpServerEntry> getServer(String serverName) {
        return Optional.ofNullable(servers.get(serverName));
    }

    // ─────────────────────────────────────────────
    //  健康检查与自动重连
    // ─────────────────────────────────────────────

    /** 定期健康检查（使用 tools/list 作为 ping）。 */
    void scheduleHealthCheck(String serverName, Duration interval) {
        scheduler.scheduleAtFixedRate(() -> {
            var entry = servers.get(serverName);
            if (entry == null || !entry.state().isAvailable()) return;

            updateState(serverName, McpServerState.HEALTH_CHECK);

            try {
                if (entry.client() != null) {
                    entry.client().listTools().join();
                }
                updateEntry(serverName, e -> e.toBuilder()
                        .state(McpServerState.CONNECTED)
                        .lastHealthCheck(Instant.now())
                        .build());
            } catch (Exception e) {
                log.warn("MCP Server 健康检查失败: name={}, error={}", serverName, e.getMessage());
                updateEntry(serverName, en -> en.toBuilder()
                        .state(McpServerState.RECONNECTING)
                        .lastError(e.getMessage())
                        .build());
                scheduleReconnect(serverName, entry.config());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 指数退避重连（500ms × 2^(n-1)，上限 5s）。 */
    void scheduleReconnect(String serverName, McpServerConfig config) {
        var entry = servers.get(serverName);
        if (entry == null) return;

        int attempts = entry.reconnectAttempts();
        if (attempts >= config.maxReconnectAttempts()) {
            log.error("MCP Server 重连次数耗尽: name={}, attempts={}", serverName, attempts);
            updateEntry(serverName, e -> e.toBuilder()
                    .state(McpServerState.DISCONNECTED)
                    .build());
            return;
        }

        // 指数退避：500ms × 2^n，上限 5s
        long delayMs = Math.min(
                config.reconnectDelay().toMillis() * (1L << attempts),
                5000L
        );

        updateEntry(serverName, e -> e.toBuilder()
                .state(McpServerState.RECONNECTING)
                .reconnectAttempts(attempts + 1)
                .build());

        log.info("MCP Server 重连调度: name={}, attempt={}, delay={}ms",
                serverName, attempts + 1, delayMs);

        scheduler.schedule(() -> connectServer(config), delayMs, TimeUnit.MILLISECONDS);
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /** 创建 McpClient（可被子类覆盖用于测试）。 */
    McpClient createClient(McpServerConfig config) {
        return new McpClient(config);
    }

    /** 更新 Server 状态。 */
    private void updateState(String serverName, McpServerState state) {
        servers.computeIfPresent(serverName, (k, entry) ->
                entry.toBuilder().state(state).build());
    }

    /** 更新 Server 条目。 */
    private void updateEntry(String serverName,
                             java.util.function.UnaryOperator<McpServerEntry> updater) {
        servers.computeIfPresent(serverName, (k, entry) -> updater.apply(entry));
    }
}
