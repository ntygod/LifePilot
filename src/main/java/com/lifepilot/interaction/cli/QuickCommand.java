package com.lifepilot.interaction.cli;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.mcp.registry.McpServerEntry;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快捷命令分发器 — 直接调用各模块 API。
 *
 * <p>支持 todo/schedule/habit/llm/mcp/skill 快捷命令，
 * 通过 DynamicToolRegistry 和各模块注册中心执行操作。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class QuickCommand {

    private static final Logger log = LoggerFactory.getLogger(QuickCommand.class);

    private final DynamicToolRegistry toolRegistry;
    private final ProviderRegistry providerRegistry;
    private final McpServerRegistry mcpServerRegistry;
    private final SkillRegistry skillRegistry;

    public QuickCommand(DynamicToolRegistry toolRegistry,
                        ProviderRegistry providerRegistry,
                        McpServerRegistry mcpServerRegistry,
                        SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 执行快捷命令。
     *
     * @param command 命令名（todo/schedule/habit/llm/mcp/skill）
     * @param subArgs 子命令和参数
     * @param renderer 响应渲染器
     */
    public void execute(String command, String[] subArgs, ResponseRenderer renderer) {
        try {
            switch (command) {
                case "todo" -> handleBuiltinTool("todo", subArgs, renderer);
                case "schedule" -> handleBuiltinTool("schedule", subArgs, renderer);
                case "habit" -> handleBuiltinTool("habit", subArgs, renderer);
                case "llm" -> handleLlm(subArgs, renderer);
                case "mcp" -> handleMcp(subArgs, renderer);
                case "skill" -> handleSkill(subArgs, renderer);
                default -> renderer.error("未知命令: " + command);
            }
        } catch (Exception e) {
            log.warn("快捷命令执行失败: command={}, error={}", command, e.getMessage());
            renderer.error("命令执行失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  内置工具命令（todo / schedule / habit）
    // ─────────────────────────────────────────────

    /**
     * 处理内置工具命令（todo/schedule/habit）。
     *
     * @param domain 领域名（todo/schedule/habit）
     * @param subArgs 子命令和参数
     * @param renderer 响应渲染器
     */
    private void handleBuiltinTool(String domain, String[] subArgs, ResponseRenderer renderer) {
        if (subArgs.length == 0) {
            printBuiltinUsage(domain, renderer);
            return;
        }

        String subCommand = subArgs[0].toLowerCase();
        switch (subCommand) {
            case "list" -> executeToolCommand("builtin." + domain + ".list", Map.of(), renderer);
            case "add" -> {
                if (subArgs.length < 2) {
                    renderer.error("缺少参数: " + domain + " add <内容>");
                    printBuiltinUsage(domain, renderer);
                    return;
                }
                // 拼接 add 后面的所有参数作为内容
                String content = String.join(" ", java.util.Arrays.copyOfRange(subArgs, 1, subArgs.length));
                executeToolCommand("builtin." + domain + ".create", Map.of("content", content), renderer);
            }
            default -> {
                renderer.error("未知子命令: " + domain + " " + subCommand);
                printBuiltinUsage(domain, renderer);
            }
        }
    }

    /**
     * 通过 DynamicToolRegistry 执行工具命令。
     *
     * @param toolId 工具 ID
     * @param parameters 参数
     * @param renderer 响应渲染器
     */
    private void executeToolCommand(String toolId, Map<String, Object> parameters, ResponseRenderer renderer) {
        Optional<ToolContract> toolOpt = toolRegistry.resolve(toolId);
        if (toolOpt.isEmpty()) {
            renderer.error("工具未注册: " + toolId);
            return;
        }

        ToolContract tool = toolOpt.get();
        var input = new ToolInput(toolId, parameters, JsonSchema.empty(), null);
        ToolResult result = tool.execute(input);

        if (result.ok()) {
            // 输出结果数据
            Map<String, Object> data = result.data();
            if (data.containsKey("items")) {
                // 列表类结果，尝试表格输出
                @SuppressWarnings("unchecked")
                var items = (List<Map<String, Object>>) data.get("items");
                renderItemList(items, renderer);
            } else if (data.containsKey("message")) {
                renderer.success(String.valueOf(data.get("message")));
            } else if (!data.isEmpty()) {
                renderer.success(data.toString());
            } else {
                renderer.success("执行成功");
            }
        } else {
            renderer.error("执行失败: " + result.error());
        }
    }

    /**
     * 渲染列表项为表格。
     */
    private void renderItemList(List<Map<String, Object>> items, ResponseRenderer renderer) {
        if (items.isEmpty()) {
            renderer.info("（无数据）");
            return;
        }
        // 提取所有键作为表头
        List<String> headers = new ArrayList<>(items.getFirst().keySet());
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            List<String> row = new ArrayList<>();
            for (String header : headers) {
                Object value = item.get(header);
                row.add(value != null ? String.valueOf(value) : "");
            }
            rows.add(row);
        }
        renderer.table(headers, rows);
    }

    /**
     * 输出内置工具命令的用法帮助。
     */
    private void printBuiltinUsage(String domain, ResponseRenderer renderer) {
        renderer.info("");
        renderer.info("用法: " + domain + " <子命令>");
        renderer.info("");
        renderer.info("可用子命令:");
        renderer.info("  list              列出所有" + domainLabel(domain));
        renderer.info("  add <内容>        添加" + domainLabel(domain));
    }

    /**
     * 获取领域的中文标签。
     */
    private String domainLabel(String domain) {
        return switch (domain) {
            case "todo" -> "待办";
            case "schedule" -> "日程";
            case "habit" -> "习惯";
            default -> domain;
        };
    }

    // ─────────────────────────────────────────────
    //  LLM 命令
    // ─────────────────────────────────────────────

    /**
     * 处理 llm 命令。
     */
    private void handleLlm(String[] subArgs, ResponseRenderer renderer) {
        if (subArgs.length == 0) {
            printLlmUsage(renderer);
            return;
        }

        String subCommand = subArgs[0].toLowerCase();
        switch (subCommand) {
            case "list" -> llmList(renderer);
            case "test" -> {
                if (subArgs.length < 2) {
                    renderer.error("缺少参数: llm test <providerId>");
                    printLlmUsage(renderer);
                    return;
                }
                llmTest(subArgs[1], renderer);
            }
            default -> {
                renderer.error("未知子命令: llm " + subCommand);
                printLlmUsage(renderer);
            }
        }
    }

    /**
     * 列出所有 LLM Provider。
     */
    private void llmList(ResponseRenderer renderer) {
        var ids = providerRegistry.registeredIds();
        if (ids.isEmpty()) {
            renderer.info("（无已注册的 LLM Provider）");
            return;
        }

        List<String> headers = List.of("ID", "类型", "模型", "优先级", "已启用");
        List<List<String>> rows = new ArrayList<>();
        for (String id : ids) {
            providerRegistry.getConfig(id).ifPresent(config -> {
                rows.add(List.of(
                        config.id(),
                        config.type().name(),
                        config.modelName(),
                        String.valueOf(config.priority()),
                        config.enabled() ? "是" : "否"
                ));
            });
        }
        renderer.table(headers, rows);
    }

    /**
     * 测试指定 LLM Provider 的连通性。
     */
    private void llmTest(String providerId, ResponseRenderer renderer) {
        Optional<ProviderConfig> configOpt = providerRegistry.getConfig(providerId);
        if (configOpt.isEmpty()) {
            renderer.error("Provider 未注册: " + providerId);
            return;
        }

        renderer.info("正在测试 Provider: " + providerId + " ...");
        try {
            long startMs = System.currentTimeMillis();
            boolean healthy = providerRegistry.getAdapter(providerId).healthCheck();
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (healthy) {
                renderer.success("Provider " + providerId + " 连通正常（延迟 " + elapsedMs + "ms）");
            } else {
                renderer.error("Provider " + providerId + " 健康检查失败");
            }
        } catch (Exception e) {
            log.warn("LLM 健康检查异常: providerId={}, error={}", providerId, e.getMessage());
            renderer.error("Provider " + providerId + " 测试异常: " + e.getMessage());
        }
    }

    /**
     * 输出 llm 命令的用法帮助。
     */
    private void printLlmUsage(ResponseRenderer renderer) {
        renderer.info("");
        renderer.info("用法: llm <子命令>");
        renderer.info("");
        renderer.info("可用子命令:");
        renderer.info("  list              列出所有 LLM Provider");
        renderer.info("  test <id>         测试 Provider 连通性");
    }

    // ─────────────────────────────────────────────
    //  MCP 命令
    // ─────────────────────────────────────────────

    /**
     * 处理 mcp 命令。
     */
    private void handleMcp(String[] subArgs, ResponseRenderer renderer) {
        if (subArgs.length == 0) {
            printMcpUsage(renderer);
            return;
        }

        String subCommand = subArgs[0].toLowerCase();
        switch (subCommand) {
            case "list" -> mcpList(renderer);
            default -> {
                renderer.error("未知子命令: mcp " + subCommand);
                printMcpUsage(renderer);
            }
        }
    }

    /**
     * 列出所有 MCP Server。
     */
    private void mcpList(ResponseRenderer renderer) {
        List<McpServerEntry> servers = mcpServerRegistry.listServers();
        if (servers.isEmpty()) {
            renderer.info("（无已配置的 MCP Server）");
            return;
        }

        List<String> headers = List.of("名称", "传输类型", "状态", "可用");
        List<List<String>> rows = new ArrayList<>();
        for (McpServerEntry entry : servers) {
            rows.add(List.of(
                    entry.config().name(),
                    entry.config().transport().name(),
                    entry.state().name(),
                    entry.state().isAvailable() ? "是" : "否"
            ));
        }
        renderer.table(headers, rows);
    }

    /**
     * 输出 mcp 命令的用法帮助。
     */
    private void printMcpUsage(ResponseRenderer renderer) {
        renderer.info("");
        renderer.info("用法: mcp <子命令>");
        renderer.info("");
        renderer.info("可用子命令:");
        renderer.info("  list              列出所有 MCP Server");
    }

    // ─────────────────────────────────────────────
    //  Skill 命令
    // ─────────────────────────────────────────────

    /**
     * 处理 skill 命令。
     */
    private void handleSkill(String[] subArgs, ResponseRenderer renderer) {
        if (subArgs.length == 0) {
            printSkillUsage(renderer);
            return;
        }

        String subCommand = subArgs[0].toLowerCase();
        switch (subCommand) {
            case "list" -> skillList(renderer);
            case "info" -> {
                if (subArgs.length < 2) {
                    renderer.error("缺少参数: skill info <skillId>");
                    printSkillUsage(renderer);
                    return;
                }
                skillInfo(subArgs[1], renderer);
            }
            default -> {
                renderer.error("未知子命令: skill " + subCommand);
                printSkillUsage(renderer);
            }
        }
    }

    /**
     * 列出所有已注册 Skill 的摘要。
     */
    private void skillList(ResponseRenderer renderer) {
        List<String> summaries = skillRegistry.listSummaries();
        if (summaries.isEmpty()) {
            renderer.info("（无已注册的 Skill）");
            return;
        }

        List<String> headers = List.of("序号", "摘要");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i++) {
            rows.add(List.of(String.valueOf(i + 1), summaries.get(i)));
        }
        renderer.table(headers, rows);
    }

    /**
     * 输出指定 Skill 的详细信息。
     */
    private void skillInfo(String skillId, ResponseRenderer renderer) {
        Optional<SkillDefinition> skillOpt = skillRegistry.find(skillId);
        if (skillOpt.isEmpty()) {
            renderer.error("Skill 未找到: " + skillId);
            return;
        }

        SkillDefinition skill = skillOpt.get();
        renderer.info("Skill 详细信息:");
        renderer.info("  ID:          " + skill.id());
        renderer.info("  名称:        " + skill.name());
        renderer.info("  描述:        " + skill.description());
        renderer.info("  版本:        " + skill.version());
        renderer.info("  来源:        " + skill.source());
        renderer.info("  允许工具:    " + String.join(", ", skill.allowedTools()));
        if (skill.preferredProviderId() != null) {
            renderer.info("  首选 Provider: " + skill.preferredProviderId());
        }
    }

    /**
     * 输出 skill 命令的用法帮助。
     */
    private void printSkillUsage(ResponseRenderer renderer) {
        renderer.info("");
        renderer.info("用法: skill <子命令>");
        renderer.info("");
        renderer.info("可用子命令:");
        renderer.info("  list              列出所有已注册 Skill");
        renderer.info("  info <id>         查看 Skill 详细信息");
    }
}
