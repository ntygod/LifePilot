package com.lifepilot.meta.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 域名级请求限流器 — 令牌桶算法，按域名独立控制请求间隔。
 *
 * <p>防止对同一域名的高频请求触发 429 限流。收到 429 响应时调用
 * {@link #recordRateLimited(String)} 自动加大该域名的间隔（指数退避）。</p>
 *
 * <p>线程安全：使用 ConcurrentHashMap + synchronized per-bucket 保证并发正确性。</p>
 *
 * @author zsg
 * @since 2026-05-18
 */
public class DomainRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DomainRateLimiter.class);

    /** 默认最小请求间隔（毫秒）。 */
    private final long defaultMinIntervalMs;

    /** 429 退避后的最大间隔上限（毫秒），防止无限增长。 */
    private static final long MAX_INTERVAL_MS = 60_000;

    /** 退避乘数。 */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** 域名 → 限流桶。 */
    private final ConcurrentHashMap<String, DomainBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param defaultMinIntervalMs 默认最小请求间隔（毫秒），建议 1000
     */
    public DomainRateLimiter(long defaultMinIntervalMs) {
        this.defaultMinIntervalMs = Math.max(defaultMinIntervalMs, 0);
    }

    /**
     * 在发起请求前调用 — 如果需要等待则阻塞当前线程（virtual thread 友好）。
     *
     * @param url 目标 URL
     */
    public void acquirePermit(String url) {
        if (defaultMinIntervalMs <= 0) return;
        String domain = extractDomain(url);
        if (domain == null) return;

        var bucket = buckets.computeIfAbsent(domain, k -> new DomainBucket(defaultMinIntervalMs));
        bucket.acquire(domain);
    }

    /**
     * 记录某域名收到 429 响应 — 加大该域名的请求间隔。
     *
     * @param url 触发 429 的 URL
     */
    public void recordRateLimited(String url) {
        String domain = extractDomain(url);
        if (domain == null) return;

        var bucket = buckets.computeIfAbsent(domain, k -> new DomainBucket(defaultMinIntervalMs));
        bucket.backoff(domain);
    }

    /**
     * 记录某域名请求成功 — 逐步恢复正常间隔。
     *
     * @param url 成功的 URL
     */
    public void recordSuccess(String url) {
        String domain = extractDomain(url);
        if (domain == null) return;

        var bucket = buckets.get(domain);
        if (bucket != null) {
            bucket.recover();
        }
    }

    /** 从 URL 提取域名。 */
    private static String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单域名限流桶。 */
    private static class DomainBucket {
        private long currentIntervalMs;
        private final long baseIntervalMs;
        private long lastRequestTimeMs;

        DomainBucket(long baseIntervalMs) {
            this.baseIntervalMs = baseIntervalMs;
            this.currentIntervalMs = baseIntervalMs;
            this.lastRequestTimeMs = 0;
        }

        synchronized void acquire(String domain) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTimeMs;
            long waitMs = currentIntervalMs - elapsed;

            if (waitMs > 0) {
                log.debug("域名限流等待: domain={}, waitMs={}", domain, waitMs);
                try {
                    TimeUnit.MILLISECONDS.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTimeMs = System.currentTimeMillis();
        }

        synchronized void backoff(String domain) {
            long newInterval = (long) (currentIntervalMs * BACKOFF_MULTIPLIER);
            currentIntervalMs = Math.min(newInterval, MAX_INTERVAL_MS);
            log.info("域名限流退避: domain={}, newIntervalMs={}", domain, currentIntervalMs);
        }

        synchronized void recover() {
            if (currentIntervalMs > baseIntervalMs) {
                // 成功后缓慢恢复（每次成功减半差值）
                long diff = currentIntervalMs - baseIntervalMs;
                currentIntervalMs = baseIntervalMs + diff / 2;
            }
        }
    }
}
