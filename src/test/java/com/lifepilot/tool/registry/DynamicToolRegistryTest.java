package com.lifepilot.tool.registry;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DynamicToolRegistry 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class DynamicToolRegistryTest {

    private DynamicToolRegistry registry;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new DynamicToolRegistry(eventPublisher);
    }

    @Test
    void 注册Builtin工具_成功解析() {
        BuiltinTool tool = createBuiltinTool("test.echo", "Echo");
        registry.registerBuiltinTool(tool);

        var resolved = registry.resolve("test.echo");
        assertTrue(resolved.isPresent());
        assertEquals("Echo", resolved.get().name());
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    void 高优先级层覆盖低优先级层() {
        // MCP 工具先注册
        McpTool mcpTool = createMcpTool("test.echo", "Echo MCP");
        registry.registerMcpTools("server1", List.of(mcpTool));
        assertEquals("Echo MCP", registry.resolve("test.echo").orElseThrow().name());

        // Builtin 工具覆盖 MCP
        BuiltinTool builtinTool = createBuiltinTool("test.echo", "Echo Builtin");
        registry.registerBuiltinTool(builtinTool);
        assertEquals("Echo Builtin", registry.resolve("test.echo").orElseThrow().name());
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    void 同层冲突_跳过注册() {
        BuiltinTool tool1 = createBuiltinTool("test.echo", "Echo 1");
        BuiltinTool tool2 = createBuiltinTool("test.echo", "Echo 2");

        registry.registerBuiltinTool(tool1);
        registry.registerBuiltinTool(tool2);

        // 同层不覆盖，保留第一个
        assertEquals("Echo 1", registry.resolve("test.echo").orElseThrow().name());
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    void 注销MCP工具() {
        McpTool tool1 = createMcpTool("mcp.tool1", "Tool1");
        McpTool tool2 = createMcpTool("mcp.tool2", "Tool2");
        registry.registerMcpTools("server1", List.of(tool1, tool2));
        assertEquals(2, registry.getAllTools().size());

        registry.unregisterMcpTools("server1");
        assertEquals(0, registry.getAllTools().size());
        assertTrue(registry.resolve("mcp.tool1").isEmpty());
    }

    @Test
    void 通用注销可移除单个Builtin工具() {
        BuiltinTool tool = createBuiltinTool("echo", "Echo");
        registry.registerBuiltinTool(tool);

        assertTrue(registry.unregisterTool("echo"));
        assertTrue(registry.resolve("echo").isEmpty());
        assertEquals(0, registry.getAllTools().size());
    }

    @Test
    void 通用注销可移除单个Mcp工具且更新Server索引() {
        McpTool tool1 = createMcpTool("mcp.tool1", "Tool1");
        McpTool tool2 = createMcpTool("mcp.tool2", "Tool2");
        registry.registerMcpTools("server1", List.of(tool1, tool2));

        assertTrue(registry.unregisterTool("mcp.tool1"));
        assertTrue(registry.resolve("mcp.tool1").isEmpty());
        assertTrue(registry.resolve("mcp.tool2").isPresent());
        assertEquals(List.of("mcp.tool2"),
                registry.getToolsByServer("server1").stream().map(ToolContract::id).toList());
    }

    @Test
    void 快照不可变性() {
        BuiltinTool tool = createBuiltinTool("test.echo", "Echo");
        registry.registerBuiltinTool(tool);

        List<ToolContract> snapshot = registry.getToolSnapshot();
        assertEquals(1, snapshot.size());

        // 快照是不可变的
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(tool));
    }

    @Test
    void 工具快照应按工具ID稳定排序() {
        registry.registerBuiltinTool(createBuiltinTool("z.tool", "Z"));
        registry.registerBuiltinTool(createBuiltinTool("a.tool", "A"));
        registry.registerBuiltinTool(createBuiltinTool("m.tool", "M"));

        List<String> toolIds = registry.getToolSnapshot().stream()
                .map(ToolContract::id)
                .toList();

        assertEquals(List.of("a.tool", "m.tool", "z.tool"), toolIds);
    }

    @Test
    void 按层次过滤工具() {
        registry.registerBuiltinTool(createBuiltinTool("a", "A"));
        registry.registerMcpTools("s1", List.of(createMcpTool("mcp.b", "B")));

        var counts = registry.getToolCountByLayer();
        assertEquals(1, counts.getOrDefault(ToolLayer.JAVA_NATIVE, 0));
        assertEquals(1, counts.getOrDefault(ToolLayer.MCP_EXTERNAL, 0));
    }

    // ─── 辅助方法 ───

    private BuiltinTool createBuiltinTool(String id, String name) {
        return BuiltinTool.builder()
                .id(id).name(name).description("测试工具")
                .inputSchema(JsonSchema.empty()).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(input -> ToolResult.success(java.util.Map.of("echo", "ok")))
                .build();
    }

    private McpTool createMcpTool(String id, String name) {
        return new McpTool(id, name, "测试 MCP 工具",
                JsonSchema.empty(), JsonSchema.empty(),
                RiskLevel.LOW, true, ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL), ToolBudget.MCP_DEFAULT,
                List.of(), "test-server", id, "test-server");
    }
}
