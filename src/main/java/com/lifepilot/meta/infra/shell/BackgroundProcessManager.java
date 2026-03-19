package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台进程管理器 — 管理通过 shell.exec(background=true) 启动的长时间运行进程。
 *
 * <p>每个后台进程分配唯一 sessionId，输出存入环形缓冲区，空闲超时自动清理。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class BackgroundProcessManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessManager.class);

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final MetaProperties.Infra.Process processConfig;
    private final ScheduledExecutorService cleanupScheduler;

    public BackgroundProcessManager(MetaProperties.Infra.Process processConfig) {
        this.processConfig = processConfig;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofVirtual().unstarted(r);
            t.setName("process-cleanup");
            return t;
        });
        // 每分钟执行一次空闲清理
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleProcesses, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 启动后台进程，返回 sessionId。
     *
     * @param command Shell 命令
     * @param workDir 工作目录
     * @return sessionId
     * @throws IllegalStateException 超过最大并发数时
     * @throws IOException 进程启动失败时
     */
    public String startProcess(String command, Path workDir) throws IOException {
        // 检查并发限制（只计算 RUNNING 状态的进程）
        long runningCount = processes.values().stream()
                .filter(ManagedProcess::isRunning)
                .count();
        if (runningCount >= processConfig.getMaxConcurrent()) {
            throw new IllegalStateException(
                    "后台进程数已达上限: " + processConfig.getMaxConcurrent());
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);

        // 根据操作系统选择 Shell
        ProcessBuilder pb;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true); // stdout + stderr 合并

        Process process = pb.start();
        var outputBuffer = new RingBuffer(processConfig.getMaxOutputBufferSize());
        var now = Instant.now();

        var managed = new ManagedProcess(
                sessionId,
                process,
                outputBuffer,
                new AtomicReference<>(ProcessState.RUNNING),
                now,
                new AtomicReference<>(now),
                command,
                workDir
        );

        processes.put(sessionId, managed);

        // 启动虚拟线程读取进程输出
        Thread.ofVirtual().name("process-reader-" + sessionId).start(() -> readProcessOutput(managed));

        // 启动虚拟线程监控进程退出
        Thread.ofVirtual().name("process-monitor-" + sessionId).start(() -> monitorProcessExit(managed));

        log.info("后台进程已启动: sessionId={}, command={}, workDir={}", sessionId, command, workDir);
        return sessionId;
    }

    /**
     * 列出所有后台进程。
     *
     * @return 进程摘要列表
     */
    public List<ProcessInfo> listProcesses() {
        return processes.values().stream()
                .map(mp -> new ProcessInfo(
                        mp.sessionId(),
                        mp.command(),
                        mp.currentState(),
                        mp.startTime(),
                        mp.workDir().toString()
                ))
                .toList();
    }

    /**
     * 读取指定进程的输出缓冲区增量。
     *
     * @param sessionId 会话标识
     * @return 自上次读取以来的新输出
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public String readOutput(String sessionId) {
        var managed = getProcess(sessionId);
        managed.touch();
        return managed.outputBuffer().readIncremental();
    }

    /**
     * 向指定进程的 stdin 写入内容。
     *
     * @param sessionId 会话标识
     * @param input 要写入的内容
     * @throws IllegalArgumentException sessionId 不存在时
     * @throws IllegalStateException 进程已结束时
     * @throws IOException 写入失败时
     */
    public void writeInput(String sessionId, String input) throws IOException {
        var managed = getProcess(sessionId);
        if (!managed.isRunning()) {
            throw new IllegalStateException("进程已结束，无法写入: sessionId=" + sessionId);
        }
        managed.touch();
        OutputStream os = managed.process().getOutputStream();
        os.write(input.getBytes(StandardCharsets.UTF_8));
        os.flush();
        log.debug("向后台进程写入: sessionId={}, length={}", sessionId, input.length());
    }

    /**
     * 终止指定后台进程。
     *
     * @param sessionId 会话标识
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public void killProcess(String sessionId) {
        var managed = getProcess(sessionId);
        if (managed.isRunning()) {
            managed.process().destroyForcibly();
            managed.state().set(ProcessState.KILLED);
            log.info("后台进程已终止: sessionId={}, command={}", sessionId, managed.command());
        } else {
            log.debug("后台进程已结束，跳过 kill: sessionId={}, state={}", sessionId, managed.currentState());
        }
    }

    /**
     * 清理空闲超时的进程。
     */
    void cleanupIdleProcesses() {
        var now = Instant.now();
        var timeoutMinutes = processConfig.getIdleTimeoutMinutes();

        processes.forEach((id, mp) -> {
            var idleMinutes = java.time.Duration.between(mp.lastAccessTime().get(), now).toMinutes();
            if (idleMinutes >= timeoutMinutes) {
                if (mp.isRunning()) {
                    mp.process().destroyForcibly();
                    mp.state().set(ProcessState.KILLED);
                    log.info("后台进程空闲超时清理: sessionId={}, idleMinutes={}", id, idleMinutes);
                }
                processes.remove(id);
            }
        });
    }

    /**
     * 关闭管理器，终止所有后台进程和清理调度器。
     */
    public void shutdown() {
        cleanupScheduler.shutdownNow();
        processes.forEach((id, mp) -> {
            if (mp.isRunning()) {
                mp.process().destroyForcibly();
                mp.state().set(ProcessState.KILLED);
            }
        });
        processes.clear();
        log.info("后台进程管理器已关闭");
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    private ManagedProcess getProcess(String sessionId) {
        var managed = processes.get(sessionId);
        if (managed == null) {
            throw new IllegalArgumentException("后台进程不存在: sessionId=" + sessionId);
        }
        return managed;
    }

    /** 持续读取进程输出到环形缓冲区。 */
    private void readProcessOutput(ManagedProcess managed) {
        try (var reader = new BufferedReader(
                new InputStreamReader(managed.process().getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                managed.outputBuffer().append(new String(buf, 0, read));
            }
        } catch (IOException e) {
            log.debug("进程输出读取结束: sessionId={}, reason={}", managed.sessionId(), e.getMessage());
        }
    }

    /** 监控进程退出并更新状态。 */
    private void monitorProcessExit(ManagedProcess managed) {
        try {
            int exitCode = managed.process().waitFor();
            if (managed.currentState() == ProcessState.RUNNING) {
                var newState = exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED;
                managed.state().set(newState);
                log.info("后台进程退出: sessionId={}, exitCode={}, state={}",
                        managed.sessionId(), exitCode, newState);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("进程监控被中断: sessionId={}", managed.sessionId());
        }
    }
}
