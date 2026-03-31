package com.lifepilot.meta.infra.file.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lint 钩子执行器 — 在文件编辑后运行对应语言的语法检查命令。
 *
 * <p>根据文件扩展名在 {@code lintCommands} 配置中匹配对应的 lint 命令，
 * 通过 {@link ProcessBuilder} 执行，替换 {@code {file}} 占位符为实际文件路径。</p>
 *
 * <p>若 lint 命令退出码不为 0，返回 stdout + stderr 内容；否则返回空字符串表示通过。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class LintHookExecutor {

    private static final Logger log = LoggerFactory.getLogger(LintHookExecutor.class);

    /**
     * 对文件执行 lint 检查。
     *
     * @param filePath        文件路径
     * @param lintCommands    扩展名 → lint 命令映射（如 {@code ".py" → "python3 -m py_compile {file}"}）
     * @param timeoutSeconds  命令执行超时（秒）
     * @return lint 输出（非空表示有问题），通过时返回空字符串
     */
    public String runLint(Path filePath, Map<String, String> lintCommands, int timeoutSeconds) {
        String fileName = filePath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            log.debug("文件无扩展名，跳过 lint: {}", filePath);
            return "";
        }

        String extension = fileName.substring(dotIndex); // 包含点号，如 ".py"
        String commandTemplate = lintCommands.get(extension);
        if (commandTemplate == null) {
            log.debug("未找到扩展名 {} 对应的 lint 命令，跳过: {}", extension, filePath);
            return "";
        }

        String command = commandTemplate.replace("{file}", filePath.toAbsolutePath().normalize().toString());

        try {
            ProcessBuilder pb = buildProcess(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("lint 命令超时({}秒): command={}", timeoutSeconds, command);
                return "lint 命令超时（" + timeoutSeconds + "秒）: " + command;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.debug("lint 检查发现问题: path={}, exitCode={}, output={}", filePath, exitCode, output);
                return output.isEmpty() ? "lint 检查失败，退出码: " + exitCode : output;
            }

            log.debug("lint 检查通过: path={}", filePath);
            return "";

        } catch (IOException e) {
            log.warn("lint 命令执行失败: command={}, error={}", command, e.getMessage());
            return "lint 命令执行失败: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("lint 命令被中断: command={}", command);
            return "lint 命令被中断";
        }
    }

    /**
     * 构建进程 — 按操作系统选择 shell。
     *
     * <p>包级可见，便于测试时 mock。</p>
     */
    ProcessBuilder buildProcess(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("sh", "-c", command);
        }
    }
}
