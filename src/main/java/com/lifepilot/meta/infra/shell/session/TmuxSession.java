package com.lifepilot.meta.infra.shell.session;

import com.lifepilot.meta.infra.shell.RingBuffer;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tmux 持久会话内部跟踪记录。
 *
 * <p>使用 {@link AtomicReference} 实现线程安全的可变状态，
 * 与 {@link com.lifepilot.meta.infra.shell.ManagedProcess} 保持一致的并发模式。</p>
 *
 * @param sessionId      唯一会话标识（8 位 UUID 片段）
 * @param tmuxName       tmux 会话名称，格式 "zhiwei-{sessionId}"
 * @param state          会话状态
 * @param cwd            当前工作目录
 * @param createdAt      创建时间
 * @param lastAccessTime 最后访问时间（用于空闲超时计算）
 * @param outputBuffer   输出环形缓冲区，复用 {@link RingBuffer}
 * @author zsg
 * @since 2026-03-31
 */
public record TmuxSession(
        String sessionId,
        String tmuxName,
        AtomicReference<SessionState> state,
        AtomicReference<String> cwd,
        Instant createdAt,
        AtomicReference<Instant> lastAccessTime,
        RingBuffer outputBuffer
) {

    /** 更新最后访问时间为当前时刻。 */
    public void touch() {
        lastAccessTime.set(Instant.now());
    }

    /** 获取当前会话状态。 */
    public SessionState currentState() {
        return state.get();
    }
}
