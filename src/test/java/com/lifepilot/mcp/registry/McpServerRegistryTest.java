package com.lifepilot.mcp.registry;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.cache.McpToolManifestCache;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpServerUnavailableException;
import com.lifepilot.mcp.model.McpServerInfo;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.lifepilot.config.threadpool.SharedScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * McpServerRegistry 单元测试。
 *
 * <p>通过子类覆盖 createClient() 注入 Mock McpClient，
 * 验证注册→懒连接→工具调用记录→断开→优雅关闭全流程。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ExtendWith(MockitoExtension.class)
class McpServerRegistryTest {

    @Mock
    private McpToolAdapter mockToolAdapter;

    @Mock
    private DynamicToolRegistry mockToolRegistry;

    @Mock
    private ApplicationEventPublisher mockEventPublisher;

    @Mock
    private McpClient mockClient;

    @Mock
    private SharedScheduler mockScheduler;

    @Mock
    private McpToolManifestCache mockToolCache;

    @Mock
    private ScheduledExecutorService mockScheduledExecutor;

    private McpServerConfig config;
    private McpServerRegistry registry;

    @BeforeEach
    void setUp() {
        config = McpServerConfig.builder()
                .name("test-server")
                .transport(TransportType.STDIO)
                .command("echo")
                .autoConnect(false)
                .reconnect(false)
                .maxReconnectAttempts(3)
                .reconnectDelay(Duration.ofMillis(100))
                .healthCheckInterval(Duration.ofSeconds(30))
                .idleTimeout(Duration.ofMinutes(10))
                .build();

        lenient().when(mockScheduler.heartbeat()).thenReturn(mockScheduledExecutor);
        lenient().when(mockToolCache.load(anyString())).thenReturn(List.of());

        // 子类覆盖 createClient() 返回 Mock
        registry = new McpServerRegistry(mockToolAdapter, mockToolRegistry, mockEventPublisher, mockScheduler, mockToolCache) {
            @Override
            McpClient createClient(McpServerConfig cfg) {
                return mockClient;
            }
        };
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 执行标准连接流程并等待异步完成。 */
    private void 连接成功() throws Exception {
        when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
        when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                .thenReturn(List.of());
        registry.connectServer(config).join();
    }

    // ─────────────────────────────────────────────
    //  initializeAll 测试
    // ─────────────────────────────────────────────

    @Nested
    class 批量注册 {

        @Test
        void initializeAll_注册服务器条目_状态为DISCONNECTED() {
            registry.initializeAll(List.of(config));

            var servers = registry.listServers();
            assertEquals(1, servers.size());
            assertEquals("test-server", servers.get(0).config().name());
            assertEquals(McpServerState.DISCONNECTED, servers.get(0).state());
        }

        @Test
        void initializeAll_不触发连接_客户端为null() {
            registry.initializeAll(List.of(config));

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertNull(entry.get().client());
        }

        @Test
        void initializeAll_autoConnect为true也不自动连接() {
            // 新设计：initializeAll 只注册不连接，与 autoConnect 无关
            var autoConfig = config.toBuilder().autoConnect(true).build();

            registry.initializeAll(List.of(autoConfig));

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertEquals(McpServerState.DISCONNECTED, entry.get().state());
            assertNull(entry.get().client());
        }

        @Test
        void initializeAll_多个配置_全部注册() {
            var config2 = config.toBuilder().name("second-server").build();
            registry.initializeAll(List.of(config, config2));

            assertEquals(2, registry.listServers().size());
            assertTrue(registry.getServer("test-server").isPresent());
            assertTrue(registry.getServer("second-server").isPresent());
        }

        @Test
        void initializeAll_有缓存时从缓存加载工具桩() {
            var cachedSchemas = List.of(
                    new McpToolSchema("cached-tool", "缓存的工具", Map.of("type", "object"), null)
            );
            when(mockToolCache.load("test-server")).thenReturn(cachedSchemas);

            @SuppressWarnings("unchecked")
            List<ToolContract> mockTools = (List<ToolContract>) mock(List.class);
            // toToolContracts 第三个参数为 null（无 client）
            when(mockToolAdapter.toToolContracts(eq("test-server"), eq(cachedSchemas), isNull()))
                    .thenReturn(mockTools);

            registry.initializeAll(List.of(config));

            verify(mockToolAdapter).toToolContracts(eq("test-server"), eq(cachedSchemas), isNull());
            verify(mockToolRegistry).registerMcpTools(eq("test-server"), eq(mockTools));
        }

        @Test
        void initializeAll_无缓存时不注册工具桩() {
            when(mockToolCache.load("test-server")).thenReturn(List.of());

            registry.initializeAll(List.of(config));

            verify(mockToolAdapter, never()).toToolContracts(eq("test-server"), anyList(), isNull());
            verify(mockToolRegistry, never()).registerMcpTools(eq("test-server"), anyList());
        }
    }

    // ─────────────────────────────────────────────
    //  connectServer 测试
    // ─────────────────────────────────────────────

    @Nested
    class 连接服务器 {

        @Test
        void connectServer_返回CompletableFuture() {
            registry.initializeAll(List.of(config));
            when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                    .thenReturn(List.of());

            CompletableFuture<Void> future = registry.connectServer(config);

            assertNotNull(future);
            assertDoesNotThrow(future::join);
        }

        @Test
        void connectServer_连接成功后注册工具() throws Exception {
            registry.initializeAll(List.of(config));

            @SuppressWarnings("unchecked")
            List<ToolContract> mockTools = (List<ToolContract>) mock(List.class);
            when(mockTools.size()).thenReturn(1);
            when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                    .thenReturn(mockTools);

            registry.connectServer(config).join();

            verify(mockToolRegistry).registerMcpTools(eq("test-server"), eq(mockTools));
        }

        @Test
        void connectServer_连接成功后状态为CONNECTED() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertEquals(McpServerState.CONNECTED, entry.get().state());
            assertNotNull(entry.get().connectedSince());
            assertNotNull(entry.get().lastToolCall());
            assertEquals(0, entry.get().reconnectAttempts());
            assertNull(entry.get().lastError());
        }

        @Test
        void connectServer_成功后写入工具缓存() throws Exception {
            registry.initializeAll(List.of(config));

            var schemas = List.of(
                    new McpToolSchema("tool-a", "工具A", Map.of("type", "object"), null)
            );
            when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(schemas));
            when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("test-server"), eq(schemas), eq(mockClient)))
                    .thenReturn(List.of());

            registry.connectServer(config).join();

            verify(mockToolCache).save(eq("test-server"), eq(schemas));
        }

        @Test
        void connectServer_连接失败后状态为DISCONNECTED() {
            registry.initializeAll(List.of(config));

            when(mockClient.initialize()).thenReturn(
                    CompletableFuture.failedFuture(new RuntimeException("连接超时")));

            registry.connectServer(config).join();

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertEquals(McpServerState.DISCONNECTED, entry.get().state());
            assertTrue(entry.get().lastError().contains("连接超时"));
        }

        @Test
        void connectServer_已连接时跳过重复连接() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            // 再次连接应跳过
            registry.connectServer(config).join();

            // initialize 只调用一次
            verify(mockClient, times(1)).initialize();
        }

        @Test
        void connectServer_连接失败且reconnect为true_触发重连调度() {
            var reconnectConfig = config.toBuilder().reconnect(true).build();
            registry.initializeAll(List.of(reconnectConfig));

            when(mockClient.initialize()).thenReturn(
                    CompletableFuture.failedFuture(new RuntimeException("失败")));

            registry.connectServer(reconnectConfig).join();

            // 验证调度了重连任务
            verify(mockScheduledExecutor).schedule(any(Runnable.class), anyLong(), any());
        }
    }

    // ─────────────────────────────────────────────
    //  disconnectServer 测试
    // ─────────────────────────────────────────────

    @Nested
    class 断开连接 {

        @Test
        void disconnectServer_断开连接并注销工具() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            when(mockClient.shutdown()).thenReturn(CompletableFuture.completedFuture(null));

            registry.disconnectServer("test-server");

            verify(mockToolRegistry).unregisterMcpTools("test-server");

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertEquals(McpServerState.DISCONNECTED, entry.get().state());
            assertNull(entry.get().client());
            assertNull(entry.get().connectedSince());
        }

        @Test
        void disconnectServer_不存在的服务器不抛异常() {
            assertDoesNotThrow(() -> registry.disconnectServer("non-existent"));
        }
    }

    // ─────────────────────────────────────────────
    //  ensureConnected 测试
    // ─────────────────────────────────────────────

    @Nested
    class 懒连接 {

        @Test
        void ensureConnected_已连接时直接返回McpClient() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            McpClient client = registry.ensureConnected("test-server");

            assertSame(mockClient, client);
        }

        @Test
        void ensureConnected_未连接时触发懒连接() {
            registry.initializeAll(List.of(config));

            // 模拟连接成功（ensureConnected 内部会调 connectServer）
            when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                    .thenReturn(List.of());

            McpClient client = registry.ensureConnected("test-server");

            assertSame(mockClient, client);
            verify(mockClient).initialize();
        }

        @Test
        void ensureConnected_服务器未注册_抛出McpServerUnavailableException() {
            var ex = assertThrows(McpServerUnavailableException.class,
                    () -> registry.ensureConnected("unknown-server"));

            assertTrue(ex.getMessage().contains("unknown-server"));
            assertTrue(ex.getMessage().contains("未注册"));
        }

        @Test
        void ensureConnected_懒连接失败_抛出McpServerUnavailableException() {
            registry.initializeAll(List.of(config));

            when(mockClient.initialize()).thenReturn(
                    CompletableFuture.failedFuture(new RuntimeException("网络中断")));

            var ex = assertThrows(McpServerUnavailableException.class,
                    () -> registry.ensureConnected("test-server"));

            assertTrue(ex.getMessage().contains("test-server"));
        }
    }

    // ─────────────────────────────────────────────
    //  recordToolCall 测试
    // ─────────────────────────────────────────────

    @Nested
    class 工具调用记录 {

        @Test
        void recordToolCall_更新lastToolCall时间戳() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            var beforeCall = registry.getServer("test-server").get().lastToolCall();

            // 等一小段时间确保时间戳不同
            Thread.sleep(10);
            registry.recordToolCall("test-server");

            var afterCall = registry.getServer("test-server").get().lastToolCall();
            assertNotNull(afterCall);
            assertTrue(afterCall.isAfter(beforeCall) || afterCall.equals(beforeCall));
        }

        @Test
        void recordToolCall_服务器不存在时不抛异常() {
            // computeIfPresent 不操作不存在的键
            assertDoesNotThrow(() -> registry.recordToolCall("non-existent"));
        }
    }

    // ─────────────────────────────────────────────
    //  getClient 测试
    // ─────────────────────────────────────────────

    @Nested
    class 获取客户端 {

        @Test
        void getClient_已连接时返回客户端() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            var client = registry.getClient("test-server");
            assertTrue(client.isPresent());
            assertSame(mockClient, client.get());
        }

        @Test
        void getClient_未连接时返回空() {
            registry.initializeAll(List.of(config));
            assertTrue(registry.getClient("test-server").isEmpty());
        }

        @Test
        void getClient_不存在的服务器返回空() {
            assertTrue(registry.getClient("non-existent").isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  shutdown / destroy 测试
    // ─────────────────────────────────────────────

    @Nested
    class 优雅关闭 {

        @Test
        void shutdown_断开所有连接_清空注册表() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            when(mockClient.shutdown()).thenReturn(CompletableFuture.completedFuture(null));

            registry.shutdown();

            verify(mockClient).shutdown();
            verify(mockToolRegistry).unregisterMcpTools("test-server");
            assertTrue(registry.listServers().isEmpty());
        }

        @Test
        void shutdown_无连接时安全执行() {
            registry.initializeAll(List.of(config));

            assertDoesNotThrow(() -> registry.shutdown());
            assertTrue(registry.listServers().isEmpty());
        }

        @Test
        void shutdown_单个server关闭异常不影响其他server清理() throws Exception {
            var config2 = config.toBuilder().name("server-2").build();
            var mockClient2 = mock(McpClient.class);

            // 用一个记录 server 名称来返回不同 client 的子类
            var registryMulti = new McpServerRegistry(
                    mockToolAdapter, mockToolRegistry, mockEventPublisher, mockScheduler, mockToolCache) {
                @Override
                McpClient createClient(McpServerConfig cfg) {
                    return "server-2".equals(cfg.name()) ? mockClient2 : mockClient;
                }
            };

            registryMulti.initializeAll(List.of(config, config2));

            // 连接第一个
            when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                    .thenReturn(List.of());
            registryMulti.connectServer(config).join();

            // 连接第二个
            when(mockClient2.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(mockClient2.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(mockClient2.getServerInfo()).thenReturn(new McpServerInfo("test2", "1.0"));
            when(mockToolAdapter.toToolContracts(eq("server-2"), anyList(), eq(mockClient2)))
                    .thenReturn(List.of());
            registryMulti.connectServer(config2).join();

            // 第一个关闭时抛异常
            when(mockClient.shutdown()).thenThrow(new RuntimeException("关闭失败"));
            when(mockClient2.shutdown()).thenReturn(CompletableFuture.completedFuture(null));

            // 不应抛异常
            assertDoesNotThrow(registryMulti::shutdown);
            assertTrue(registryMulti.listServers().isEmpty());
        }

        @Test
        void destroy_委托给shutdown() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            when(mockClient.shutdown()).thenReturn(CompletableFuture.completedFuture(null));

            registry.destroy();

            // shutdown 被调用后 servers 应该为空
            assertTrue(registry.listServers().isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  scheduleMaintenance 测试
    // ─────────────────────────────────────────────

    @Nested
    class 维护任务 {

        @Test
        void connectServer成功后_调度维护任务() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            // 验证 scheduleAtFixedRate 被调用
            verify(mockScheduledExecutor).scheduleAtFixedRate(
                    any(Runnable.class),
                    eq(config.healthCheckInterval().toMillis()),
                    eq(config.healthCheckInterval().toMillis()),
                    any());
        }

        @Test
        void 维护任务句柄保存在entry中() throws Exception {
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
            doReturn(mockFuture).when(mockScheduledExecutor)
                    .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());

            registry.initializeAll(List.of(config));
            连接成功();

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertNotNull(entry.get().maintenanceFuture());
        }
    }

    // ─────────────────────────────────────────────
    //  重连测试
    // ─────────────────────────────────────────────

    @Nested
    class 重连逻辑 {

        @Test
        void scheduleReconnect_重连次数耗尽后状态为DISCONNECTED() {
            var reconnectConfig = config.toBuilder()
                    .reconnect(true)
                    .maxReconnectAttempts(2)
                    .reconnectDelay(Duration.ofMillis(50))
                    .build();

            registry.initializeAll(List.of(reconnectConfig));

            // 手动触发重连直到次数耗尽
            registry.scheduleReconnect("test-server", reconnectConfig);
            // attempts: 0 → 1
            registry.scheduleReconnect("test-server", reconnectConfig);
            // attempts: 1 → 2，达到上限，下次应拒绝
            registry.scheduleReconnect("test-server", reconnectConfig);

            var entry = registry.getServer("test-server");
            assertTrue(entry.isPresent());
            assertEquals(McpServerState.DISCONNECTED, entry.get().state());
        }
    }

    // ─────────────────────────────────────────────
    //  连接日志测试
    // ─────────────────────────────────────────────

    @Nested
    class 连接日志 {

        @Test
        void getConnectionLogs_不存在的server返回空列表() {
            assertTrue(registry.getConnectionLogs("non-existent").isEmpty());
        }

        @Test
        void getConnectionLogs_连接成功后有日志记录() throws Exception {
            registry.initializeAll(List.of(config));
            连接成功();

            var logs = registry.getConnectionLogs("test-server");
            assertFalse(logs.isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  列表不可变性测试
    // ─────────────────────────────────────────────

    @Test
    void listServers_返回不可变列表() {
        registry.initializeAll(List.of(config));

        var servers = registry.listServers();
        assertThrows(UnsupportedOperationException.class, () ->
                servers.add(McpServerEntry.initial(config)));
    }
}
