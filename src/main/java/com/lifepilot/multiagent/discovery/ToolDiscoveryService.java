package com.lifepilot.multiagent.discovery;

import com.lifepilot.multiagent.execution.HandoffToolFactory;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;

import java.util.List;

/**
 * 工具发现服务 — 列出所有可用工具（排除 HandoffTool）。
 *
 * <p>从 {@link DynamicToolRegistry} 获取所有工具，过滤掉 ID 以
 * {@code handoff_to_} 开头的 HandoffTool（避免循环引用），
 * 按来源类型分类返回。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class ToolDiscoveryService {

    private final DynamicToolRegistry toolRegistry;

    public ToolDiscoveryService(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 工具摘要。
     *
     * @param toolId     工具 ID
     * @param name       工具名称
     * @param description 工具描述
     * @param sourceType 来源类型（builtin / skill / mcp）
     */
    public record ToolSummary(String toolId, String name, String description, String sourceType) {}

    /**
     * 列出所有可用工具（排除 HandoffTool）。
     *
     * @return 工具摘要列表
     */
    public List<ToolSummary> listAvailableTools() {
        return toolRegistry.getAllTools().stream()
                .filter(tool -> !tool.id().startsWith(HandoffToolFactory.TOOL_ID_PREFIX))
                .map(this::toSummary)
                .toList();
    }

    /** 将 ToolContract 转换为 ToolSummary。 */
    private ToolSummary toSummary(ToolContract tool) {
        String sourceType = switch (tool) {
            case BuiltinTool _ -> "builtin";
            case McpTool _ -> "mcp";
        };
        return new ToolSummary(tool.id(), tool.name(), tool.description(), sourceType);
    }
}
