package com.lifepilot.mcp.adapter;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.model.McpToolAnnotations;
import com.lifepilot.mcp.model.McpToolSchema;
import com.lifepilot.observability.guardrail.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpToolAdapter 单元测试。
 *
 * <p>穷举 annotations 组合验证风险等级推断一致性（CP-2）、
 * ID 格式验证（CP-1）、单个工具转换失败不影响其他工具。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ExtendWith(MockitoExtension.class)
class McpToolAdapterTest {

    private McpToolAdapter adapter;

    @Mock
    private McpClient mockClient;

    @BeforeEach
    void setUp() {
        adapter = new McpToolAdapter();
    }

    // ─────────────────────────────────────────────
    //  CP-1: 工具 ID 唯一性
    // ─────────────────────────────────────────────

    @Test
    void generateToolId_格式为mcp点serverName点toolName() {
        String id = adapter.generateToolId("filesystem", "read_file");
        assertEquals("mcp.filesystem.read_file", id);
    }

    @Test
    void generateToolId_不同server和tool生成不同ID() {
        String id1 = adapter.generateToolId("server-a", "tool-1");
        String id2 = adapter.generateToolId("server-b", "tool-1");
        String id3 = adapter.generateToolId("server-a", "tool-2");

        assertNotEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertNotEquals(id2, id3);
    }

    // ─────────────────────────────────────────────
    //  CP-2: 风险等级推断一致性（穷举 annotations 组合）
    // ─────────────────────────────────────────────

    @Test
    void inferRiskLevel_无annotations_返回MEDIUM() {
        var schema = new McpToolSchema("tool", "描述", null, null);
        assertEquals(RiskLevel.MEDIUM, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_readOnly为true_返回LOW() {
        var annotations = new McpToolAnnotations(null, true, null, null, null);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.LOW, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_readOnly为true且destructive为true_readOnly优先_返回LOW() {
        // readOnly 优先级最高
        var annotations = new McpToolAnnotations(null, true, true, null, null);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.LOW, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_destructive为true_返回HIGH() {
        var annotations = new McpToolAnnotations(null, false, true, null, null);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.HIGH, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_destructive且openWorld为true_返回CRITICAL() {
        var annotations = new McpToolAnnotations(null, false, true, null, true);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.CRITICAL, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_仅openWorld为true_返回MEDIUM() {
        var annotations = new McpToolAnnotations(null, null, null, null, true);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.MEDIUM, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_所有hint为false_返回MEDIUM() {
        var annotations = new McpToolAnnotations(null, false, false, false, false);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.MEDIUM, adapter.inferRiskLevel(schema));
    }

    @Test
    void inferRiskLevel_所有hint为null_返回MEDIUM() {
        var annotations = new McpToolAnnotations(null, null, null, null, null);
        var schema = new McpToolSchema("tool", "描述", null, annotations);
        assertEquals(RiskLevel.MEDIUM, adapter.inferRiskLevel(schema));
    }

    // ─────────────────────────────────────────────
    //  toToolContract 单个转换
    // ─────────────────────────────────────────────

    @Test
    void toToolContract_基本转换() {
        var schema = new McpToolSchema("read_file", "读取文件",
                Map.of("type", "object"), null);

        var tool = adapter.toToolContract("fs", schema);

        assertEquals("mcp.fs.read_file", tool.id());
        assertEquals("read_file", tool.name());
        assertEquals("读取文件", tool.description());
        assertEquals(RiskLevel.MEDIUM, tool.riskLevel());
        assertFalse(tool.idempotent());
        assertTrue(tool.tags().contains("mcp"));
        assertTrue(tool.tags().contains("mcp:fs"));
    }

    @Test
    void toToolContract_无description时使用默认描述() {
        var schema = new McpToolSchema("my_tool", null, null, null);

        var tool = adapter.toToolContract("server", schema);

        assertEquals("MCP 工具: my_tool", tool.description());
    }

    @Test
    void toToolContract_幂等性从annotations推断() {
        var annotations = new McpToolAnnotations(null, null, null, true, null);
        var schema = new McpToolSchema("tool", "描述", null, annotations);

        var tool = adapter.toToolContract("server", schema);

        assertTrue(tool.idempotent());
    }

    @Test
    void toToolContract_HIGH风险预算更严格() {
        var annotations = new McpToolAnnotations(null, false, true, null, null);
        var schema = new McpToolSchema("delete_all", "删除全部", null, annotations);

        var tool = adapter.toToolContract("server", schema);

        assertEquals(RiskLevel.HIGH, tool.riskLevel());
        // HIGH 风险：30 秒超时，1 次重试
        assertEquals(30, tool.budget().timeout().getSeconds());
        assertEquals(1, tool.budget().maxRetries());
    }

    @Test
    void toToolContract_CRITICAL风险预算最严格() {
        var annotations = new McpToolAnnotations(null, false, true, null, true);
        var schema = new McpToolSchema("nuke", "核弹", null, annotations);

        var tool = adapter.toToolContract("server", schema);

        assertEquals(RiskLevel.CRITICAL, tool.riskLevel());
        // CRITICAL 风险：15 秒超时，0 次重试
        assertEquals(15, tool.budget().timeout().getSeconds());
        assertEquals(0, tool.budget().maxRetries());
    }

    @Test
    void toToolContract_标签包含readOnly标记() {
        var annotations = new McpToolAnnotations(null, true, null, null, null);
        var schema = new McpToolSchema("list", "列表", null, annotations);

        var tool = adapter.toToolContract("server", schema);

        assertTrue(tool.tags().contains("read-only"));
        assertFalse(tool.tags().contains("destructive"));
    }

    @Test
    void toToolContract_标签包含destructive标记() {
        var annotations = new McpToolAnnotations(null, false, true, null, null);
        var schema = new McpToolSchema("delete", "删除", null, annotations);

        var tool = adapter.toToolContract("server", schema);

        assertTrue(tool.tags().contains("destructive"));
        assertFalse(tool.tags().contains("read-only"));
    }

    // ─────────────────────────────────────────────
    //  toToolContracts 批量转换
    // ─────────────────────────────────────────────

    @Test
    void toToolContracts_批量转换() {
        var schemas = List.of(
                new McpToolSchema("tool_a", "工具A", null, null),
                new McpToolSchema("tool_b", "工具B", null, null)
        );

        var tools = adapter.toToolContracts("server", schemas, mockClient);

        assertEquals(2, tools.size());
        assertEquals("mcp.server.tool_a", tools.get(0).id());
        assertEquals("mcp.server.tool_b", tools.get(1).id());
    }

    @Test
    void toToolContracts_单个转换失败不影响其他工具() {
        // 使用 Mockito mock 一个会抛异常的 schema
        var badSchema = org.mockito.Mockito.mock(McpToolSchema.class);
        org.mockito.Mockito.when(badSchema.name()).thenThrow(new RuntimeException("模拟转换失败"));

        var schemas = List.of(
                new McpToolSchema("good_tool", "正常工具", null, null),
                badSchema,
                new McpToolSchema("another_good", "另一个正常工具", null, null)
        );

        var tools = adapter.toToolContracts("server", schemas, mockClient);

        // 坏工具被跳过，其他两个正常转换
        assertEquals(2, tools.size());
        assertEquals("mcp.server.good_tool", tools.get(0).id());
        assertEquals("mcp.server.another_good", tools.get(1).id());
    }

    @Test
    void toToolContracts_空列表返回空列表() {
        var tools = adapter.toToolContracts("server", List.of(), mockClient);
        assertTrue(tools.isEmpty());
    }
}
