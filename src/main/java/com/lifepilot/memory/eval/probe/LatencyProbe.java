package com.lifepilot.memory.eval.probe;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 延迟采集器。
 *
 * <p>用法：</p>
 * <pre>
 *   long t0 = probe.start();
 *   doRetrieve();
 *   probe.stop(t0);
 *   ...
 *   LatencySummary summary = probe.summary();
 * </pre>
 *
 * <p>内部收集每次调用的纳秒耗时；{@link #summary()} 计算 p50/p95/p99。线程安全。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class LatencyProbe {

    private final ConcurrentLinkedQueue<Long> samplesNanos = new ConcurrentLinkedQueue<>();

    public long start() {
        return System.nanoTime();
    }

    public void stop(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed < 0) elapsed = 0;
        samplesNanos.offer(elapsed);
    }

    public int sampleCount() {
        return samplesNanos.size();
    }

    public void reset() {
        samplesNanos.clear();
    }

    /**
     * 计算 p50 / p95 / p99。
     * 没有样本时返回全 0。
     */
    public LatencySummary summary() {
        if (samplesNanos.isEmpty()) {
            return new LatencySummary(0, 0, 0, 0);
        }
        long[] arr = samplesNanos.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);
        return new LatencySummary(
                percentile(arr, 50) / 1_000_000L,
                percentile(arr, 95) / 1_000_000L,
                percentile(arr, 99) / 1_000_000L,
                arr.length);
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        // 使用 nearest-rank 方式：索引 = ceil(p/100 * n) - 1
        int idx = Math.min(sorted.length - 1,
                (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        if (idx < 0) idx = 0;
        return sorted[idx];
    }

    /** 延迟汇总。单位：毫秒。 */
    public record LatencySummary(long p50Ms, long p95Ms, long p99Ms, int sampleCount) {}
}
