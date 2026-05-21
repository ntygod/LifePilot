package com.lifepilot.memory.governance.security;

import org.springframework.lang.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 space 的 trustScore 分布统计 — 用于 Mahalanobis 距离异常检测。
 *
 * <p>每个 space 独立维护一个 FIFO 样本窗口（默认 1000 条），在线计算均值与标准差；
 * 给定新样本时返回 |x - mean| / stddev。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class SpaceTrustDistribution {

    /** 进入稳态估计所需的最小样本数。 */
    private static final int MIN_SAMPLES_FOR_ESTIMATION = 10;

    private final int windowSize;
    private final Map<String, Stats> bySpace = new ConcurrentHashMap<>();

    public SpaceTrustDistribution(int windowSize) {
        this.windowSize = Math.max(10, windowSize);
    }

    /** 加入新样本。 */
    public void observe(@Nullable String spaceId, float trustScore) {
        if (spaceId == null || spaceId.isBlank()) return;
        Stats stats = bySpace.computeIfAbsent(spaceId, _ -> new Stats());
        synchronized (stats) {
            stats.add(trustScore, windowSize);
        }
    }

    /** 计算 Mahalanobis 距离（简化为 1D：|x - mean| / stddev）。样本不足返回 0。 */
    public float mahalanobisDistance(@Nullable String spaceId, float trustScore) {
        if (spaceId == null || spaceId.isBlank()) return 0f;
        Stats stats = bySpace.get(spaceId);
        if (stats == null) return 0f;
        synchronized (stats) {
            if (stats.samples.size() < MIN_SAMPLES_FOR_ESTIMATION) return 0f;
            double stddev = stats.stddev();
            if (stddev <= 1e-6) return 0f;
            return (float) (Math.abs(trustScore - stats.mean()) / stddev);
        }
    }

    /** 判断是否偏离阈值。 */
    public boolean isOutlier(@Nullable String spaceId, float trustScore, float threshold) {
        return mahalanobisDistance(spaceId, trustScore) > threshold;
    }

    public int sampleCount(@Nullable String spaceId) {
        if (spaceId == null) return 0;
        Stats stats = bySpace.get(spaceId);
        if (stats == null) return 0;
        synchronized (stats) {
            return stats.samples.size();
        }
    }

    /** 单 space 样本统计。 */
    private static final class Stats {
        final Deque<Float> samples = new ArrayDeque<>();
        double sum;
        double sumSq;

        void add(float v, int windowSize) {
            samples.addLast(v);
            sum += v;
            sumSq += v * v;
            while (samples.size() > windowSize) {
                Float removed = samples.pollFirst();
                if (removed != null) {
                    sum -= removed;
                    sumSq -= removed * removed;
                }
            }
        }

        double mean() { return samples.isEmpty() ? 0.0 : sum / samples.size(); }

        double stddev() {
            int n = samples.size();
            if (n < 2) return 0.0;
            double m = mean();
            double variance = Math.max(0.0, (sumSq / n) - m * m);
            return Math.sqrt(variance);
        }
    }
}
