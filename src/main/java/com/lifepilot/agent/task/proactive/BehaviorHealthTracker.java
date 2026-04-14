package com.lifepilot.agent.task.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行为插件健康追踪器 — 记录连续异常次数，超阈值时降级。
 *
 * <p>连续 N 次异常后标记该插件为降级状态，心跳时跳过。
 * 每次成功执行重置计数。降级持续一段时间后自动恢复尝试。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class BehaviorHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(BehaviorHealthTracker.class);

    /** 连续失败阈值 — 超过此值降级。 */
    private final int degradeThreshold;

    /** 降级恢复间隔心跳次数 — 降级后每隔 N 次心跳尝试恢复一次。 */
    private final int recoveryInterval;

    private final Map<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> degradedSince = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> heartbeatsSinceDegraded = new ConcurrentHashMap<>();

    public BehaviorHealthTracker() {
        this(3, 5);
    }

    public BehaviorHealthTracker(int degradeThreshold, int recoveryInterval) {
        this.degradeThreshold = Math.max(1, degradeThreshold);
        this.recoveryInterval = Math.max(1, recoveryInterval);
    }

    /** 记录一次成功执行，重置计数。 */
    public void recordSuccess(String behaviorName) {
        consecutiveFailures.computeIfAbsent(behaviorName, _ -> new AtomicInteger(0)).set(0);
        if (degradedSince.remove(behaviorName) != null) {
            heartbeatsSinceDegraded.remove(behaviorName);
            log.info("行为插件已恢复: behavior={}", behaviorName);
        }
    }

    /** 记录一次异常，返回当前连续失败次数。 */
    public int recordFailure(String behaviorName, Exception e) {
        int count = consecutiveFailures.computeIfAbsent(behaviorName, _ -> new AtomicInteger(0))
                .incrementAndGet();

        if (count == degradeThreshold) {
            degradedSince.put(behaviorName, Instant.now());
            heartbeatsSinceDegraded.put(behaviorName, new AtomicInteger(0));
            log.error("行为插件已降级: behavior={}, consecutiveFailures={}, lastError={}",
                    behaviorName, count, e.getMessage());
        } else if (count > degradeThreshold) {
            log.warn("行为插件持续异常（已降级）: behavior={}, consecutiveFailures={}, error={}",
                    behaviorName, count, e.getMessage());
        } else {
            log.warn("行为插件异常: behavior={}, consecutiveFailures={}/{}, error={}",
                    behaviorName, count, degradeThreshold, e.getMessage());
        }
        return count;
    }

    /** 判断插件是否可运行（未降级，或降级后到了恢复尝试间隔）。 */
    public boolean isHealthy(String behaviorName) {
        if (!degradedSince.containsKey(behaviorName)) {
            return true;
        }
        // 降级后每隔 recoveryInterval 次心跳尝试恢复一次
        var counter = heartbeatsSinceDegraded.get(behaviorName);
        if (counter != null && counter.incrementAndGet() >= recoveryInterval) {
            counter.set(0);
            log.debug("行为插件尝试恢复: behavior={}", behaviorName);
            return true;
        }
        return false;
    }

    /** 获取插件连续失败次数。 */
    public int getConsecutiveFailures(String behaviorName) {
        var counter = consecutiveFailures.get(behaviorName);
        return counter != null ? counter.get() : 0;
    }

    /** 插件是否处于降级状态。 */
    public boolean isDegraded(String behaviorName) {
        return degradedSince.containsKey(behaviorName);
    }
}
