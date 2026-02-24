package com.lifepilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.transport.StdioTransport;
import com.lifepilot.mcp.transport.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * McpClient 单元测试。
 *
 * <p>使用 Mock StdioTransport 验证 initialize 流程、listTools 解析、callTool 请求构建。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ExtendWith(MockitoExtension.class)
class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private StdioTransport mockTransport;

    private McpServerConfig config;
    private McpClient client;

    @BeforeEach
    void setUp() {
        config = McpServerConfig.builder()
                .name("test-server")
                .transport(TransportType.STDIO)
                .command("echo")
                .build();
        client = new McpClient(config, mockTransport);
    }

    // ─────────────────────────────────────────────
    //  initialize 测试
    // ─────────────────────────────────────────────

    @Test
    void initialize_完整握手流程() throws Exception {
        // 准备 initialize 响应
        JsonNode initResponse = MAPPER.readTree("""
                {
                    "capabilities": { "tools": {} },
                    "serverInfo": { "name": "test-mcp", "version": "1.0" }
                }
                """);

        when(mockTransport.connect())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockTransport.sendRequest(eq("initialize"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(initResponse));

        // 执行
        client.initialize().join();

        // 验证：connect → sendRequest("initialize") → sendNotification("notifications/initialized")
        var inOrder = inOrder(mockTransport);
        inOrder.verify(mockTransport).connect();
        inOrder.verify(mockTransport).sendRequest(eq("initialize"), anyMap());
        inOrder.verify(mockTransport).sendNotification(
                eq("notifications/initialized"), eq(Map.of()));

        // 验证服务端信息已解析
        assertNotNull(client.getServerCapabilities());
        assertTrue(client.getServerCapabilities().supportsTools());
        assertNotNull(client.getServerInfo());
        assertEquals("test-mcp", client.getServerInfo().name());
        assertEquals("1.0", client.getServerInfo().version());
    }

    @Test
    void initialize_服务端能力解析_无capabilities字段() throws Exception {
        JsonNode initResponse = MAPPER.readTree("""
                {
                    "serverInfo": { "name": "minimal", "version": "0.1" }
                }
                """);

        when(mockTransport.connect())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockTransport.sendRequest(eq("initialize"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(initResponse));

        client.initialize().join();

        // capabilities 为 null 时降级为默认值
        var caps = client.getServerCapabilities();
        assertNotNull(caps);
        assertFalse(caps.supportsTools());
        assertFalse(caps.supportsResources());
    }

    @Test
    void initialize_请求包含协议版本和客户端信息() throws Exception {
        JsonNode initResponse = MAPPER.readTree("""
                { "capabilities": {}, "serverInfo": { "name": "s", "version": "1" } }
                """);

        when(mockTransport.connect())
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockTransport.sendRequest(eq("initialize"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(initResponse));

        client.initialize().join();

        // 验证 initialize 请求参数
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mockTransport).sendRequest(eq("initialize"), captor.capture());

        var params = captor.getValue();
        assertEquals("2025-06-18", params.get("protocolVersion"));
        assertNotNull(params.get("capabilities"));
        assertNotNull(params.get("clientInfo"));
    }

    // ─────────────────────────────────────────────
    //  listTools 测试
    // ─────────────────────────────────────────────

    @Test
    void listTools_解析工具列表() throws Exception {
        JsonNode toolsResponse = MAPPER.readTree("""
                {
                    "tools": [
                        {
                            "name": "read_file",
                            "description": "读取文件内容",
                            "inputSchema": { "type": "object", "properties": { "path": { "type": "string" } } }
                        },
                        {
                            "name": "write_file",
                            "description": "写入文件",
                            "inputSchema": { "type": "object" },
                            "annotations": { "readOnlyHint": false, "destructiveHint": true }
                        }
                    ]
                }
                """);

        when(mockTransport.sendRequest(eq("tools/list"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(toolsResponse));

        var tools = client.listTools().join();

        assertEquals(2, tools.size());
        assertEquals("read_file", tools.get(0).name());
        assertEquals("读取文件内容", tools.get(0).description());
        assertEquals("write_file", tools.get(1).name());
        assertNotNull(tools.get(1).annotations());
        assertTrue(tools.get(1).annotations().destructiveHint());
    }

    @Test
    void listTools_空工具列表() throws Exception {
        JsonNode emptyResponse = MAPPER.readTree("""
                { "tools": [] }
                """);

        when(mockTransport.sendRequest(eq("tools/list"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(emptyResponse));

        var tools = client.listTools().join();

        assertTrue(tools.isEmpty());
    }

    @Test
    void listTools_无tools字段_返回空列表() throws Exception {
        JsonNode noToolsResponse = MAPPER.readTree("{}");

        when(mockTransport.sendRequest(eq("tools/list"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(noToolsResponse));

        var tools = client.listTools().join();

        assertTrue(tools.isEmpty());
    }

    // ─────────────────────────────────────────────
    //  callTool 测试
    // ─────────────────────────────────────────────

    @Test
    void callTool_成功调用() throws Exception {
        JsonNode callResponse = MAPPER.readTree("""
                {
                    "content": [{ "type": "text", "text": "文件内容" }],
                    "isError": false
                }
                """);

        when(mockTransport.sendRequest(eq("tools/call"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(callResponse));

        var result = client.callTool("read_file", Map.of("path", "/tmp/test.txt")).join();

        assertFalse(result.isError());
        assertEquals(1, result.content().size());
        assertEquals("text", result.content().getFirst().type());
        assertEquals("文件内容", result.content().getFirst().text());
    }

    @Test
    void callTool_请求参数正确构建() throws Exception {
        JsonNode callResponse = MAPPER.readTree("""
                { "content": [], "isError": false }
                """);

        when(mockTransport.sendRequest(eq("tools/call"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(callResponse));

        client.callTool("my_tool", Map.of("key", "value")).join();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mockTransport).sendRequest(eq("tools/call"), captor.capture());

        var params = captor.getValue();
        assertEquals("my_tool", params.get("name"));
        assertEquals(Map.of("key", "value"), params.get("arguments"));
    }

    @Test
    void callTool_错误结果() throws Exception {
        JsonNode errorResponse = MAPPER.readTree("""
                {
                    "content": [{ "type": "text", "text": "工具执行失败" }],
                    "isError": true
                }
                """);

        when(mockTransport.sendRequest(eq("tools/call"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(errorResponse));

        var result = client.callTool("bad_tool", Map.of()).join();

        assertTrue(result.isError());
    }

    // ─────────────────────────────────────────────
    //  shutdown 和辅助方法测试
    // ─────────────────────────────────────────────

    @Test
    void shutdown_调用传输层disconnect() {
        when(mockTransport.disconnect())
                .thenReturn(CompletableFuture.completedFuture(null));

        client.shutdown().join();

        verify(mockTransport).disconnect();
    }

    @Test
    void getServerName_返回配置名称() {
        assertEquals("test-server", client.getServerName());
    }

    @Test
    void isConnected_委托给传输层() {
        when(mockTransport.isConnected()).thenReturn(true);
        assertTrue(client.isConnected());

        when(mockTransport.isConnected()).thenReturn(false);
        assertFalse(client.isConnected());
    }

    @Test
    void getTransportType_委托给传输层() {
        when(mockTransport.transportType()).thenReturn(TransportType.STDIO);
        assertEquals(TransportType.STDIO, client.getTransportType());
    }
}
