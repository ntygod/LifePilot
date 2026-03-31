package com.lifepilot.meta.infra.shell.session;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.shell.RingBuffer;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tmux 持久会话管理器。
 *
 * <p>管理 tmux 会话的完整生命周期：创建、命令执行、输入写入、输出读取、
 * 信号发送、大小调整和关闭。自动清理空闲超时的会话。</p>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} + {@link AtomicReference}，
 * 与 {@link com.lifepilot.meta.infra.shell.BackgroundProcessManager} 保持一致。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class TmuxSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TmuxSessionManager.class);
    private static final String SESSION_PREFIX = "zhiwei-";

    private final ConcurrentHashMap<String, TmuxSession> sessions = new ConcurrentHashMap<>();
    private final TmuxCommandExecutor tmuxCmd;
    private final MetaProperties.Infra.ShellSession config;
    private final ScheduledExecutorService cleanupScheduler;

    public TmuxSessionManager(TmuxCommandExecutor tmuxCmd, MetaProperties.Infra.ShellSession config) {
        this.tmuxCmd = tmuxCmd;
        this.config = config;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofVirtual().unstarted(r);
            t.setName("session-cleanup");
            return t;
        });
        // 按配置间隔定期执行空闲清理
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleSessions,
                config.getCleanupIntervalSeconds(), config.getCleanupIntervalSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 创建新的 tmux 持久会话。
     *
     * @param name    会话名称（可选），为 null 时自动生成
     * @param workDir 初始工作目录（可选），为 null 时使用用户主目录
     * @return 会话标识
     * @throws IllegalStateException 超过最大并发数时
     * @throws IOException           tmux 会话创建失败时
     */
    public String createSession(@Nullable String name, @Nullable String workDir) throws IOException {
        // 检查并发限制
        long activeCount = sessions.values().stream()
                .filter(s -> s.currentState() != SessionState.CLOSED)
                .count();
        if (activeCount >= config.getMaxConcurrentSessions()) {
            throw new IllegalStateException("持久会话数已达上限: " + config.getMaxConcurrentSessions());
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String tmuxName = SESSION_PREFIX + sessionId;
        String cwd = workDir != null ? workDir : System.getProperty("user.home");

        // 创建 tmux 会话
        tmuxCmd.newSession(tmuxName, cwd, config.getDefaultCols(), config.getDefaultRows());

        var now = Instant.now();
        var session = new TmuxSession(
                sessionId,
                tmuxName,
                new AtomicReference<>(SessionState.IDLE),
                new AtomicReference<>(cwd),
                now,
                new AtomicReference<>(now),
                new RingBuffer(config.getOutputMaxChars())
        );

        sessions.put(sessionId, session);
        log.info("持久会话已创建: sessionId={}, tmuxName={}, cwd={}", sessionId, tmuxName, cwd);

        return sessionId;
    }

    /**
     * 在持久会话中执行命令并等待完成。
     *
     * <p>通过发送命令 + 结束标记，轮询 capture-pane 直到标记出现，
     * 提取命令输出。超时后返回已捕获的部分输出。</p>
     *
     * @param sessionId 会话标识
     * @param command   要执行的命令
     * @return 命令输出
     * @throws IOException              tmux 命令执行失败时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public String execInSession(String sessionId, String command) throws IOException {
        var session = requireSession(sessionId);
        session.touch();
        session.state().set(SessionState.ACTIVE);

        // 生成唯一结束标记
        String marker = "__ZHIWEI_END_" + UUID.randomUUID().toString().substring(0, 8) + "__";

        try {
            // 发送命令
            tmuxCmd.sendKeys(session.tmuxName(), command);
            // 短暂延迟后发送标记回显
            Thread.sleep(50);
            tmuxCmd.sendKeys(session.tmuxName(), "echo '" + marker + "'");

            // 轮询 capture-pane 直到标记出现
            Instant deadline = Instant.now().plusSeconds(config.getExecTimeoutSeconds());
            String lastCapture = "";
            while (Instant.now().isBefore(deadline)) {
                String capture = tmuxCmd.capturePane(session.tmuxName(), config.getHistoryLines());
                if (capture.contains(marker)) {
                    // 提取命令和标记之间的输出
                    String output = extractOutputBetweenMarkers(capture, command, marker);
                    session.state().set(SessionState.IDLE);
                    session.outputBuffer().append(output);
                    // 更新当前工作目录
                    updateCwd(session);
                    return truncate(output, config.getOutputMaxChars());
                }
                lastCapture = capture;
                Thread.sleep(200);
            }

            // 超时，返回已捕获的部分输出
            session.state().set(SessionState.IDLE);
            session.outputBuffer().append(lastCapture);
            return truncate(lastCapture, config.getOutputMaxChars()) + "\n[命令执行超时]";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.state().set(SessionState.IDLE);
            throw new IOException("命令执行被中断", e);
        }
    }

    /**
     * 向持久会话写入原始输入（不附加 Enter）。
     *
     * @param sessionId 会话标识
     * @param input     要写入的内容
     * @throws IOException              tmux 命令执行失败时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public void writeToSession(String sessionId, String input) throws IOException {
        var session = requireSession(sessionId);
        session.touch();
        tmuxCmd.sendKeysRaw(session.tmuxName(), input);
        log.debug("向持久会话写入: sessionId={}, length={}", sessionId, input.length());
    }

    /**
     * 读取持久会话的当前屏幕输出。
     *
     * @param sessionId 会话标识
     * @return 屏幕捕获内容
     * @throws IOException              tmux 命令执行失败时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public String readSession(String sessionId) throws IOException {
        var session = requireSession(sessionId);
        session.touch();
        String capture = tmuxCmd.capturePane(session.tmuxName(), config.getHistoryLines());
        return truncate(capture, config.getOutputMaxChars());
    }

    /**
     * 向持久会话发送信号。
     *
     * <p>SIGINT 通过 {@code tmux send-keys C-c} 发送，
     * 其他信号通过 {@code kill -<signal> <pane_pid>} 发送。</p>
     *
     * @param sessionId 会话标识
     * @param signal    信号名称（如 "SIGINT"、"SIGTERM"）
     * @throws IOException              tmux 命令执行失败时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public void signalSession(String sessionId, String signal) throws IOException {
        var session = requireSession(sessionId);
        session.touch();

        if ("SIGINT".equalsIgnoreCase(signal)) {
            // SIGINT 通过 send-keys C-c 发送
            tmuxCmd.sendKeysRaw(session.tmuxName(), "C-c");
            log.info("向持久会话发送 SIGINT: sessionId={}", sessionId);
        } else {
            // 其他信号通过 kill 命令发送
            String panePid = tmuxCmd.getPanePid(session.tmuxName());
            String signalName = signal.toUpperCase().startsWith("SIG") ? signal.substring(3) : signal;
            try {
                var pb = new ProcessBuilder("kill", "-" + signalName, panePid);
                var process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("发送信号超时: signal=" + signal + ", pid=" + panePid);
                }
                if (process.exitValue() != 0) {
                    throw new IOException("发送信号失败: signal=" + signal + ", pid=" + panePid);
                }
                log.info("向持久会话发送信号: sessionId={}, signal={}, pid={}", sessionId, signal, panePid);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("发送信号被中断", e);
            }
        }
    }

    /**
     * 列出所有活跃的持久会话。
     *
     * @return 会话摘要列表
     */
    public List<SessionInfo> listSessions() {
        return sessions.values().stream()
                .filter(s -> s.currentState() != SessionState.CLOSED)
                .map(this::toSessionInfo)
                .toList();
    }

    /**
     * 关闭持久会话。
     *
     * @param sessionId 会话标识
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public void closeSession(String sessionId) {
        var session = requireSession(sessionId);
        session.state().set(SessionState.CLOSED);

        try {
            tmuxCmd.killSession(session.tmuxName());
            log.info("持久会话已关闭: sessionId={}, tmuxName={}", sessionId, session.tmuxName());
        } catch (IOException e) {
            log.warn("关闭 tmux 会话失败（可能已不存在）: sessionId={}, error={}", sessionId, e.getMessage());
        }

        sessions.remove(sessionId);
    }

    /**
     * 调整持久会话窗口大小。
     *
     * @param sessionId 会话标识
     * @param cols      列数
     * @param rows      行数
     * @throws IOException              tmux 命令执行失败时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public void resizeSession(String sessionId, int cols, int rows) throws IOException {
        var session = requireSession(sessionId);
        session.touch();
        tmuxCmd.resizeWindow(session.tmuxName(), cols, rows);
        log.debug("持久会话窗口已调整: sessionId={}, cols={}, rows={}", sessionId, cols, rows);
    }

    /**
     * 清理空闲超时的会话。
     */
    void cleanupIdleSessions() {
        var now = Instant.now();
        var ttlMinutes = config.getTtlMinutes();

        sessions.forEach((id, session) -> {
            var idleMinutes = Duration.between(session.lastAccessTime().get(), now).toMinutes();
            if (idleMinutes >= ttlMinutes) {
                session.state().set(SessionState.CLOSED);
                try {
                    tmuxCmd.killSession(session.tmuxName());
                    log.info("持久会话空闲超时清理: sessionId={}, idleMinutes={}", id, idleMinutes);
                } catch (IOException e) {
                    log.debug("清理 tmux 会话失败: sessionId={}, error={}", id, e.getMessage());
                }
                sessions.remove(id);
            }
        });
    }

    /**
     * 关闭管理器，终止所有会话和清理调度器。
     */
    public void shutdown() {
        cleanupScheduler.shutdownNow();
        sessions.forEach((id, session) -> {
            session.state().set(SessionState.CLOSED);
            try {
                tmuxCmd.killSession(session.tmuxName());
            } catch (IOException e) {
                log.debug("关闭 tmux 会话失败: sessionId={}, error={}", id, e.getMessage());
            }
        });
        sessions.clear();
        log.info("tmux 持久会话管理器已关闭");
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 获取指定会话，不存在时抛出异常。
     */
    private TmuxSession requireSession(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("持久会话不存在: sessionId=" + sessionId);
        }
        if (session.currentState() == SessionState.CLOSED) {
            throw new IllegalArgumentException("持久会话已关闭: sessionId=" + sessionId);
        }
        return session;
    }

    /**
     * 从 capture-pane 输出中提取命令和标记之间的内容。
     */
    private String extractOutputBetweenMarkers(String capture, String command, String marker) {
        var lines = capture.split("\n");
        var sb = new StringBuilder();
        boolean capturing = false;

        for (String line : lines) {
            if (!capturing) {
                // 找到命令回显行后开始捕获
                if (line.contains(command)) {
                    capturing = true;
                    continue;
                }
            } else {
                // 遇到标记回显行时停止
                if (line.contains(marker)) {
                    break;
                }
                // 跳过 echo marker 命令本身的回显
                if (line.contains("echo '" + marker + "'")) {
                    continue;
                }
                sb.append(line).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 执行 pwd 命令更新会话的当前工作目录。
     */
    private void updateCwd(TmuxSession session) {
        try {
            String cwdMarker = "__ZHIWEI_CWD_" + UUID.randomUUID().toString().substring(0, 8) + "__";
            tmuxCmd.sendKeys(session.tmuxName(), "echo '" + cwdMarker + "'$(pwd)'" + cwdMarker + "'");
            Thread.sleep(100);
            String capture = tmuxCmd.capturePane(session.tmuxName(), 10);
            // 解析 CWD
            int startIdx = capture.indexOf(cwdMarker);
            if (startIdx >= 0) {
                startIdx += cwdMarker.length();
                int endIdx = capture.indexOf(cwdMarker, startIdx);
                if (endIdx > startIdx) {
                    String cwd = capture.substring(startIdx, endIdx).trim();
                    if (!cwd.isEmpty()) {
                        session.cwd().set(cwd);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            log.debug("更新会话 CWD 失败: sessionId={}, error={}", session.sessionId(), e.getMessage());
        }
    }

    /**
     * 截断超长输出。
     */
    private String truncate(String output, int maxChars) {
        if (output == null) {
            return "";
        }
        if (output.length() <= maxChars) {
            return output;
        }
        return output.substring(0, maxChars) + "...[输出已截断，原始长度: " + output.length() + " 字符]";
    }

    /**
     * 将内部 TmuxSession 转换为外部 SessionInfo。
     */
    private SessionInfo toSessionInfo(TmuxSession session) {
        return new SessionInfo(
                session.sessionId(),
                session.tmuxName(),
                session.currentState(),
                session.cwd().get(),
                session.createdAt(),
                session.lastAccessTime().get()
        );
    }
}
