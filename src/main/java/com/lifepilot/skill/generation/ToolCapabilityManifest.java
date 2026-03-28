package com.lifepilot.skill.generation;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具能力清单 — 为 SkillGenerator 提供按分组组织的工具能力描述。
 *
 * <p>从 DynamicToolRegistry 拉取所有已注册工具，按 ToolCategory 分组，
 * 生成结构化的能力清单文本，注入到 SkillGenerator 的 Prompt 中。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ToolCapabilityManifest {

    private final DynamicToolRegistry toolRegistry;

    public ToolCapabilityManifest(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 构建工具能力清单文本。
     *
     * <p>按 ToolCategory 枚举顺序遍历所有分组，跳过无工具的分组，
     * 为每个非空分组输出标题行和工具条目。</p>
     *
     * <p>Format:</p>
     * <pre>
     * ## 感知（获取外部世界的信息）
     * - web.search: Web 搜索
     * - file.read: 读取文件
     *
     * ## 行动（改变外部世界的状态）
     * - file.write: 写入文件
     * </pre>
     *
     * @return 格式化的能力清单文本
     */
    public String buildManifest() {
        var tools = toolRegistry.getToolSnapshot();
        var grouped = tools.stream()
                .collect(Collectors.groupingBy(ToolContract::category));

        var sb = new StringBuilder();
        for (ToolCategory category : ToolCategory.values()) {
            var categoryTools = grouped.getOrDefault(category, List.of());
            if (categoryTools.isEmpty()) continue;
            sb.append("## ").append(category.displayName())
              .append("（").append(category.description()).append("）\n");
            for (var tool : categoryTools) {
                sb.append("- ").append(tool.id()).append(": ").append(tool.description()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
