package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git 命令执行器 — 基于 ProcessBuilder 的 Git CLI 薄封装。
 *
 * <p>提供 Git 命令执行、仓库校验和输出截断等基础能力，
 * 所有 Git 工具执行器共享此类实例。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitCommandExecutor.class);

    private final int timeoutSeconds;
    private final int maxOutputChars;

    public GitCommandExecutor(MetaProperties.Infra.Git gitConfig) {
        this.timeoutSeconds = gitConfig.getTimeoutSeconds();
        this.maxOutputChars = gitConfig.getMaxOutputChars();
    }

    /**
     * 检查系统中是否安装了 Git。
     *
     * @return true 表示 git 命令可用
     */
    public boolean isGitAvailable() {
        try {
            var process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("检测 git 可用性失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定路径是否为 Git 仓库。
     *
     * @param repoPath 仓库路径
     * @return true 表示路径是有效的 Git 仓库
     */
    public boolean isGitRepo(Path repoPath) {
        if (repoPath == null || !Files.isDirectory(repoPath)) {
            return false;
        }
        try {
            var result = execute(repoPath, "rev-parse", "--is-inside-work-tree");
            return result.ok() && "true".equals(result.stdout().trim());
        } catch (Exception e) {
            log.debug("检测 Git 仓库失败: path={}, error={}", repoPath, e.getMessage());
            return false;
        }
    }

    /**
     * 在指定仓库路径下执行 Git 命令。
     *
     * @param repoPath Git 仓库路径
     * @param args     git 子命令及参数
     * @return 命令执行结果
     */
    public GitCommandResult execute(Path repoPath, String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));

        log.debug("执行 Git 命令: dir={}, cmd={}", repoPath, command);

        try {
            var pb = new ProcessBuilder(command)
                    .directory(repoPath.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("LC_ALL", "C.UTF-8");

            var process = pb.start();

            // 异步读取 stdout 和 stderr 防止缓冲区阻塞
            String stdout;
            String stderr;
            try (var stdoutStream = process.getInputStream();
                 var stderrStream = process.getErrorStream()) {

                var stdoutBytes = stdoutStream.readAllBytes();
                var stderrBytes = stderrStream.readAllBytes();

                stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
                stderr = new String(stderrBytes, StandardCharsets.UTF_8);
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git 命令超时: cmd={}, timeout={}s", command, timeoutSeconds);
                return new GitCommandResult(-1, "", "命令执行超时（" + timeoutSeconds + "秒）");
            }

            // 输出截断
            stdout = truncateOutput(stdout);
            stderr = truncateOutput(stderr);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.debug("Git 命令非零退出: cmd={}, exitCode={}, stderr={}", command, exitCode, stderr);
            }

            return new GitCommandResult(exitCode, stdout, stderr);

        } catch (IOException e) {
            log.error("Git 命令执行 IO 异常: cmd={}, error={}", command, e.getMessage(), e);
            return new GitCommandResult(-1, "", "命令执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Git 命令执行被中断: cmd={}", command);
            return new GitCommandResult(-1, "", "命令执行被中断");
        }
    }

    /**
     * 截断超长输出。
     */
    private String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() > maxOutputChars) {
            return output.substring(0, maxOutputChars) + "\n...[输出已截断，共 " + output.length() + " 字符]";
        }
        return output;
    }
}
