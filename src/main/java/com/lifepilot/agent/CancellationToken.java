package com.lifepilot.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号令牌 — 用于跨线程传递取消意图。
 *
 * @author zsg
 * @since 2026-07-21
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 检查是否已取消。
     *
     * @return 若已取消返回 true
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 发送取消信号。多次调用幂等。
     */
    public void cancel() {
        cancelled.set(true);
    }
}
