package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell 持久内核 — 委托给 {@link TmuxSessionManager} 实现。
 *
 * <p>当 tmux 不可用（如 Windows 环境）时，{@code tmuxManager} 为 null，
 * 所有操作将返回错误或空结果。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class ShellKernel implements PersistentKernel {

    private static final Logger log = LoggerFactory.getLogger(ShellKernel.class);

    private final String id;
    @Nullable
    private final TmuxSessionManager tmuxManager;
    private final AtomicReference<KernelState> stateRef = new AtomicReference<>(KernelState.STARTING);
    private final int maxOutputChars;

    @Nullable
    private String sessionId;

    /**
     * 创建 Shell 持久内核。
     *
     * @param kernelId       内核唯一标识
     * @param tmuxManager    tmux 会话管理器（可能为 null）
     * @param maxOutputChars 输出最大字符数
     */
    public ShellKernel(String kernelId, @Nullable TmuxSessionManager tmuxManager, int maxOutputChars) {
        this.id = kernelId;
        this.tmuxManager = tmuxManager;
        this.maxOutputChars = maxOutputChars;
        initialize();
    }

    @Override
    public String kernelId() {
        return id;
    }

    @Override
    public KernelState state() {
        return stateRef.get();
    }

    @Override
    public synchronized KernelExecutionResult execute(String code, int timeoutSeconds) {
        if (tmuxManager == null || sessionId == null) {
            return new KernelExecutionResult("", "",
                    "Shell 内核不可用: tmux 未安装或不支持当前平台", 0);
        }

        ensureAlive();
        stateRef.set(KernelState.BUSY);
        long startMs = System.currentTimeMillis();

        try {
            String rawOutput = tmuxManager.execInSession(sessionId, code);
            String output = truncate(rawOutput, maxOutputChars);
            int durationMs = (int) (System.currentTimeMillis() - startMs);
            stateRef.set(KernelState.READY);
            return new KernelExecutionResult(output, "", null, durationMs);
        } catch (IOException e) {
            int durationMs = (int) (System.currentTimeMillis() - startMs);
            stateRef.set(KernelState.ERROR);
            log.error("Shell 内核执行失败: kernelId={}, error={}", id, e.getMessage());
            return new KernelExecutionResult("", "", "Shell 命令执行失败: " + e.getMessage(), durationMs);
        }
    }

    @Override
    public Map<String, String> inspect() {
        var result = new LinkedHashMap<String, String>();

        if (tmuxManager == null || sessionId == null) {
            result.put("error", "Shell 内核不可用");
            return result;
        }

        result.put("sessionId", sessionId);
        result.put("state", stateRef.get().name());

        try {
            var sessions = tmuxManager.listSessions();
            result.put("activeSessions", String.valueOf(sessions.size()));
            sessions.stream()
                    .filter(s -> s.sessionId().equals(sessionId))
                    .findFirst()
                    .ifPresent(s -> result.put("cwd", s.cwd()));
        } catch (Exception e) {
            result.put("error", "检查失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    public synchronized void reset() {
        if (tmuxManager == null) {
            log.warn("Shell 内核重置跳过: tmux 不可用, kernelId={}", id);
            return;
        }

        // 关闭旧会话，创建新会话
        closeSessionQuietly();
        createSession();
        log.info("Shell 内核已重置: kernelId={}, newSessionId={}", id, sessionId);
    }

    @Override
    public void close() {
        stateRef.set(KernelState.CLOSED);
        closeSessionQuietly();
        log.info("Shell 内核已关闭: kernelId={}", id);
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 初始化 Shell 内核，创建 tmux 会话。
     */
    private void initialize() {
        if (tmuxManager == null) {
            stateRef.set(KernelState.ERROR);
            log.warn("Shell 内核初始化失败: tmux 不可用, kernelId={}", id);
            return;
        }

        createSession();
    }

    /**
     * 创建 tmux 会话。
     */
    private void createSession() {
        try {
            sessionId = tmuxManager.createSession("kernel-" + id, null);
            stateRef.set(KernelState.READY);
            log.info("Shell 内核 tmux 会话已创建: kernelId={}, sessionId={}", id, sessionId);
        } catch (Exception e) {
            stateRef.set(KernelState.ERROR);
            log.error("Shell 内核 tmux 会话创建失败: kernelId={}, error={}", id, e.getMessage());
        }
    }

    /**
     * 静默关闭 tmux 会话。
     */
    private void closeSessionQuietly() {
        if (tmuxManager != null && sessionId != null) {
            try {
                tmuxManager.closeSession(sessionId);
            } catch (Exception e) {
                log.debug("关闭 Shell 内核 tmux 会话失败: kernelId={}, sessionId={}, error={}",
                        id, sessionId, e.getMessage());
            }
            sessionId = null;
        }
    }

    /**
     * 检查状态是否有效。
     */
    private void ensureAlive() {
        KernelState currentState = stateRef.get();
        if (currentState == KernelState.CLOSED) {
            throw new IllegalStateException("Shell 内核已关闭: kernelId=" + id);
        }
    }

    /** 截断超长输出。 */
    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...[输出已截断]";
    }
}
