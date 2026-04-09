package com.lifepilot.mcp.adapter;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpServerUnavailableException;
import com.lifepilot.mcp.model.McpContent;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.registry.McpServerEntry;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.mcp.registry.McpServerState;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.model.*;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * McpToolExecutor 单元测试 — 覆盖懒连接、超时保护、工具调用记录、异常映射等核心路径。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class McpToolExecutor_单元测试 {

    @Mock
    private McpServerRegistry registry;

    @Mock
    private McpClient mcpClient;

    private McpToolExecutor executor;

    @BeforeEach
    void 初始化() {
        executor = new McpToolExecutor(registry);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 创建标准的 McpTool 测试实例。 */
    private McpTool 创建测试工具(String serverName, String toolName, String clientId) {
        return new McpTool(
                "mcp." + serverName + "." + toolName,
                toolName,
                "测试工具描述",
                new JsonSchema(Map.of("type", "object")),
                new JsonSchema(Map.of("type", "object")),
                RiskLevel.MEDIUM,
                false,
                new ToolExecutionSemantics(null, null, null),
                ToolBudget.MCP_DEFAULT,
                List.of("mcp"),
                serverName,
                toolName,
                clientId
        );
    }

    /** 创建带有默认 serverName/clientId 的测试工具。 */
    private McpTool 创建默认测试工具() {
        return 创建测试工具("test-server", "read_file", "test-server");
    }

    /** 创建标准的 ToolInput 测试实例。 */
    private ToolInput 创建测试输入(Map<String, Object> parameters) {
        return new ToolInput(
                "mcp.test-server.read_file",
                parameters,
                new JsonSchema(Map.of("type", "object")),
                null,
                null
        );
    }

    /** 创建成功的 McpToolResult（单个 text content）。 */
    private McpToolResult 创建成功MCP结果(String text) {
        return new McpToolResult(
                List.of(new McpContent("text", text, null, null)),
                false
        );
    }

    /** 创建错误的 McpToolResult。 */
    private McpToolResult 创建错误MCP结果(String errorMsg) {
        return new McpToolResult(
                List.of(new McpContent("text", errorMsg, null, null)),
                true
        );
    }

    /** 创建带超时配置的 McpServerEntry，用于 registry.getServer() mock。 */
    private McpServerEntry 创建已连接Entry(Duration timeout) {
        var serverConfig = McpServerConfig.builder()
                .name("test-server")
                .transport(TransportType.STDIO)
                .command("echo")
                .timeout(timeout)
                .build();
        return McpServerEntry.builder()
                .config(serverConfig)
                .client(mcpClient)
                .state(McpServerState.CONNECTED)
                .build();
    }

    /** 设置 ensureConnected 和 getServer 的标准 mock。 */
    private void 设置标准连接Mock() {
        when(registry.ensureConnected("test-server")).thenReturn(mcpClient);
        lenient().when(registry.getServer("test-server"))
                .thenReturn(Optional.of(创建已连接Entry(Duration.ofSeconds(60))));
    }

    // ─────────────────────────────────────────────
    //  正常调用流程
    // ─────────────────────────────────────────────

    @Nested
    class 正常调用流程 {

        @Test
        void 调用成功_返回文本结果() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of("path", "/tmp/test.txt"));
            McpToolResult mcpResult = 创建成功MCP结果("文件内容");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("文件内容", result.data().get("result"));
            assertNull(result.error());
        }

        @Test
        void 调用成功_触发ensureConnected懒连接() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("ok");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            executor.execute(tool, input);

            // then — 验证调用了 ensureConnected 而非 getClient
            verify(registry).ensureConnected("test-server");
        }

        @Test
        void 调用成功_记录工具调用时间() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("ok");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            executor.execute(tool, input);

            // then
            verify(registry).recordToolCall("test-server");
        }

        @Test
        void 调用成功_元信息包含正确的工具ID和执行器类型() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("ok");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            ToolResultMeta meta = result.meta();
            assertEquals(tool.id(), meta.toolId());
            assertEquals("callTool", meta.action());
            assertEquals("MCP", meta.executorType());
            assertEquals("test-server", meta.mcpServerName());
            assertEquals(0, meta.tokensUsed());
            assertFalse(meta.cacheHit());
            assertEquals(0, meta.retryCount());
            assertNotNull(meta.duration());
            assertNotNull(meta.timestamp());
        }

        @Test
        void 调用成功_正确传递参数到MCP客户端() {
            // given
            McpTool tool = 创建默认测试工具();
            Map<String, Object> params = Map.of("path", "/data/file.csv", "encoding", "utf-8");
            ToolInput input = 创建测试输入(params);
            McpToolResult mcpResult = 创建成功MCP结果("csv数据");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), eq(params)))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            verify(mcpClient).callTool("read_file", params);
        }

        @Test
        void 多个text内容块_拼接为单个字符串() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(
                            new McpContent("text", "第一段", null, null),
                            new McpContent("text", "第二段", null, null),
                            new McpContent("text", "第三段", null, null)
                    ),
                    false
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("第一段第二段第三段", result.data().get("result"));
        }

        @Test
        void 混合内容类型_仅提取text类型() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(
                            new McpContent("image", null, "base64data", "image/png"),
                            new McpContent("text", "文本内容", null, null),
                            new McpContent("resource", null, null, null)
                    ),
                    false
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("文本内容", result.data().get("result"));
        }

        @Test
        void 空参数Map_正常传递() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("结果");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), eq(Map.of())))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            verify(mcpClient).callTool("read_file", Map.of());
        }

        @Test
        void 不同clientId_使用对应客户端() {
            // given
            McpTool tool = 创建测试工具("server-alpha", "search", "server-alpha");
            ToolInput input = 创建测试输入(Map.of("query", "test"));
            McpToolResult mcpResult = 创建成功MCP结果("搜索结果");

            when(registry.ensureConnected("server-alpha")).thenReturn(mcpClient);
            var alphaConfig = McpServerConfig.builder()
                    .name("server-alpha")
                    .transport(TransportType.STDIO)
                    .command("echo")
                    .timeout(Duration.ofSeconds(60))
                    .build();
            lenient().when(registry.getServer("server-alpha"))
                    .thenReturn(Optional.of(McpServerEntry.builder()
                            .config(alphaConfig)
                            .client(mcpClient)
                            .state(McpServerState.CONNECTED)
                            .build()));
            when(mcpClient.callTool(eq("search"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            verify(registry).ensureConnected("server-alpha");
        }
    }

    // ─────────────────────────────────────────────
    //  Server 不可用（McpServerUnavailableException）
    // ─────────────────────────────────────────────

    @Nested
    class Server不可用 {

        @Test
        void ensureConnected抛出不可用异常_返回错误结果() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            when(registry.ensureConnected("test-server"))
                    .thenThrow(new McpServerUnavailableException("test-server", "未注册"));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals(ToolResultStatus.ERROR, result.status());
            assertTrue(result.error().contains("test-server"));
        }

        @Test
        void ensureConnected抛出不可用异常_不调用callTool() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            when(registry.ensureConnected("test-server"))
                    .thenThrow(new McpServerUnavailableException("test-server", "懒连接失败"));

            // when
            executor.execute(tool, input);

            // then
            verifyNoInteractions(mcpClient);
        }

        @Test
        void ensureConnected抛出不可用异常_不调用recordToolCall() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            when(registry.ensureConnected("test-server"))
                    .thenThrow(new McpServerUnavailableException("test-server"));

            // when
            executor.execute(tool, input);

            // then
            verify(registry, never()).recordToolCall(anyString());
        }

        @Test
        void 错误信息包含clientId方便排查() {
            // given
            McpTool tool = 创建测试工具("my-custom-server", "my_tool", "my-custom-server");
            ToolInput input = 创建测试输入(Map.of());

            when(registry.ensureConnected("my-custom-server"))
                    .thenThrow(new McpServerUnavailableException("my-custom-server", "连接超时"));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.error().contains("my-custom-server"));
        }
    }

    // ─────────────────────────────────────────────
    //  超时保护
    // ─────────────────────────────────────────────

    @Nested
    class 超时保护 {

        @Test
        void callTool超时_返回超时错误结果() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();

            // 模拟 orTimeout 触发的 CompletionException(TimeoutException)
            CompletableFuture<McpToolResult> slowFuture = new CompletableFuture<>();
            slowFuture.completeExceptionally(new TimeoutException("超时"));

            when(mcpClient.callTool(eq("read_file"), anyMap())).thenReturn(slowFuture);

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("超时"));
        }

        @Test
        void CompletionException包装TimeoutException_返回特定超时消息() {
            // given — join() 会把 TimeoutException 包装为 CompletionException
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();

            // 模拟 callTool 直接抛 CompletionException(TimeoutException)
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenThrow(new CompletionException(new TimeoutException("请求超时")));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("MCP 工具调用超时"));
            assertTrue(result.error().contains("read_file"));
        }

        @Test
        void 超时场景_仍记录recordToolCall() {
            // given — recordToolCall 在 callTool 之前调用
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();

            CompletableFuture<McpToolResult> slowFuture = new CompletableFuture<>();
            slowFuture.completeExceptionally(new TimeoutException("超时"));
            when(mcpClient.callTool(eq("read_file"), anyMap())).thenReturn(slowFuture);

            // when
            executor.execute(tool, input);

            // then — recordToolCall 在 callTool 之前执行，所以已记录
            verify(registry).recordToolCall("test-server");
        }

        @Test
        void getServer返回empty时_使用默认超时() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("ok");

            when(registry.ensureConnected("test-server")).thenReturn(mcpClient);
            when(registry.getServer("test-server")).thenReturn(Optional.empty());
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then — 使用 DEFAULT_TIMEOUT，仍能正常完成
            assertTrue(result.isSuccess());
        }
    }

    // ─────────────────────────────────────────────
    //  MCP 工具返回错误
    // ─────────────────────────────────────────────

    @Nested
    class MCP工具返回错误 {

        @Test
        void isError为true_提取错误文本() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建错误MCP结果("文件不存在: /tmp/missing.txt");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals(ToolResultStatus.ERROR, result.status());
            assertEquals("文件不存在: /tmp/missing.txt", result.error());
        }

        @Test
        void isError为true_元信息仍然包含执行轨迹() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建错误MCP结果("权限不足");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            ToolResultMeta meta = result.meta();
            assertNotNull(meta);
            assertEquals(tool.id(), meta.toolId());
            assertEquals("MCP", meta.executorType());
            assertEquals("test-server", meta.mcpServerName());
        }

        @Test
        void 错误结果无text内容_使用默认错误信息() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(new McpContent("image", null, "base64", "image/png")),
                    true
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals("MCP 工具调用失败", result.error());
        }

        @Test
        void 错误结果text为null_使用空字符串替代() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(new McpContent("text", null, null, null)),
                    true
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals("", result.error());
        }

        @Test
        void 错误结果content列表为空_使用默认错误信息() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(List.of(), true);

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals("MCP 工具调用失败", result.error());
        }
    }

    // ─────────────────────────────────────────────
    //  异常处理
    // ─────────────────────────────────────────────

    @Nested
    class 异常处理 {

        @Test
        void callTool的Future异常_返回错误结果() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            CompletableFuture<McpToolResult> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("连接超时"));

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap())).thenReturn(failedFuture);

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertEquals(ToolResultStatus.ERROR, result.status());
            assertTrue(result.error().contains("MCP 工具调用异常"));
        }

        @Test
        void CompletionException包装非TimeoutException_返回通用异常消息() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenThrow(new CompletionException(new RuntimeException("网络中断")));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("MCP 工具调用异常"));
        }

        @Test
        void callTool抛出同步异常_返回错误结果() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenThrow(new IllegalStateException("客户端未初始化"));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("MCP 工具调用异常"));
            assertTrue(result.error().contains("客户端未初始化"));
        }

        @Test
        void 异常场景_元信息仍然生成() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenThrow(new RuntimeException("未知错误"));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            ToolResultMeta meta = result.meta();
            assertNotNull(meta);
            assertEquals(tool.id(), meta.toolId());
            assertEquals("MCP", meta.executorType());
            assertEquals("test-server", meta.mcpServerName());
            assertNotNull(meta.duration());
        }

        @Test
        void NullPointerException_安全捕获() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenThrow(new NullPointerException("参数为 null"));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("MCP 工具调用异常"));
        }
    }

    // ─────────────────────────────────────────────
    //  结果内容边界情况
    // ─────────────────────────────────────────────

    @Nested
    class 结果内容边界情况 {

        @Test
        void 成功结果无text内容_返回空字符串() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(new McpContent("image", null, "base64data", "image/png")),
                    false
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("", result.data().get("result"));
        }

        @Test
        void 成功结果content为空列表_返回空字符串() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(List.of(), false);

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("", result.data().get("result"));
        }

        @Test
        void 成功结果text字段为null_使用空字符串替代() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(new McpContent("text", null, null, null)),
                    false
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("", result.data().get("result"));
        }

        @Test
        void 多个text内容块中部分text为null_null被替换为空字符串() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = new McpToolResult(
                    List.of(
                            new McpContent("text", "有内容", null, null),
                            new McpContent("text", null, null, null),
                            new McpContent("text", "也有内容", null, null)
                    ),
                    false
            );

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertTrue(result.isSuccess());
            assertEquals("有内容也有内容", result.data().get("result"));
        }
    }

    // ─────────────────────────────────────────────
    //  元信息构建验证
    // ─────────────────────────────────────────────

    @Nested
    class 元信息构建 {

        @Test
        void 成功调用_duration为非负值() {
            // given
            McpTool tool = 创建默认测试工具();
            ToolInput input = 创建测试输入(Map.of());
            McpToolResult mcpResult = 创建成功MCP结果("结果");

            设置标准连接Mock();
            when(mcpClient.callTool(eq("read_file"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            assertFalse(result.meta().duration().isNegative());
        }

        @Test
        void 不同工具_元信息反映对应工具信息() {
            // given
            McpTool tool = 创建测试工具("special-server", "web_search", "special-server");
            ToolInput input = new ToolInput(
                    "mcp.special-server.web_search",
                    Map.of("query", "hello"),
                    new JsonSchema(Map.of("type", "object")),
                    null, null
            );
            McpToolResult mcpResult = 创建成功MCP结果("搜索结果");

            when(registry.ensureConnected("special-server")).thenReturn(mcpClient);
            var specialConfig = McpServerConfig.builder()
                    .name("special-server")
                    .transport(TransportType.STDIO)
                    .command("echo")
                    .timeout(Duration.ofSeconds(60))
                    .build();
            lenient().when(registry.getServer("special-server"))
                    .thenReturn(Optional.of(McpServerEntry.builder()
                            .config(specialConfig)
                            .client(mcpClient)
                            .state(McpServerState.CONNECTED)
                            .build()));
            when(mcpClient.callTool(eq("web_search"), anyMap()))
                    .thenReturn(CompletableFuture.completedFuture(mcpResult));

            // when
            ToolResult result = executor.execute(tool, input);

            // then
            ToolResultMeta meta = result.meta();
            assertEquals("mcp.special-server.web_search", meta.toolId());
            assertEquals("special-server", meta.mcpServerName());
        }
    }
}
