package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.config.MetaProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台进程管理器 — 管理通过 shell.exec(background=true) 启动的长时间运行进程。
 *
 * <p>每个后台进程分配唯一 sessionId，输出存入环形缓冲区，空闲超时自动清理。
 * 进程创建委托 {@link ShellProcessFactory}，与同步执行共享一致的 Shell 启动逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class BackgroundProcessManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundProcessManager.class);

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final MetaProperties.Infra.Process processConfig;
    private final ScheduledExecutorService cleanupScheduler;
    @Nullable
    private final ApplicationEventPublisher eventPublisher;

    public BackgroundProcessManager(MetaProperties.Infra.Process processConfig,
                                     @Nullable ApplicationEventPublisher eventPublisher) {
        this.processConfig = processConfig;
        this.eventPublisher = eventPublisher;
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
     * @param env     额外环境变量，null 时不注入
     * @return sessionId
     * @throws IllegalStateException 超过最大并发数时
     * @throws IOException           进程启动失败时
     */
    public String startProcess(String command, Path workDir, @Nullable Map<String, String> env)
            throws IOException {
        processes.values().forEach(this::refreshProcessState);
        // 检查并发限制（只计算 RUNNING 状态的进程）
        long runningCount = processes.values().stream()
                .filter(ManagedProcess::isRunning)
                .count();
        if (runningCount >= processConfig.getMaxConcurrent()) {
            throw new IllegalStateException(
                    "后台进程数已达上限: " + processConfig.getMaxConcurrent());
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);

        // 通过工厂创建进程（消除重复的 PowerShell/Unix 构建逻辑）
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(command, workDir, null, false, env);

        Process process = pb.start();
        var stdoutBuffer = new RingBuffer(processConfig.getMaxOutputBufferSize());
        var stderrBuffer = new RingBuffer(processConfig.getMaxOutputBufferSize());
        var now = Instant.now();

        var managed = new ManagedProcess(
                sessionId,
                process,
                stdoutBuffer,
                stderrBuffer,
                new AtomicReference<>(ProcessState.RUNNING),
                new AtomicReference<>(null),
                now,
                new AtomicReference<>(now),
                command,
                workDir
        );

        processes.put(sessionId, managed);

        // 启动虚拟线程分别读取 stdout/stderr，避免丢失通道语义
        Thread.ofVirtual().name("process-stdout-reader-" + sessionId)
                .start(() -> readProcessOutput(managed, managed.process().getInputStream(),
                        managed.stdoutBuffer(), "stdout"));
        Thread.ofVirtual().name("process-stderr-reader-" + sessionId)
                .start(() -> readProcessOutput(managed, managed.process().getErrorStream(),
                        managed.stderrBuffer(), "stderr"));

        // 启动虚拟线程监控进程退出
        Thread.ofVirtual().name("process-monitor-" + sessionId).start(() -> monitorProcessExit(managed));

        log.info("后台进程已启动: sessionId={}, command={}, workDir={}", sessionId, command, workDir);
        return sessionId;
    }

    /**
     * 启动后台进程（无额外环境变量的简洁版）。
     *
     * @param command Shell 命令
     * @param workDir 工作目录
     * @return sessionId
     * @throws IOException 进程启动失败时
     */
    public String startProcess(String command, Path workDir) throws IOException {
        return startProcess(command, workDir, null);
    }

    /**
     * 列出所有后台进程。
     *
     * @return 进程摘要列表
     */
    public List<ProcessInfo> listProcesses() {
        return processes.values().stream()
                .map(this::snapshotProcessInfo)
                .toList();
    }

    /**
     * 获取指定后台进程的摘要信息。
     *
     * @param sessionId 会话标识
     * @return 进程摘要
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public ProcessInfo getProcessInfo(String sessionId) {
        var managed = getProcess(sessionId);
        managed.touch();
        return snapshotProcessInfo(managed);
    }

    /**
     * 等待指定进程完成或超时。
     *
     * <p>比 {@code Thread.sleep(yieldMs)} 更高效——进程提前退出时立即返回，
     * 不会浪费剩余等待时间。</p>
     *
     * @param sessionId 会话标识
     * @param timeout   超时时间
     * @param unit      时间单位
     * @return true 如果进程在超时前已退出
     * @throws InterruptedException     等待被中断时
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public boolean awaitCompletion(String sessionId, long timeout, TimeUnit unit)
            throws InterruptedException {
        var managed = getProcess(sessionId);
        managed.touch();
        boolean finished = managed.process().waitFor(timeout, unit);
        if (finished) {
            refreshProcessState(managed);
        }
        return finished;
    }

    /**
     * 读取指定进程的输出缓冲区增量。
     *
     * @param sessionId 会话标识
     * @return 自上次读取以来的新输出
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public String readOutput(String sessionId) {
        return readOutputChunk(sessionId).output();
    }

    /**
     * 读取指定进程的输出缓冲区增量快照。
     *
     * @param sessionId 会话标识
     * @return stdout/stderr 分离的增量快照
     * @throws IllegalArgumentException sessionId 不存在时
     */
    public ProcessOutputChunk readOutputChunk(String sessionId) {
        var managed = getProcess(sessionId);
        managed.touch();
        refreshProcessState(managed);
        String stdout = managed.stdoutBuffer().readIncremental();
        String stderr = managed.stderrBuffer().readIncremental();
        return new ProcessOutputChunk(
                stdout,
                stderr,
                mergeOutput(stdout, stderr),
                managed.currentState(),
                managed.exitCode().get()
        );
    }

    /**
     * 向指定进程的 stdin 写入内容。
     *
     * @param sessionId 会话标识
     * @param input     要写入的内容
     * @throws IllegalArgumentException sessionId 不存在时
     * @throws IllegalStateException    进程已结束时
     * @throws IOException              写入失败时
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

    /** SSE 事件 debounce 间隔（毫秒） — 攒批发送，减少高频推送。 */
    private static final long SSE_DEBOUNCE_INTERVAL_MS = 200;
    /** SSE 事件 debounce 缓冲区上限（字节） — 超过此值立即 flush。 */
    private static final int SSE_DEBOUNCE_BUFFER_LIMIT = 4096;

    /** 持续读取进程输出到环形缓冲区，并通过 debounce 攒批发布 SSE 推送事件。 */
    private void readProcessOutput(ManagedProcess managed,
                                   InputStream stream,
                                   RingBuffer buffer,
                                   String streamName) {
        var sseBuffer = new StringBuilder();
        long lastFlushTime = System.currentTimeMillis();

        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                String chunk = new String(buf, 0, read);
                buffer.append(chunk);

                // 将输出追加到 debounce 缓冲区
                if (eventPublisher != null) {
                    sseBuffer.append(chunk);
                    long now = System.currentTimeMillis();
                    // 缓冲区超过 4KB 或距上次 flush 超过 200ms 时发布事件
                    if (sseBuffer.length() >= SSE_DEBOUNCE_BUFFER_LIMIT
                            || (now - lastFlushTime) >= SSE_DEBOUNCE_INTERVAL_MS) {
                        eventPublisher.publishEvent(new ProcessOutputEvent(
                                this, managed.sessionId(), streamName,
                                sseBuffer.toString(), managed.currentState()));
                        sseBuffer.setLength(0);
                        lastFlushTime = now;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("进程{}读取结束: sessionId={}, reason={}", streamName, managed.sessionId(), e.getMessage());
        }

        // 进程结束时 flush 剩余缓冲
        if (eventPublisher != null && !sseBuffer.isEmpty()) {
            eventPublisher.publishEvent(new ProcessOutputEvent(
                    this, managed.sessionId(), streamName,
                    sseBuffer.toString(), managed.currentState()));
        }
    }

    /** 监控进程退出并更新状态。 */
    private void monitorProcessExit(ManagedProcess managed) {
        try {
            int exitCode = managed.process().waitFor();
            managed.exitCode().set(exitCode);
            if (managed.currentState() == ProcessState.RUNNING) {
                var newState = exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED;
                managed.state().set(newState);
                log.info("后台进程退出: sessionId={}, exitCode={}, state={}",
                        managed.sessionId(), exitCode, newState);
                // 发布进程状态变化事件
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new ProcessOutputEvent(
                            this, managed.sessionId(), "state",
                            "exitCode=" + exitCode, newState));
                }
            } else {
                log.info("后台进程退出: sessionId={}, exitCode={}, state={}",
                        managed.sessionId(), exitCode, managed.currentState());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("进程监控被中断: sessionId={}", managed.sessionId());
        }
    }

    private void refreshProcessState(ManagedProcess managed) {
        if (managed.currentState() != ProcessState.RUNNING || managed.process().isAlive()) {
            return;
        }
        Integer exitCode = managed.exitCode().get();
        if (exitCode == null) {
            try {
                exitCode = managed.process().exitValue();
                managed.exitCode().compareAndSet(null, exitCode);
            } catch (IllegalThreadStateException ignored) {
                return;
            }
        }
        if (exitCode != null && managed.currentState() == ProcessState.RUNNING) {
            managed.state().compareAndSet(ProcessState.RUNNING,
                    exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED);
        }
    }

    private ProcessInfo snapshotProcessInfo(ManagedProcess managed) {
        refreshProcessState(managed);
        return new ProcessInfo(
                managed.sessionId(),
                managed.command(),
                managed.currentState(),
                managed.exitCode().get(),
                managed.startTime(),
                managed.workDir().toString()
        );
    }

    private String mergeOutput(String stdout, String stderr) {
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout + stderr;
    }
}
