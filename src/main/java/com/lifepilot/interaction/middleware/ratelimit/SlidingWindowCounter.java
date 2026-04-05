package com.lifepilot.interaction.middleware.ratelimit;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 滑动窗口计数器，按请求数限流。
 *
 * <p>使用 {@link ConcurrentLinkedDeque} 存储请求时间戳，
 * 窗口内成功的 {@link #tryAcquire()} 次数不超过 maxRequests。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>任意滑动窗口内成功获取次数不超过 maxRequests</li>
 *   <li>{@link #currentCount()} 返回当前窗口内的有效请求数</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SlidingWindowCounter {

    /** 窗口内最大请求数 */
    private final int maxRequests;

    /** 窗口大小（毫秒） */
    private final long windowSizeMs;

    /** 请求时间戳队列 */
    private final ConcurrentLinkedDeque<Long> timestamps;

    /**
     * 创建滑动窗口计数器。
     *
     * @param maxRequests  窗口内最大请求数，必须大于 0
     * @param windowSizeMs 窗口大小（毫秒），必须大于 0
     * @throws IllegalArgumentException 如果 maxRequests ≤ 0 或 windowSizeMs ≤ 0
     */
    public SlidingWindowCounter(int maxRequests, long windowSizeMs) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests 必须大于 0，当前值: " + maxRequests);
        }
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("windowSizeMs 必须大于 0，当前值: " + windowSizeMs);
        }
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.timestamps = new ConcurrentLinkedDeque<>();
    }

    /**
     * 尝试获取一次请求许可。
     *
     * <p>使用 synchronized 保证 evict + size 检查 + add 的原子性，
     * 避免并发下超过 maxRequests 的请求通过。
     *
     * @return true 如果获取成功，false 如果窗口内请求数已达上限
     */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        evictExpired(now);
        if (timestamps.size() >= maxRequests) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /**
     * 获取当前窗口内的有效请求数。
     *
     * @return 当前窗口内的请求数
     */
    public synchronized int currentCount() {
        evictExpired(System.currentTimeMillis());
        return timestamps.size();
    }

    /**
     * 获取窗口内最大请求数。
     *
     * @return 最大请求数
     */
    public int maxRequests() {
        return maxRequests;
    }

    /**
     * 获取窗口大小（毫秒）。
     *
     * @return 窗口大小
     */
    public long windowSizeMs() {
        return windowSizeMs;
    }

    /**
     * 清除窗口外的过期时间戳。
     *
     * @param now 当前时间戳（毫秒）
     */
    private void evictExpired(long now) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= now - windowSizeMs) {
            timestamps.pollFirst();
        }
    }
}
