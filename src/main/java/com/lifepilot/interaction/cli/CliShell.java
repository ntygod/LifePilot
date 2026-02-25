package com.lifepilot.interaction.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;

import java.util.Arrays;

/**
 * CLI 交互式 Shell — 基于 JLine 3 的终端主循环。
 *
 * <p>实现 {@link CommandLineRunner}，Spring 容器启动后自动进入交互循环。
 * 支持 Tab 补全、命令历史持久化、语法高亮。</p>
 *
 * <p>根据启动参数决定运行模式：
 * <ul>
 *   <li>有命令参数 → 执行单次命令后退出</li>
 *   <li>无命令参数 → 进入交互式命令循环</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CliShell implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliShell.class);

    /** 续行提示符。 */
    private static final String CONTINUATION_PROMPT = "... ";

    /** 欢迎横幅。 */
    private static final String WELCOME_BANNER = """
            
            ╔══════════════════════════════════════╗
            ║         LifePilot CLI v0.1.0         ║
            ║   输入命令开始，/exit 或 Ctrl+D 退出   ║
            ╚══════════════════════════════════════╝
            """;

    private final CommandRouter commandRouter;
    private final CliConfigProperties config;

    /**
     * 创建 CLI Shell。
     *
     * @param commandRouter 命令路由器
     * @param config        CLI 配置属性
     */
    public CliShell(CommandRouter commandRouter, CliConfigProperties config) {
        this.commandRouter = commandRouter;
        this.config = config;
    }

    @Override
    public void run(String... args) throws Exception {
        // 1. 初始化 JLine Terminal
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        try {
            // 2. 创建响应渲染器
            var renderer = new ResponseRenderer(terminal);

            // 3. 创建命令历史
            var historyConfig = CliHistoryFactory.createHistory(config);

            // 4. 构建 LineReader（含补全、高亮、历史）
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CliCompleter())
                    .highlighter(new CliHighlighter())
                    .history(historyConfig.history())
                    .build();

            // 5. 配置历史持久化
            if (historyConfig.persistent()) {
                lineReader.setVariable(LineReader.HISTORY_FILE, historyConfig.historyFile());
                lineReader.setVariable(LineReader.HISTORY_SIZE, historyConfig.maxSize());
            }

            // 6. 过滤 Spring Boot 内部参数，提取用户命令参数
            String[] userArgs = filterSpringArgs(args);

            // 7. 有用户命令参数 → 执行单次命令后退出
            if (userArgs.length > 0) {
                log.info("单次命令模式: args={}", Arrays.toString(userArgs));
                commandRouter.route(userArgs, lineReader, renderer);
                return;
            }

            // 8. 无参数 → 进入交互式命令循环
            log.info("进入交互式命令循环");
            enterInteractiveLoop(lineReader, renderer);

        } finally {
            terminal.close();
        }
    }

    /**
     * 进入交互式命令循环。
     *
     * <p>循环读取用户输入，通过 {@link CommandRouter} 分发命令。
     * Ctrl+C 提示退出方式，Ctrl+D 优雅退出。</p>
     *
     * @param lineReader JLine LineReader
     * @param renderer   响应渲染器
     */
    private void enterInteractiveLoop(LineReader lineReader, ResponseRenderer renderer) {
        // 打印欢迎横幅
        renderer.info(WELCOME_BANNER);

        while (true) {
            try {
                // 读取一行输入
                String line = lineReader.readLine(config.getDefaultPrompt());

                // 处理多行输入
                String fullInput = MultiLineParser.readMultiLine(line, lineReader, CONTINUATION_PROMPT);

                // 去除首尾空白
                String trimmed = fullInput.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // 按空白字符分割为参数数组
                String[] splitArgs = trimmed.split("\\s+");

                // 路由到对应命令处理器
                commandRouter.route(splitArgs, lineReader, renderer);

            } catch (UserInterruptException e) {
                // Ctrl+C → 提示退出方式
                renderer.info("输入 /exit 退出");
            } catch (EndOfFileException e) {
                // Ctrl+D → 优雅退出
                renderer.info("再见！");
                break;
            }
        }
    }

    /**
     * 过滤 Spring Boot 内部参数（以 {@code --spring} 开头），提取用户命令参数。
     *
     * @param args 原始命令行参数
     * @return 过滤后的用户命令参数
     */
    private static String[] filterSpringArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        return Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--spring"))
                .toArray(String[]::new);
    }
}
