package com.lifepilot.mcp.registry;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.cache.McpToolManifestCache;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpServerUnavailableException;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MCP Server 注册中心 — 管理所有 MCP Server 连接的完整生命周期。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>懒连接</b>：启动时仅注册配置，首次工具调用时按需连接</li>
 *   <li><b>工具缓存</b>：连接后缓存工具清单，下次启动时从缓存加载工具桩，LLM 无需连接即可感知工具</li>
 *   <li><b>单一维护 tick</b>：每个 Server 一个 {@link ScheduledFuture}，合并健康检查和空闲超时检测</li>
 *   <li><b>per-server 锁</b>：防止同一 Server 并发连接/断开竞态</li>
 *   <li><b>优雅关闭</b>：实现 {@link DisposableBean}，Spring 关闭时自动清理</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpServerRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistry.class);

    /** 每个 Server 最多保留的连接日志条数。 */
    private static final int MAX_LOG_ENTRIES = 50;

    private final ConcurrentHashMap<String, McpServerEntry> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<McpConnectionLogEntry>> connectionLogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> serverLocks = new ConcurrentHashMap<>();

    private final McpToolAdapter toolAdapter;
    private final DynamicToolRegistry toolRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final SharedScheduler sharedScheduler;
    private final McpToolManifestCache toolCache;

    public McpServerRegistry(
            McpToolAdapter toolAdapter,
            DynamicToolRegistry toolRegistry,
            ApplicationEventPublisher eventPublisher,
            SharedScheduler sharedScheduler,
            McpToolManifestCache toolCache) {
        this.toolAdapter = toolAdapter;
        this.toolRegistry = toolRegistry;
        this.eventPublisher = eventPublisher;
        this.sharedScheduler = sharedScheduler;
        this.toolCache = toolCache;
    }

    // ─────────────────────────────────────────────
    //  注册与连接
    // ─────────────────────────────────────────────

    /**
     * 批量注册 MCP Server 配置（仅注册，不连接）。
     *
     * <p>对每个 Server，尝试从本地缓存加载工具清单并注册为工具桩。
     * 这样 LLM 在 Server 未连接时也能感知可用工具，
     * 首次工具调用时由 {@link #ensureConnected} 触发懒连接。</p>
     *
     * @param configs 服务器配置列表
     */
    public void initializeAll(List<McpServerConfig> configs) {
        log.info("MCP Server 批量注册: count={}", configs.size());
        int cachedToolCount = 0;
        for (McpServerConfig config : configs) {
            servers.put(config.name(), McpServerEntry.initial(config));
            serverLocks.putIfAbsent(config.name(), new ReentrantLock());

            // 从缓存加载工具桩
            List<McpToolSchema> cached = toolCache.load(config.name());
            if (!cached.isEmpty()) {
                List<ToolContract> tools = toolAdapter.toToolContracts(config.name(), cached, null);
                toolRegistry.registerMcpTools(config.name(), tools);
                cachedToolCount += tools.size();
                log.debug("从缓存加载工具桩: server={}, tools={}", config.name(), tools.size());
            }
        }
        if (cachedToolCount > 0) {
            log.info("MCP 工具缓存加载完成: {} 个工具桩已注册（Server 未连接，首次调用时懒连接）",
                    cachedToolCount);
        }
    }

    /**
     * 对无工具缓存且 autoConnect=true 的 Server 启动后台发现。
     *
     * <p>仅对显式启用自动连接的 Server 执行后台发现，
     * autoConnect=false 的 Server 需要用户从前端手动连接或等待工具调用触发懒连接。</p>
     *
     * <p>后台连接 → 发现工具 → 写入缓存 → 注册工具 → 空闲超时后自然断开。
     * 发现失败不影响应用启动。</p>
     */
    public void discoverUncached() {
        var uncached = servers.entrySet().stream()
                .filter(e -> e.getValue().state() == McpServerState.DISCONNECTED)
                .filter(e -> e.getValue().config().autoConnect())
                .filter(e -> toolCache.load(e.getKey()).isEmpty())
                .map(e -> e.getValue().config())
                .toList();

        if (uncached.isEmpty()) return;

        log.info("MCP 后台发现: {} 个 Server 无工具缓存，启动后台连接", uncached.size());
        for (McpServerConfig config : uncached) {
            connectServer(config).whenComplete((_, ex) -> {
                if (ex != null) {
                    log.debug("MCP 后台发现失败（用户可从前端手动连接）: server={}, error={}",
                            config.name(), ex.getMessage());
                }
            });
        }
    }

    /**
     * 连接单个 MCP Server。
     *
     * <p>线程安全：使用 per-server 锁防止并发连接。
     * 返回 {@link CompletableFuture}，支持懒连接场景同步等待。</p>
     *
     * @param config 服务器配置
     * @return 连接完成的 Future
     */
    public CompletableFuture<Void> connectServer(McpServerConfig config) {
        String name = config.name();
        servers.putIfAbsent(name, McpServerEntry.initial(config));
        serverLocks.putIfAbsent(name, new ReentrantLock());

        return CompletableFuture.runAsync(() -> {
            ReentrantLock lock = serverLocks.get(name);
            lock.lock();
            try {
                // 已连接则跳过
                var entry = servers.get(name);
                if (entry != null && entry.state().isAvailable()) {
                    log.debug("MCP Server 已连接，跳过: name={}", name);
                    return;
                }

                log.info("MCP Server 连接开始: name={}, transport={}", name, config.transport());

                // 取消旧的维护任务
                cancelMaintenance(name);

                updateState(name, McpServerState.CONNECTING);

                var client = createClient(config);
                updateEntry(name, e -> e.toBuilder().client(client).build());

                updateState(name, McpServerState.INITIALIZING);
                client.initialize().join();

                var schemas = client.listTools().join();
                List<ToolContract> tools = toolAdapter.toToolContracts(name, schemas, client);
                toolRegistry.registerMcpTools(name, tools);

                // 刷新工具缓存（下次启动可直接加载工具桩）
                toolCache.save(name, schemas);

                updateEntry(name, e -> e.toBuilder()
                        .state(McpServerState.CONNECTED)
                        .serverInfo(client.getServerInfo())
                        .connectedSince(Instant.now())
                        .lastToolCall(Instant.now())
                        .reconnectAttempts(0)
                        .lastError(null)
                        .build());

                log.info("MCP Server 连接成功: name={}, tools={}", name, tools.size());

                // 启动维护任务（合并健康检查和空闲检测）
                scheduleMaintenance(name, config);

            } catch (Exception e) {
                log.error("MCP Server 连接失败: name={}, error={}", name, e.getMessage());
                updateEntry(name, entry -> entry.toBuilder()
                        .state(McpServerState.DISCONNECTED)
                        .lastError(e.getMessage())
                        .build());
                if (config.reconnect()) {
                    scheduleReconnect(name, config);
                }
            } finally {
                lock.unlock();
            }
        }, command -> Thread.ofVirtual().name("mcp-connect-" + name).start(command));
    }

    /**
     * 断开指定 MCP Server。
     *
     * @param serverName 服务器名称
     */
    public void disconnectServer(String serverName) {
        if (!servers.containsKey(serverName)) return;

        ReentrantLock lock = serverLocks.get(serverName);
        if (lock != null) lock.lock();
        try {
            // 获锁后重新读取最新状态
            var entry = servers.get(serverName);
            if (entry == null || entry.state() == McpServerState.DISCONNECTED) return;

            cancelMaintenance(serverName);

            updateState(serverName, McpServerState.DISCONNECTING);

            if (entry.client() != null) {
                try {
                    entry.client().shutdown().join();
                } catch (Exception e) {
                    log.warn("MCP Server 关闭异常: name={}, error={}", serverName, e.getMessage());
                }
            }

            toolRegistry.unregisterMcpTools(serverName);

            // 从缓存重新注册工具桩，断开连接不影响 LLM 的工具可见性
            List<McpToolSchema> cached = toolCache.load(serverName);
            if (!cached.isEmpty()) {
                List<ToolContract> stubs = toolAdapter.toToolContracts(serverName, cached, null);
                toolRegistry.registerMcpTools(serverName, stubs);
            }

            updateEntry(serverName, e -> e.toBuilder()
                    .state(McpServerState.DISCONNECTED)
                    .client(null)
                    .connectedSince(null)
                    .maintenanceFuture(null)
                    .build());

            log.info("MCP Server 已断开: name={}", serverName);
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    // ─────────────────────────────────────────────
    //  懒连接与查询
    // ─────────────────────────────────────────────

    /**
     * 确保指定 Server 已连接 — 懒连接的核心入口。
     *
     * <p>快速路径：已连接则直接返回 McpClient。
     * 慢路径：同步连接（阻塞至多 config.timeout()），成功后返回。</p>
     *
     * @param serverName 服务器名称
     * @return McpClient 实例
     * @throws McpServerUnavailableException 服务器未注册、连接失败或超时
     */
    public McpClient ensureConnected(String serverName) {
        var entry = servers.get(serverName);
        if (entry == null) {
            throw new McpServerUnavailableException(serverName, "未注册");
        }

        // 快速路径：二次读取最新快照，避免 TOCTOU 竞态
        var latest = servers.get(serverName);
        if (latest != null && latest.state().isAvailable() && latest.client() != null) {
            return latest.client();
        }

        // 慢路径：懒连接
        log.info("MCP Server 懒连接触发: name={}", serverName);
        try {
            connectServer(entry.config())
                    .orTimeout(entry.config().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw new McpServerUnavailableException(serverName,
                    "懒连接失败: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            throw new McpServerUnavailableException(serverName, "懒连接失败: " + e.getMessage());
        }

        var connected = servers.get(serverName);
        if (connected == null || !connected.state().isAvailable() || connected.client() == null) {
            throw new McpServerUnavailableException(serverName, "连接后仍不可用");
        }
        return connected.client();
    }

    /**
     * 记录工具调用时间 — 由 McpToolExecutor 在每次调用时调用，用于空闲超时检测。
     *
     * @param serverName 服务器名称
     */
    public void recordToolCall(String serverName) {
        updateEntry(serverName, e -> e.toBuilder()
                .lastToolCall(Instant.now())
                .build());
    }

    /** 获取指定 Server 的客户端（仅在已连接状态下返回）。 */
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

    /**
     * 获取指定 Server 的连接日志。
     *
     * @param serverName 服务器名称
     * @return 连接日志列表（按时间倒序），Server 不存在时返回空列表
     */
    public List<McpConnectionLogEntry> getConnectionLogs(String serverName) {
        var logs = connectionLogs.get(serverName);
        if (logs == null) {
            return List.of();
        }
        return List.copyOf(logs);
    }

    // ─────────────────────────────────────────────
    //  维护 tick（合并健康检查 + 空闲超时检测）
    // ─────────────────────────────────────────────

    /**
     * 调度统一维护任务 — 合并健康检查和空闲超时检测。
     *
     * <p>单一 {@link ScheduledFuture} 存储在 {@link McpServerEntry#maintenanceFuture()} 中，
     * 确保 disconnect/reconnect 时可精确取消，从根本上消除任务泄漏和累积问题。</p>
     */
    private void scheduleMaintenance(String serverName, McpServerConfig config) {
        cancelMaintenance(serverName);

        long intervalMs = config.healthCheckInterval().toMillis();

        ScheduledFuture<?> future = sharedScheduler.heartbeat().scheduleAtFixedRate(() -> {
            var entry = servers.get(serverName);
            if (entry == null || !entry.state().isAvailable()) return;

            // 1. 空闲超时检测
            if (entry.lastToolCall() != null) {
                Duration idle = Duration.between(entry.lastToolCall(), Instant.now());
                if (idle.compareTo(config.idleTimeout()) > 0) {
                    log.info("MCP Server 空闲超时，自动断开: name={}, idle={}s",
                            serverName, idle.toSeconds());
                    CompletableFuture.runAsync(
                            () -> disconnectServer(serverName),
                            cmd -> Thread.ofVirtual()
                                    .name("mcp-idle-disconnect-" + serverName).start(cmd));
                    return;
                }
            }

            // 2. 健康检查
            updateState(serverName, McpServerState.HEALTH_CHECK);
            try {
                if (entry.client() != null) {
                    entry.client().ping().join();
                }
                updateEntry(serverName, e -> e.toBuilder()
                        .state(McpServerState.CONNECTED)
                        .lastHealthCheck(Instant.now())
                        .build());
            } catch (Exception e) {
                log.warn("MCP Server 健康检查失败: name={}, error={}", serverName, e.getMessage());
                // 先取消维护任务，再触发重连，防止反馈循环
                cancelMaintenance(serverName);
                updateEntry(serverName, en -> en.toBuilder()
                        .state(McpServerState.RECONNECTING)
                        .lastError(e.getMessage())
                        .build());
                scheduleReconnect(serverName, entry.config());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // 保存 ScheduledFuture，确保可精确取消
        updateEntry(serverName, e -> e.toBuilder()
                .maintenanceFuture(future)
                .build());
    }

    /** 取消指定 Server 的维护任务。 */
    private void cancelMaintenance(String serverName) {
        var entry = servers.get(serverName);
        if (entry != null && entry.maintenanceFuture() != null) {
            entry.maintenanceFuture().cancel(false);
            log.debug("维护任务已取消: name={}", serverName);
        }
    }

    // ─────────────────────────────────────────────
    //  自动重连
    // ─────────────────────────────────────────────

    /** 指数退避重连（500ms × 2^n，上限 5s）。 */
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

        sharedScheduler.heartbeat().schedule(() -> {
            // 检查是否已被手动断开或已连接
            var current = servers.get(serverName);
            if (current == null
                    || current.state() == McpServerState.DISCONNECTED
                    || current.state().isAvailable()) {
                log.debug("重连跳过（状态已变化）: name={}, state={}",
                        serverName, current != null ? current.state() : "null");
                return;
            }
            connectServer(config);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ─────────────────────────────────────────────
    //  优雅关闭
    // ─────────────────────────────────────────────

    /**
     * 优雅关闭 — 取消所有定时任务，断开所有连接。
     *
     * <p>由 Spring 容器关闭时自动调用（{@link DisposableBean}）。</p>
     */
    public void shutdown() {
        log.info("MCP Server 注册中心关闭开始: servers={}", servers.size());

        servers.forEach((name, entry) -> cancelMaintenance(name));

        servers.forEach((name, entry) -> {
            if (entry.state().isAvailable() && entry.client() != null) {
                try {
                    entry.client().shutdown().join();
                    toolRegistry.unregisterMcpTools(name);
                    log.debug("MCP Server 已关闭: name={}", name);
                } catch (Exception e) {
                    log.warn("MCP Server 关闭异常: name={}, error={}", name, e.getMessage());
                }
            }
        });

        servers.clear();
        connectionLogs.clear();
        serverLocks.clear();

        log.info("MCP Server 注册中心关闭完成");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /** 创建 McpClient（可被子类覆盖用于测试）。 */
    McpClient createClient(McpServerConfig config) {
        return new McpClient(config);
    }

    /** 更新 Server 状态。 */
    private void updateState(String serverName, McpServerState newState) {
        servers.computeIfPresent(serverName, (k, entry) -> {
            var oldState = entry.state();
            var updated = entry.toBuilder().state(newState).build();
            if (oldState != newState) {
                publishStateChangedEvent(serverName, oldState, newState, null);
            }
            return updated;
        });
    }

    /** 更新 Server 条目。 */
    private void updateEntry(String serverName,
                             java.util.function.UnaryOperator<McpServerEntry> updater) {
        servers.computeIfPresent(serverName, (k, entry) -> {
            var updated = updater.apply(entry);
            if (entry.state() != updated.state()) {
                publishStateChangedEvent(serverName, entry.state(), updated.state(), updated.lastError());
            }
            return updated;
        });
    }

    /** 安全发布状态变化事件，同时记录连接日志，异常不影响主流程。 */
    private void publishStateChangedEvent(String serverName, McpServerState oldState,
                                          McpServerState newState, @Nullable String error) {
        try {
            var now = Instant.now();
            eventPublisher.publishEvent(new McpServerStateChangedEvent(
                    serverName, oldState, newState, now, error));
            recordConnectionLog(serverName, oldState, newState, now, error);
        } catch (Exception e) {
            log.warn("MCP Server 状态变化事件发布失败: server={}, {}→{}, error={}",
                    serverName, oldState, newState, e.getMessage());
        }
    }

    /**
     * 记录连接日志条目（内存环形缓冲，每个 Server 最多 {@value MAX_LOG_ENTRIES} 条）。
     *
     * <p>使用 synchronized 保证 addFirst + 裁剪的原子性，
     * 避免 ConcurrentLinkedDeque.size() 的 O(n) 遍历和竞态问题。</p>
     */
    private void recordConnectionLog(String serverName, McpServerState oldState,
                                     McpServerState newState, Instant timestamp,
                                     @Nullable String error) {
        String eventType = mapToEventType(newState, error);
        if (eventType == null) return;

        String description = buildLogDescription(oldState, newState, error);
        var logEntry = new McpConnectionLogEntry(timestamp, eventType, description);

        var logs = connectionLogs.computeIfAbsent(serverName,
                k -> new ConcurrentLinkedDeque<>());

        synchronized (logs) {
            logs.addFirst(logEntry);
            while (logs.size() > MAX_LOG_ENTRIES) {
                logs.removeLast();
            }
        }
    }

    /**
     * 将状态转换映射为前端期望的事件类型。
     *
     * @return 事件类型字符串，中间状态返回 null（不记录）
     */
    @Nullable
    private String mapToEventType(McpServerState newState, @Nullable String error) {
        if (error != null) return "ERROR";
        return switch (newState) {
            case CONNECTED -> "CONNECT";
            case DISCONNECTED -> "DISCONNECT";
            case RECONNECTING -> "RECONNECT";
            default -> null;
        };
    }

    /** 构建日志描述文本。 */
    private String buildLogDescription(McpServerState oldState, McpServerState newState,
                                       @Nullable String error) {
        if (error != null) {
            return "状态 %s→%s 失败: %s".formatted(oldState, newState, error);
        }
        return "状态变化: %s→%s".formatted(oldState, newState);
    }
}
