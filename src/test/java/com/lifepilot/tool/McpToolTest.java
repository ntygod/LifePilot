package com.lifepilot.tool;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.model.McpContent;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * McpTool 单元测试�? *
 * <p>使用 Mock McpServerRegistry + McpClient 验证 execute() 各路径�?/p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ExtendWith(MockitoExtension.class)
class McpToolTest {

    @Mock
    private McpClient mockClient;

    @Mock
    private McpServerRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        McpTool.setMcpServerRegistry(mockRegistry);
    }

    private McpTool createTool() {
        return new McpTool(
                "mcp.test.read_file", "read_file", "读取文件",
                JsonSchema.empty(), JsonSchema.empty(),
                RiskLevel.LOW, true, ToolBudget.MCP_DEFAULT,
                List.of("mcp"), "test", "read_file",
                "test"
        );
    }

    private ToolInput createInput(Map<String, Object> params) {
        return new ToolInput("mcp.test.read_file", params, JsonSchema.empty(), null, null);
    }

    @Test
    void execute_成功路径_返回ToolResult_success() {
        when(mockRegistry.getClient("test")).thenReturn(Optional.of(mockClient));
        var mcpResult = new McpToolResult(
                List.of(new McpContent("text", "文件内容", null, null)),
                false
        );
        when(mockClient.callTool(eq("read_file"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(mcpResult));

        var tool = createTool();
        var result = tool.execute(createInput(Map.of("path", "/tmp/test.txt")));

        assertTrue(result.ok());
        assertEquals("文件内容", result.getData("result"));
        assertEquals("MCP", result.meta().executorType());
        assertEquals("test", result.meta().mcpServerName());
    }

    @Test
    void execute_多个text内容_拼接结果() {
        when(mockRegistry.getClient("test")).thenReturn(Optional.of(mockClient));
        var mcpResult = new McpToolResult(
                List.of(
                        new McpContent("text", "第一�?, null, null),
                        new McpContent("text", "第二�?, null, null)
                ),
                false
        );
        when(mockClient.callTool(eq("read_file"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(mcpResult));

        var tool = createTool();
        var result = tool.execute(createInput(Map.of()));

        assertTrue(result.ok());
        assertEquals("第一段第二段", result.getData("result"));
    }

    @Test
    void execute_失败路径_isError为true_返回ToolResult_error() {
        when(mockRegistry.getClient("test")).thenReturn(Optional.of(mockClient));
        var mcpResult = new McpToolResult(
                List.of(new McpContent("text", "文件不存�?, null, null)),
                true
        );
        when(mockClient.callTool(eq("read_file"), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(mcpResult));

        var tool = createTool();
        var result = tool.execute(createInput(Map.of("path", "/nonexistent")));

        assertFalse(result.ok());
        assertEquals("文件不存�?, result.error());
        assertEquals("MCP", result.meta().executorType());
    }

    @Test
    void execute_异常路径_返回ToolResult_error() {
        when(mockRegistry.getClient("test")).thenReturn(Optional.of(mockClient));
        when(mockClient.callTool(eq("read_file"), anyMap()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("连接断开")));

        var tool = createTool();
        var result = tool.execute(createInput(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.error().contains("连接断开"));
    }

    @Test
    void execute_客户端不存在_返回错误() {
        when(mockRegistry.getClient("test")).thenReturn(Optional.empty());

        var tool = createTool();
        var result = tool.execute(createInput(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.error().contains("不存在或已断开"));
    }

    @Test
    void layer_返回MCP_EXTERNAL() {
        var tool = createTool();
        assertEquals(com.lifepilot.tool.model.ToolLayer.MCP_EXTERNAL, tool.layer());
    }
}
