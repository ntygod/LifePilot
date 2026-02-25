package com.lifepilot.interaction.cli;

import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;

/**
 * 命令路由器 — 根据首个参数分发到对应处理器。
 *
 * <p>已知命令包括 {@code chat}（交互式对话）和快捷命令
 * （{@code todo/schedule/habit/llm/mcp/skill}）。
 * 空参数默认进入对话模式，未知命令输出可用命令列表。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    /** 对话命令名。 */
    private static final String CMD_CHAT = "chat";

    /** 快捷命令集合。 */
    private static final Set<String> QUICK_COMMANDS = Set.of(
            "todo", "schedule", "habit", "llm", "mcp", "skill"
    );

    private final ChatCommand chatCommand;
    private final QuickCommand quickCommand;

    /**
     * 创建命令路由器。
     *
     * @param chatCommand 交互式对话命令处理器
     * @param quickCommand 快捷命令分发器
     */
    public CommandRouter(ChatCommand chatCommand, QuickCommand quickCommand) {
        this.chatCommand = chatCommand;
        this.quickCommand = quickCommand;
    }

    /**
     * 路由命令。
     *
     * <p>根据首个参数分发到对应处理器：
     * <ul>
     *   <li>空参数或 {@code chat} → 进入交互式对话模式</li>
     *   <li>{@code todo/schedule/habit/llm/mcp/skill} → 执行快捷命令</li>
     *   <li>其他 → 输出可用命令列表和帮助提示</li>
     * </ul>
     *
     * @param args 命令行参数（已按空格分割）
     * @param lineReader JLine LineReader（对话模式需要）
     * @param renderer 响应渲染器
     */
    public void route(String[] args, LineReader lineReader, ResponseRenderer renderer) {
        // 空参数默认进入对话模式
        if (args == null || args.length == 0) {
            log.debug("无命令参数，默认进入对话模式");
            chatCommand.startSession(lineReader, renderer);
            return;
        }

        String command = args[0].trim().toLowerCase();

        // chat 命令或空字符串 → 对话模式
        if (command.isEmpty() || CMD_CHAT.equals(command)) {
            log.debug("进入对话模式: command={}", command);
            chatCommand.startSession(lineReader, renderer);
            return;
        }

        // 快捷命令 → QuickCommand 处理
        if (QUICK_COMMANDS.contains(command)) {
            String[] subArgs = args.length > 1
                    ? Arrays.copyOfRange(args, 1, args.length)
                    : new String[0];
            log.debug("执行快捷命令: command={}, subArgs={}", command, Arrays.toString(subArgs));
            quickCommand.execute(command, subArgs, renderer);
            return;
        }

        // 未知命令 → 输出帮助
        log.debug("未知命令: {}", command);
        renderer.error("未知命令: " + command);
        printAvailableCommands(renderer);
    }

    /**
     * 输出可用命令列表。
     *
     * @param renderer 响应渲染器
     */
    private void printAvailableCommands(ResponseRenderer renderer) {
        renderer.info("");
        renderer.info("可用命令:");
        renderer.info("  chat              进入交互式对话模式（默认）");
        renderer.info("  todo              管理待办事项");
        renderer.info("  schedule          管理日程安排");
        renderer.info("  habit             管理习惯打卡");
        renderer.info("  llm               管理 LLM 服务商");
        renderer.info("  mcp               管理 MCP Server");
        renderer.info("  skill             管理 Skill 技能");
        renderer.info("");
        renderer.info("使用 --help 查看详细帮助信息");
    }
}
