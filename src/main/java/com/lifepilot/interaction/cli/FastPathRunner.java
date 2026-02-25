package com.lifepilot.interaction.cli;

import java.io.PrintStream;
import java.util.Set;

/**
 * CLI 快速路径 — 简单命令跳过 Spring 初始化。
 *
 * <p>在 {@code LifePilotApplication.main()} 中，于
 * {@code SpringApplication.run()} 之前调用，检测
 * {@code --help}/{@code --version} 等参数并直接输出。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class FastPathRunner {

    private static final Set<String> HELP_FLAGS = Set.of("--help", "-h");
    private static final Set<String> VERSION_FLAGS = Set.of("--version", "-v");
    private static final String FALLBACK_VERSION = "dev";

    private FastPathRunner() {
        // 工具类禁止实例化
    }

    /**
     * 尝试快速路径执行。
     *
     * @param args 命令行参数
     * @return true 表示已处理（调用方应退出），false 表示需要完整启动
     */
    public static boolean tryFastPath(String[] args) {
        return tryFastPath(args, System.out);
    }

    /**
     * 尝试快速路径执行（可注入输出流，便于测试）。
     *
     * @param args 命令行参数
     * @param out 输出流
     * @return true 表示已处理，false 表示需要完整启动
     */
    static boolean tryFastPath(String[] args, PrintStream out) {
        if (args == null || args.length == 0) {
            return false;
        }

        String first = args[0].trim();

        if (HELP_FLAGS.contains(first)) {
            printHelp(out);
            return true;
        }

        if (VERSION_FLAGS.contains(first)) {
            printVersion(out);
            return true;
        }

        return false;
    }

    /** 输出帮助信息。 */
    private static void printHelp(PrintStream out) {
        out.println("LifePilot — 了解你生活全貌的 AI 伙伴");
        out.println();
        out.println("用法: lifepilot [命令] [选项]");
        out.println();
        out.println("命令:");
        out.println("  chat              进入交互式对话模式（默认）");
        out.println("  todo              管理待办事项");
        out.println("  schedule          管理日程安排");
        out.println("  habit             管理习惯打卡");
        out.println("  llm               管理 LLM 服务商");
        out.println("  mcp               管理 MCP Server");
        out.println("  skill             管理 Skill 技能");
        out.println();
        out.println("选项:");
        out.println("  -h, --help        显示帮助信息");
        out.println("  -v, --version     显示版本号");
    }

    /** 输出版本号。 */
    private static void printVersion(PrintStream out) {
        String version = FastPathRunner.class.getPackage().getImplementationVersion();
        out.println("LifePilot " + (version != null ? version : FALLBACK_VERSION));
    }
}
