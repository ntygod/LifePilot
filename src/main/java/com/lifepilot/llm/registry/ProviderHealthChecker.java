package com.lifepilot.llm.registry;

import com.lifepilot.llm.adapter.ProviderAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Provider 健康检查器。
 *
 * <p>使用 Virtual Thread 并行检查所有 Provider 的健康状态，
 * 单个 Provider 超时 10 秒标记为不健康。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ProviderHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthChecker.class);
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 10;

    /**
     * 并行检查所有 Provider 的健康状态。
     *
     * @param adapters Provider ID 到适配器的映射
     * @return 不可变的健康状态映射，键为 Provider ID，值为是否健康
     */
    public Map<String, Boolean> checkAll(Map<String, ProviderAdapter> adapters) {
        var results = new ConcurrentHashMap<String, Boolean>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var entry : adapters.entrySet()) {
                var providerId = entry.getKey();
                var adapter = entry.getValue();
                executor.submit(() -> {
                    try {
                        boolean healthy = adapter.healthCheck();
                        results.put(providerId, healthy);
                        log.debug("Provider 健康检查完成: id={}, healthy={}", providerId, healthy);
                    } catch (Exception e) {
                        // adapter.healthCheck() 应该已经捕获了异常，这里捕获的是意外情况
                        results.put(providerId, false);
                        log.debug("Provider 健康检查异常: id={}, error={}", providerId, e.getMessage());
                    }
                });
            }
            // 等待所有任务完成，超时后未完成的标记为不健康
            executor.shutdown();
            if (!executor.awaitTermination(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Provider 健康检查超时（{}秒），部分 Provider 未完成检查", HEALTH_CHECK_TIMEOUT_SECONDS);
                // 未完成检查的 Provider 标记为不健康
                for (var id : adapters.keySet()) {
                    results.putIfAbsent(id, false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Provider 健康检查被中断");
            for (var id : adapters.keySet()) {
                results.putIfAbsent(id, false);
            }
        }

        // 记录汇总信息
        long healthyCount = results.values().stream().filter(b -> b).count();
        long totalCount = results.size();
        if (healthyCount < totalCount) {
            log.debug("Provider 健康检查汇总: {}/{} 个 Provider 健康", healthyCount, totalCount);
        }

        return Map.copyOf(results);
    }
}
