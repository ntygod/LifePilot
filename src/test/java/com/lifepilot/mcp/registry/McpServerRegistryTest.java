package com.lifepilot.mcp.registry;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.model.McpServerInfo;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.lifepilot.config.threadpool.SharedScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * McpServerRegistry 单元测试。
 *
 * <p>通过子类覆盖 createClient() 注入 Mock McpClient，
 * 验证连接→注册→断开→注销流程和重连次数耗尽后状态。</p>
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
                .build();

        // 子类覆盖 createClient() 返回 Mock
        registry = new McpServerRegistry(mockToolAdapter, mockToolRegistry, mockEventPublisher, mock(SharedScheduler.class)) {
            @Override
            McpClient createClient(McpServerConfig cfg) {
                return mockClient;
            }
        };
    }

    // ─────────────────────────────────────────────
    //  initializeAll 测试
    // ─────────────────────────────────────────────

    @Test
    void initializeAll_注册服务器条目() {
        registry.initializeAll(List.of(config));

        var servers = registry.listServers();
        assertEquals(1, servers.size());
        assertEquals("test-server", servers.get(0).config().name());
        assertEquals(McpServerState.DISCONNECTED, servers.get(0).state());
    }

    @Test
    void initializeAll_autoConnect为true时自动连接() throws Exception {
        var autoConfig = config.toBuilder().autoConnect(true).build();

        // 模拟连接成功
        when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
        when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                .thenReturn(List.of());

        registry.initializeAll(List.of(autoConfig));

        // 等待异步连接完成
        Thread.sleep(500);

        var entry = registry.getServer("test-server");
        assertTrue(entry.isPresent());
        assertEquals(McpServerState.CONNECTED, entry.get().state());
    }

    // ─────────────────────────────────────────────
    //  connectServer 测试
    // ─────────────────────────────────────────────

    @Test
    void connectServer_连接成功后注册工具() throws Exception {
        // 先注册条目
        registry.initializeAll(List.of(config));

        // 模拟连接成功
        when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of(
                new McpToolSchema("tool-a", "描述A", null, null)
        )));
        when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));

        @SuppressWarnings("unchecked")
        List<ToolContract> mockTools = (List<ToolContract>) mock(List.class);
        when(mockTools.size()).thenReturn(1);
        when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                .thenReturn(mockTools);

        registry.connectServer(config);

        // 等待异步完成
        Thread.sleep(500);

        // 验证工具注册
        verify(mockToolRegistry).registerMcpTools(eq("test-server"), eq(mockTools));

        // 验证状态
        var entry = registry.getServer("test-server");
        assertTrue(entry.isPresent());
        assertEquals(McpServerState.CONNECTED, entry.get().state());
        assertNotNull(entry.get().connectedSince());
        assertEquals(0, entry.get().reconnectAttempts());
        assertNull(entry.get().lastError());
    }

    @Test
    void connectServer_连接失败后状态为DISCONNECTED() throws Exception {
        registry.initializeAll(List.of(config));

        // 模拟连接失败
        when(mockClient.initialize()).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("连接超时")));

        registry.connectServer(config);

        // 等待异步完成
        Thread.sleep(500);

        var entry = registry.getServer("test-server");
        assertTrue(entry.isPresent());
        assertEquals(McpServerState.DISCONNECTED, entry.get().state());
        assertTrue(entry.get().lastError().contains("连接超时"));
    }

    // ─────────────────────────────────────────────
    //  disconnectServer 测试
    // ─────────────────────────────────────────────

    @Test
    void disconnectServer_断开连接并注销工具() throws Exception {
        // 先连接
        registry.initializeAll(List.of(config));
        when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
        when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                .thenReturn(List.of());
        registry.connectServer(config);
        Thread.sleep(500);

        // 模拟 shutdown
        when(mockClient.shutdown()).thenReturn(CompletableFuture.completedFuture(null));

        // 断开
        registry.disconnectServer("test-server");

        // 验证注销工具
        verify(mockToolRegistry).unregisterMcpTools("test-server");

        // 验证状态
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

    // ─────────────────────────────────────────────
    //  getClient 测试
    // ─────────────────────────────────────────────

    @Test
    void getClient_已连接时返回客户端() throws Exception {
        registry.initializeAll(List.of(config));
        when(mockClient.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockClient.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mockClient.getServerInfo()).thenReturn(new McpServerInfo("test", "1.0"));
        when(mockToolAdapter.toToolContracts(eq("test-server"), anyList(), eq(mockClient)))
                .thenReturn(List.of());
        registry.connectServer(config);
        Thread.sleep(500);

        var client = registry.getClient("test-server");
        assertTrue(client.isPresent());
        assertSame(mockClient, client.get());
    }

    @Test
    void getClient_未连接时返回空() {
        registry.initializeAll(List.of(config));

        var client = registry.getClient("test-server");
        assertTrue(client.isEmpty());
    }

    @Test
    void getClient_不存在的服务器返回空() {
        assertTrue(registry.getClient("non-existent").isEmpty());
    }

    // ─────────────────────────────────────────────
    //  重连测试
    // ─────────────────────────────────────────────

    @Test
    void scheduleReconnect_重连次数耗尽后状态为DISCONNECTED() throws Exception {
        var reconnectConfig = config.toBuilder()
                .reconnect(true)
                .maxReconnectAttempts(2)
                .reconnectDelay(Duration.ofMillis(50))
                .build();

        registry.initializeAll(List.of(reconnectConfig));

        // 模拟连接始终失败
        when(mockClient.initialize()).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("连接失败")));

        // 手动触发重连，模拟已尝试 2 次（达到上限）
        registry.scheduleReconnect("test-server", reconnectConfig);
        // 第一次重连尝试（attempts=0 → 1）
        Thread.sleep(200);

        registry.scheduleReconnect("test-server", reconnectConfig);
        // 第二次重连尝试（attempts=1 → 2）
        Thread.sleep(200);

        // 第三次调用应该因为 attempts >= maxReconnectAttempts 而直接设为 DISCONNECTED
        registry.scheduleReconnect("test-server", reconnectConfig);

        var entry = registry.getServer("test-server");
        assertTrue(entry.isPresent());
        assertEquals(McpServerState.DISCONNECTED, entry.get().state());
    }

    @Test
    void listServers_返回不可变列表() {
        registry.initializeAll(List.of(config));

        var servers = registry.listServers();
        assertThrows(UnsupportedOperationException.class, () ->
                servers.add(McpServerEntry.initial(config)));
    }
}
