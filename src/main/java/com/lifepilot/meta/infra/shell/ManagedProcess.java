package com.lifepilot.meta.infra.shell;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 受管后台进程 — 封装 Process 实例及其元数据。
 *
 * <p>{@code state} 和 {@code lastAccessTime} 使用 {@link AtomicReference}
 * 支持并发安全的状态更新。</p>
 *
 * @param sessionId 唯一会话标识
 * @param process 底层操作系统进程
 * @param stdoutBuffer stdout 环形输出缓冲区
 * @param stderrBuffer stderr 环形输出缓冲区
 * @param state 进程状态
 * @param exitCode 真实退出码，运行中时为 null
 * @param startTime 启动时间
 * @param lastAccessTime 最后访问时间（用于空闲超时计算）
 * @param command 启动命令
 * @param workDir 工作目录
 * @param outputDrainLatch stdout/stderr 读取线程排空信号（count=2）
 * @author zsg
 * @since 2026-03-20
 */
public record ManagedProcess(
        String sessionId,
        Process process,
        RingBuffer stdoutBuffer,
        RingBuffer stderrBuffer,
        AtomicReference<ProcessState> state,
        AtomicReference<Integer> exitCode,
        Instant startTime,
        AtomicReference<Instant> lastAccessTime,
        String command,
        Path workDir,
        CountDownLatch outputDrainLatch
) {

    /** 更新最后访问时间为当前时刻。 */
    public void touch() {
        lastAccessTime.set(Instant.now());
    }

    /**
     * 等待 stdout/stderr 读取线程排空完成。
     * 进程退出后调用，确保输出已全部写入 ring buffer。
     */
    public void awaitOutputDrain(long timeout, TimeUnit unit) throws InterruptedException {
        outputDrainLatch.await(timeout, unit);
    }

    /** 获取当前进程状态。 */
    public ProcessState currentState() {
        return state.get();
    }

    /** 判断进程是否仍在运行。 */
    public boolean isRunning() {
        return state.get() == ProcessState.RUNNING;
    }
}
