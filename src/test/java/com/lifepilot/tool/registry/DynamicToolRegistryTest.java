package com.lifepilot.tool.registry;

import com.lifepilot.guardrail.GuardrailPolicy;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.*;
import com.lifepilot.tool.schema.JsonSchema;
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
    private GuardrailPolicy guardrailPolicy;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        guardrailPolicy = new GuardrailPolicy();
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new DynamicToolRegistry(guardrailPolicy, eventPublisher);
    }

    @Test
    void 注册Builtin工具_成功解析() {
        BuiltinTool tool = createBuiltinTool("test.echo", "Echo");
        registry.registerBuiltinTool(tool);

        var resolved = registry.resolve("test.echo");
        assertTrue(resolved.isPresent());
        assertEquals("Echo", resolved.get().name());
        assertEquals(1, registry.getToolCount());
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
        assertEquals(1, registry.getToolCount());
    }

    @Test
    void 同层冲突_跳过注册() {
        BuiltinTool tool1 = createBuiltinTool("test.echo", "Echo 1");
        BuiltinTool tool2 = createBuiltinTool("test.echo", "Echo 2");

        registry.registerBuiltinTool(tool1);
        registry.registerBuiltinTool(tool2);

        // 同层不覆盖，保留第一个
        assertEquals("Echo 1", registry.resolve("test.echo").orElseThrow().name());
        assertEquals(1, registry.getToolCount());
    }

    @Test
    void 注销MCP工具() {
        McpTool tool1 = createMcpTool("mcp.tool1", "Tool1");
        McpTool tool2 = createMcpTool("mcp.tool2", "Tool2");
        registry.registerMcpTools("server1", List.of(tool1, tool2));
        assertEquals(2, registry.getToolCount());

        registry.unregisterMcpTools("server1");
        assertEquals(0, registry.getToolCount());
        assertTrue(registry.resolve("mcp.tool1").isEmpty());
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
    void 按层次过滤工具() {
        registry.registerBuiltinTool(createBuiltinTool("builtin.a", "A"));
        registry.registerMcpTools("s1", List.of(createMcpTool("mcp.b", "B")));

        assertEquals(1, registry.getToolsByLayer(ToolLayer.JAVA_NATIVE).size());
        assertEquals(1, registry.getToolsByLayer(ToolLayer.MCP_EXTERNAL).size());
        assertEquals(0, registry.getToolsByLayer(ToolLayer.YAML_DECLARATIVE).size());
    }

    @Test
    void 注册工具自动加入白名单() {
        BuiltinTool tool = createBuiltinTool("test.echo", "Echo");
        registry.registerBuiltinTool(tool);
        assertTrue(guardrailPolicy.isAllowed("test.echo"));
    }

    @Test
    void 注销工具自动移出白名单() {
        McpTool tool = createMcpTool("mcp.tool", "Tool");
        registry.registerMcpTools("server1", List.of(tool));
        assertTrue(guardrailPolicy.isAllowed("mcp.tool"));

        registry.unregisterMcpTools("server1");
        assertFalse(guardrailPolicy.isAllowed("mcp.tool"));
    }

    // ─── 辅助方法 ───

    private BuiltinTool createBuiltinTool(String id, String name) {
        return BuiltinTool.builder()
                .id(id).name(name).description("测试工具")
                .inputSchema(JsonSchema.empty()).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(true)
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(input -> ToolResult.success(java.util.Map.of("echo", "ok")))
                .build();
    }

    private McpTool createMcpTool(String id, String name) {
        return new McpTool(id, name, "测试 MCP 工具",
                JsonSchema.empty(), JsonSchema.empty(),
                RiskLevel.LOW, true, ToolBudget.MCP_DEFAULT,
                List.of(), "test-server", id);
    }
}
