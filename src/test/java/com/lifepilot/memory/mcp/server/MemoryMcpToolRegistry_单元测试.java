package com.lifepilot.memory.mcp.server;

import com.lifepilot.memory.governance.server.MemoryMcpToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryMcpToolRegistry 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class MemoryMcpToolRegistry_单元测试 {

    private final MemoryMcpToolRegistry registry = new MemoryMcpToolRegistry();

    @Test
    void listTools_返回3个工具() {
        List<Map<String, Object>> tools = registry.listTools();
        assertThat(tools).hasSize(3);
    }

    @Test
    void 每个工具有name_description_inputSchema() {
        for (var tool : registry.listTools()) {
            assertThat(tool).containsKeys("name", "description", "inputSchema");
            assertThat(tool.get("name")).isNotNull();
            assertThat(tool.get("description")).isNotNull();
            assertThat(tool.get("inputSchema")).isInstanceOf(Map.class);
        }
    }

    @Test
    void inputSchema是合法json_schema() {
        for (var tool : registry.listTools()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isInstanceOf(Map.class);
            assertThat(schema.get("required")).isInstanceOf(List.class);
        }
    }

    @Test
    void 工具名字唯一且符合约定() {
        var names = registry.listTools().stream().map(t -> t.get("name")).toList();
        assertThat(names).containsExactly("memory_search", "memory_recall", "memory_create");
    }
}
