package com.lifepilot.memory.governance.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory MCP Server 对外暴露的工具定义清单（静态）。
 *
 * <p>返回 MCP 协议标准的工具格式：{@code {name, description, inputSchema}}。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class MemoryMcpToolRegistry {

    public static final String TOOL_SEARCH = "memory_search";
    public static final String TOOL_RECALL = "memory_recall";
    public static final String TOOL_CREATE = "memory_create";

    /**
     * 返回工具清单（用于 MCP tools/list 响应的 tools 字段）。
     */
    public List<Map<String, Object>> listTools() {
        return List.of(
                buildSearchTool(),
                buildRecallTool(),
                buildCreateTool()
        );
    }

    private Map<String, Object> buildSearchTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词"
        ));
        properties.put("topK", Map.of(
                "type", "integer",
                "description", "返回结果数量上限，默认 10",
                "default", 10
        ));
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("query"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", TOOL_SEARCH);
        tool.put("description", "搜索用户长期语义记忆（基于向量 + FTS + 图的融合检索）");
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private Map<String, Object> buildRecallTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "关键词或语义查询"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "返回对话数量上限，默认 5",
                "default", 5
        ));
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("query"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", TOOL_RECALL);
        tool.put("description", "回忆历史对话（按关键词 FTS 搜索情景记忆）");
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private Map<String, Object> buildCreateTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "实体名称"
        ));
        properties.put("entityType", Map.of(
                "type", "string",
                "description", "实体类型：PERSON / ORGANIZATION / PLACE / EVENT / PROJECT / TOPIC / PREFERENCE / HABIT / GOAL / SKILL / EXPERIENCE / CUSTOM"
        ));
        properties.put("description", Map.of(
                "type", "string",
                "description", "可选的详细描述"
        ));
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("name", "entityType"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", TOOL_CREATE);
        tool.put("description", "创建新的语义记忆实体");
        tool.put("inputSchema", inputSchema);
        return tool;
    }
}
