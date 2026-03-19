package com.lifepilot.meta.infra.shell;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 受管后台进程 — 封装 Process 实例及其元数据。
 *
 * <p>{@code state} 和 {@code lastAccessTime} 使用 {@link AtomicReference}
 * 支持并发安全的状态更新。</p>
 *
 * @param sessionId 唯一会话标识
 * @param process 底层操作系统进程
 * @param outputBuffer 环形输出缓冲区（stdout + stderr 合并）
 * @param state 进程状态
 * @param startTime 启动时间
 * @param lastAccessTime 最后访问时间（用于空闲超时计算）
 * @param command 启动命令
 * @param workDir 工作目录
 * @author zsg
 * @since 2026-03-20
 */
public record ManagedProcess(
        String sessionId,
        Process process,
        RingBuffer outputBuffer,
        AtomicReference<ProcessState> state,
        Instant startTime,
        AtomicReference<Instant> lastAccessTime,
        String command,
        Path workDir
) {

    /** 更新最后访问时间为当前时刻。 */
    public void touch() {
        lastAccessTime.set(Instant.now());
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
