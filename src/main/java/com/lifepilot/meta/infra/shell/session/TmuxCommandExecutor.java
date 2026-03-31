package com.lifepilot.meta.infra.shell.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * tmux CLI 命令薄封装。所有 tmux 交互的唯一出口。
 *
 * <p>所有方法通过 {@link ProcessBuilder} + 虚拟线程执行 tmux 命令，
 * 统一超时控制和错误处理。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class TmuxCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(TmuxCommandExecutor.class);

    private final int timeoutSeconds;
    private final Boolean tmuxAvailable;

    public TmuxCommandExecutor(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        // 构造时检测 tmux 可用性，缓存结果
        this.tmuxAvailable = detectTmuxAvailable();
    }

    /**
     * 检查系统中是否安装了 tmux（结果已缓存）。
     *
     * @return true 表示 tmux 命令可用
     */
    public boolean isTmuxAvailable() {
        return tmuxAvailable;
    }

    /**
     * 创建新的 tmux 会话。
     *
     * @param name tmux 会话名称
     * @param cwd  初始工作目录
     * @param cols 终端列数
     * @param rows 终端行数
     * @return tmux 命令输出
     * @throws IOException 创建失败时
     */
    public String newSession(String name, String cwd, int cols, int rows) throws IOException {
        return runTmuxCommand("new-session", "-d", "-s", name, "-x", String.valueOf(cols),
                "-y", String.valueOf(rows), "-c", cwd);
    }

    /**
     * 向 tmux 会话发送按键序列。
     *
     * @param sessionName tmux 会话名称
     * @param keys        要发送的按键或命令文本
     * @return tmux 命令输出
     * @throws IOException 发送失败时
     */
    public String sendKeys(String sessionName, String keys) throws IOException {
        return runTmuxCommand("send-keys", "-t", sessionName, keys, "Enter");
    }

    /**
     * 向 tmux 会话发送原始按键（不附加 Enter）。
     *
     * @param sessionName tmux 会话名称
     * @param keys        要发送的原始按键
     * @return tmux 命令输出
     * @throws IOException 发送失败时
     */
    public String sendKeysRaw(String sessionName, String keys) throws IOException {
        return runTmuxCommand("send-keys", "-t", sessionName, keys);
    }

    /**
     * 捕获 tmux 窗格的输出内容。
     *
     * @param sessionName  tmux 会话名称
     * @param historyLines 向上回溯的历史行数
     * @return 窗格内容
     * @throws IOException 捕获失败时
     */
    public String capturePane(String sessionName, int historyLines) throws IOException {
        return runTmuxCommand("capture-pane", "-t", sessionName, "-p",
                "-S", "-" + historyLines);
    }

    /**
     * 终止 tmux 会话。
     *
     * @param sessionName tmux 会话名称
     * @throws IOException 终止失败时
     */
    public void killSession(String sessionName) throws IOException {
        runTmuxCommand("kill-session", "-t", sessionName);
    }

    /**
     * 获取 tmux 窗格的 PID。
     *
     * @param sessionName tmux 会话名称
     * @return 窗格 PID
     * @throws IOException 获取失败时
     */
    public String getPanePid(String sessionName) throws IOException {
        return runTmuxCommand("list-panes", "-t", sessionName, "-F", "#{pane_pid}").trim();
    }

    /**
     * 调整 tmux 窗口大小。
     *
     * @param sessionName tmux 会话名称
     * @param cols        列数
     * @param rows        行数
     * @throws IOException 调整失败时
     */
    public void resizeWindow(String sessionName, int cols, int rows) throws IOException {
        runTmuxCommand("resize-window", "-t", sessionName, "-x", String.valueOf(cols),
                "-y", String.valueOf(rows));
    }

    /**
     * 列出匹配指定前缀的 tmux 会话名称。
     *
     * @param prefix 会话名称前缀
     * @return 匹配的会话名称列表
     */
    public List<String> listSessions(String prefix) {
        try {
            String output = runTmuxCommand("list-sessions", "-F", "#{session_name}");
            return output.lines()
                    .filter(line -> line.startsWith(prefix))
                    .toList();
        } catch (IOException e) {
            log.debug("列出 tmux 会话失败: prefix={}, error={}", prefix, e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查 tmux 会话是否存在。
     *
     * @param sessionName tmux 会话名称
     * @return true 表示会话存在
     */
    public boolean hasSession(String sessionName) {
        try {
            var pb = new ProcessBuilder("tmux", "has-session", "-t", sessionName);
            pb.redirectErrorStream(true);
            var process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("检测 tmux 会话存在性失败: name={}, error={}", sessionName, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 检测 tmux 是否可用。
     */
    private boolean detectTmuxAvailable() {
        // Windows 系统不支持 tmux
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            log.debug("Windows 系统不支持 tmux");
            return false;
        }
        try {
            var process = new ProcessBuilder("tmux", "-V")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() == 0) {
                String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                log.info("检测到 tmux: {}", version);
                return true;
            }
            return false;
        } catch (IOException | InterruptedException e) {
            log.debug("检测 tmux 可用性失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行 tmux 命令并返回 stdout 输出。
     *
     * @param args tmux 子命令及参数
     * @return 命令标准输出
     * @throws IOException 命令执行失败时
     */
    String runTmuxCommand(String... args) throws IOException {
        var command = new ArrayList<String>();
        command.add("tmux");
        command.addAll(List.of(args));

        log.debug("执行 tmux 命令: {}", command);

        try {
            var pb = new ProcessBuilder(command);
            pb.environment().put("LC_ALL", "C.UTF-8");
            var process = pb.start();

            // 读取 stdout 和 stderr
            String stdout;
            String stderr;
            try (var stdoutStream = process.getInputStream();
                 var stderrStream = process.getErrorStream()) {
                stdout = new String(stdoutStream.readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(stderrStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("tmux 命令超时（" + timeoutSeconds + "秒）: " + command);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.debug("tmux 命令非零退出: cmd={}, exitCode={}, stderr={}", command, exitCode, stderr);
                throw new IOException("tmux 命令失败 (exitCode=" + exitCode + "): " + stderr.trim());
            }

            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tmux 命令被中断: " + command, e);
        }
    }
}
