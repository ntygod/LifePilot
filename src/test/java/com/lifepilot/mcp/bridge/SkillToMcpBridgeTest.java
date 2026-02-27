package com.lifepilot.mcp.bridge;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * SkillToMcpBridge 单元测试。
 *
 * <p>验证仅返回 exportable 工具（CP-6）、工具不存在返回错误、
 * RiskLevel→annotations 映射正确性。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ExtendWith(MockitoExtension.class)
class SkillToMcpBridgeTest {

    @Mock
    private DynamicToolRegistry mockRegistry;

    private SkillToMcpBridge bridge;

    /** 可导出工具。 */
    private BuiltinTool exportableTool;

    /** 不可导出工具。 */
    private BuiltinTool nonExportableTool;

    @BeforeEach
    void setUp() {
        bridge = new SkillToMcpBridge(mockRegistry);

        exportableTool = BuiltinTool.builder()
                .id("builtin.weather")
                .name("weather-query")
                .description("查询天气")
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .exportable(true)
                .executor(input -> ToolResult.success(Map.of("temp", "25°C")))
                .build();

        nonExportableTool = BuiltinTool.builder()
                .id("builtin.internal")
                .name("internal-tool")
                .description("内部工具")
                .riskLevel(RiskLevel.HIGH)
                .exportable(false)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    // ─────────────────────────────────────────────
    //  listExportableTools 测试（CP-6）
    // ─────────────────────────────────────────────

    @Test
    void listExportableTools_仅返回exportable工具() {
        when(mockRegistry.getAllTools()).thenReturn(
                List.of(exportableTool, nonExportableTool));

        var tools = bridge.listExportableTools();

        assertEquals(1, tools.size());
        assertEquals("weather-query", tools.get(0).name());
        assertEquals("查询天气", tools.get(0).description());
    }

    @Test
    void listExportableTools_无可导出工具时返回空列表() {
        when(mockRegistry.getAllTools()).thenReturn(List.of(nonExportableTool));

        var tools = bridge.listExportableTools();

        assertTrue(tools.isEmpty());
    }

    // ─────────────────────────────────────────────
    //  RiskLevel → annotations 映射测试
    // ─────────────────────────────────────────────

    @Test
    void listExportableTools_LOW风险映射readOnlyHint为true() {
        var lowTool = BuiltinTool.builder()
                .id("builtin.read").name("read-tool").description("只读")
                .riskLevel(RiskLevel.LOW).exportable(true)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(lowTool));

        var tools = bridge.listExportableTools();
        var annotations = tools.get(0).annotations();

        assertTrue(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
    }

    @Test
    void listExportableTools_MEDIUM风险映射非只读非破坏性() {
        var medTool = BuiltinTool.builder()
                .id("builtin.med").name("med-tool").description("中风险")
                .riskLevel(RiskLevel.MEDIUM).exportable(true)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(medTool));

        var tools = bridge.listExportableTools();
        var annotations = tools.get(0).annotations();

        assertFalse(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
    }

    @Test
    void listExportableTools_HIGH风险映射destructiveHint为true() {
        var highTool = BuiltinTool.builder()
                .id("builtin.high").name("high-tool").description("高风险")
                .riskLevel(RiskLevel.HIGH).exportable(true)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(highTool));

        var tools = bridge.listExportableTools();
        var annotations = tools.get(0).annotations();

        assertFalse(annotations.readOnlyHint());
        assertTrue(annotations.destructiveHint());
    }

    @Test
    void listExportableTools_CRITICAL风险映射destructiveHint为true() {
        var critTool = BuiltinTool.builder()
                .id("builtin.crit").name("crit-tool").description("关键风险")
                .riskLevel(RiskLevel.CRITICAL).exportable(true)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(critTool));

        var tools = bridge.listExportableTools();
        var annotations = tools.get(0).annotations();

        assertFalse(annotations.readOnlyHint());
        assertTrue(annotations.destructiveHint());
    }

    @Test
    void listExportableTools_幂等工具映射idempotentHint为true() {
        when(mockRegistry.getAllTools()).thenReturn(List.of(exportableTool));

        var tools = bridge.listExportableTools();

        assertTrue(tools.get(0).annotations().idempotentHint());
    }

    // ─────────────────────────────────────────────
    //  handleToolCall 测试
    // ─────────────────────────────────────────────

    @Test
    void handleToolCall_成功调用返回结果() {
        when(mockRegistry.getAllTools()).thenReturn(
                List.of(exportableTool, nonExportableTool));

        var result = bridge.handleToolCall("weather-query", Map.of());

        assertFalse(result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void handleToolCall_工具不存在返回错误() {
        when(mockRegistry.getAllTools()).thenReturn(List.of(exportableTool));

        var result = bridge.handleToolCall("non-existent", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("不存在"));
    }

    @Test
    void handleToolCall_不可导出工具返回错误() {
        when(mockRegistry.getAllTools()).thenReturn(
                List.of(exportableTool, nonExportableTool));

        var result = bridge.handleToolCall("internal-tool", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("不可导出"));
    }

    @Test
    void handleToolCall_工具执行失败返回错误() {
        var failTool = BuiltinTool.builder()
                .id("builtin.fail").name("fail-tool").description("会失败的工具")
                .riskLevel(RiskLevel.LOW).exportable(true)
                .executor(input -> ToolResult.error("执行失败"))
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(failTool));

        var result = bridge.handleToolCall("fail-tool", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("执行失败"));
    }

    @Test
    void handleToolCall_工具抛异常返回错误() {
        var exTool = BuiltinTool.builder()
                .id("builtin.ex").name("ex-tool").description("抛异常的工具")
                .riskLevel(RiskLevel.LOW).exportable(true)
                .executor(input -> { throw new RuntimeException("意外错误"); })
                .build();
        when(mockRegistry.getAllTools()).thenReturn(List.of(exTool));

        var result = bridge.handleToolCall("ex-tool", Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("意外错误"));
    }

    // ─────────────────────────────────────────────
    //  handleInitialize 测试
    // ─────────────────────────────────────────────

    @Test
    void handleInitialize_返回协议版本和服务端信息() {
        var init = bridge.handleInitialize();

        assertEquals("2025-06-18", init.get("protocolVersion"));
        assertNotNull(init.get("capabilities"));
        assertNotNull(init.get("serverInfo"));
    }
}
