package com.lifepilot.interaction.cli;

import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CLI 用户确认服务 — 在终端中请求用户确认高风险工具执行。
 *
 * <p>实现 {@link UserConfirmationService} 接口，替换 NoOpUserConfirmationService。
 * 显示工具名称、风险级别和确认消息，等待用户输入 y/n。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CliUserConfirmationService implements UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(CliUserConfirmationService.class);

    private final Terminal terminal;
    private final CliConfigProperties config;

    public CliUserConfirmationService(Terminal terminal, CliConfigProperties config) {
        this.terminal = terminal;
        this.config = config;
    }

    @Override
    public boolean requestConfirmation(ToolContract tool, ToolInput input, String message) {
        PrintWriter writer = terminal.writer();

        // 显示工具名称、风险级别、确认消息
        writer.println();
        writer.println("⚠️  工具执行确认");
        writer.println("  工具名称: " + tool.name());
        writer.println("  风险级别: " + tool.riskLevel());
        writer.println("  确认消息: " + message);
        writer.print("是否允许执行？(y/n): ");
        writer.flush();

        try {
            // 使用 CompletableFuture 实现超时读取
            int timeoutSeconds = config.getConfirmationTimeoutSeconds();
            CompletableFuture<String> inputFuture = CompletableFuture.supplyAsync(this::readLine);
            String response = inputFuture.get(timeoutSeconds, TimeUnit.SECONDS);

            return parseConfirmation(response);
        } catch (TimeoutException e) {
            log.warn("用户确认超时，自动拒绝: toolId={}, timeout={}s",
                    tool.id(), config.getConfirmationTimeoutSeconds());
            writer.println();
            writer.println("⏰ 确认超时，已自动拒绝执行");
            writer.flush();
            return false;
        } catch (Exception e) {
            // Ctrl+C、InterruptedException 等异常均返回 false
            log.debug("用户确认被中断: toolId={}, error={}", tool.id(), e.getMessage());
            return false;
        }
    }

    /**
     * 从终端读取一行用户输入。
     */
    private String readLine() {
        try {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int ch = terminal.reader().read();
                if (ch == -1 || ch == '\n' || ch == '\r') {
                    break;
                }
                // Ctrl+C (ASCII 3)
                if (ch == 3) {
                    throw new RuntimeException("用户按下 Ctrl+C");
                }
                sb.append((char) ch);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("读取用户输入失败", e);
        }
    }

    /**
     * 解析用户确认输入。
     *
     * @param input 用户输入字符串
     * @return y/yes 返回 true，其他返回 false
     */
    private boolean parseConfirmation(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim().toLowerCase();
        return "y".equals(trimmed) || "yes".equals(trimmed);
    }
}
