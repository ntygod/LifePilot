package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具 — 通过 ProcessBuilder 启动子进程执行命令。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel HIGH — GuardrailEngine 触发用户确认</li>
 *   <li>命令黑名单 — 正则模式匹配，匹配时直接拒绝</li>
 *   <li>超时强制 — {@code Process.waitFor(timeout)} + {@code destroyForcibly()}</li>
 *   <li>输出截断 — stdout/stderr 超过 maxOutputLength 时截断</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ShellExecToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellExecToolExecutor.class);

    private final MetaProperties.Infra.Shell shellConfig;
    private final List<Pattern> compiledBlacklist;

    public ShellExecToolExecutor(MetaProperties properties) {
        this.shellConfig = properties.getInfra().getShell();
        // 构造时编译正则模式，避免每次执行重复编译
        this.compiledBlacklist = shellConfig.getCommandBlacklist().stream()
                .map(Pattern::compile)
                .toList();
    }

    /**
     * 执行 Shell 命令。
     *
     * @param input 工具输入，必需参数 command，可选 workingDirectory 和 timeoutSeconds
     * @return 包含 stdout、stderr、exitCode 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        // 提取参数
        String command;
        try {
            command = input.getParam("command", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: command");
        }

        String workingDirectory = input.getOptionalParam("workingDirectory", String.class)
                .orElse(System.getProperty("user.home"));

        int timeoutSeconds = input.getOptionalParam("timeoutSeconds", Number.class)
                .map(Number::intValue)
                .orElse(shellConfig.getTimeoutSeconds());

        // 黑名单检查
        var rejection = checkBlacklist(command);
        if (rejection != null) {
            return rejection;
        }

        // 验证工作目录
        Path workDir = Path.of(workingDirectory);
        if (!Files.isDirectory(workDir)) {
            return ToolResult.error("工作目录不存在: " + workingDirectory);
        }

        // 执行命令
        try {
            return executeCommand(command, workDir, timeoutSeconds);
        } catch (IOException e) {
            log.error("Shell 命令执行失败: command={}, error={}", command, e.getMessage(), e);
            return ToolResult.error("命令执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("命令执行被中断");
        }
    }

    /**
     * 检查命令是否匹配黑名单。
     *
     * @param command 待检查的命令
     * @return 匹配时返回拒绝结果，不匹配返回 null
     */
    ToolResult checkBlacklist(String command) {
        for (Pattern pattern : compiledBlacklist) {
            if (pattern.matcher(command).find()) {
                log.warn("Shell 命令被黑名单拒绝: command={}, pattern={}", command, pattern.pattern());
                return ToolResult.error("命令被安全策略拒绝: 匹配黑名单规则 [" + pattern.pattern() + "]");
            }
        }
        return null;
    }

    /**
     * 截断超长输出。
     *
     * @param output 原始输出
     * @param maxLength 最大长度
     * @return 截断后的输出（如有截断则附加提示）
     */
    String truncateOutput(String output, int maxLength) {
        if (output.length() <= maxLength) {
            return output;
        }
        int originalLength = output.length();
        return output.substring(0, maxLength) + "...[输出已截断，原始长度: " + originalLength + " 字符]";
    }

    private ToolResult executeCommand(String command, Path workDir, int timeoutSeconds)
            throws IOException, InterruptedException {

        // 根据操作系统选择 Shell
        ProcessBuilder pb;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        log.debug("执行 Shell 命令: command={}, workDir={}, timeout={}s", command, workDir, timeoutSeconds);

        Process process = pb.start();

        // 在独立线程中读取 stdout/stderr，避免 readAllBytes() 阻塞导致 waitFor 无法超时
        var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes()); }
            catch (IOException e) { return ""; }
        });
        var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getErrorStream().readAllBytes()); }
            catch (IOException e) { return ""; }
        });

        // 等待进程完成，超时则强制终止
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS); // 等待强制终止完成
            log.warn("Shell 命令超时被终止: command={}, timeout={}s", command, timeoutSeconds);
            return ToolResult.error("命令执行超时（" + timeoutSeconds + " 秒），已强制终止");
        }

        // 进程已完成，获取输出
        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        int exitCode = process.exitValue();

        // 截断输出
        int maxOutputLength = shellConfig.getMaxOutputLength();
        stdout = truncateOutput(stdout, maxOutputLength);
        stderr = truncateOutput(stderr, maxOutputLength);

        var data = new LinkedHashMap<String, Object>();
        data.put("exitCode", exitCode);
        data.put("stdout", stdout);
        data.put("stderr", stderr);

        log.debug("Shell 命令执行完成: command={}, exitCode={}, stdoutLen={}, stderrLen={}",
                command, exitCode, stdout.length(), stderr.length());

        // exitCode != 0 时返回错误结果，让 LLM 感知命令执行失败
        if (exitCode != 0) {
            return ToolResult.error("命令执行失败 (exitCode=" + exitCode + "): "
                    + (!stderr.isBlank() ? stderr : stdout));
        }

        return ToolResult.success(Map.copyOf(data));
    }
}
