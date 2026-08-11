package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.model.ToolHealth;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力评估器 — 基于历史执行数据评估 Agent 在不同任务/工具上的能力水平。
 *
 * <p>能力评估结果用于：
 * <ul>
 *   <li>决策顾问注入上下文，让 LLM 知道哪些工具/模式更可靠</li>
 *   <li>预算分配参考（擅长的任务给更多步数，不擅长的提前预警）</li>
 *   <li>快速放弃决策（工具不健康时建议切换策略）</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class CapabilityAssessor {

    /** 工具健康状态缓存（内存，进程重启后重建）。 */
    private final ConcurrentHashMap<String, ToolHealthAccumulator> healthMap = new ConcurrentHashMap<>();
    private final int toolHealthWindowSize;
    private final Clock clock;

    public CapabilityAssessor(int toolHealthWindowSize) {
        this(toolHealthWindowSize, Clock.systemDefaultZone());
    }

    CapabilityAssessor(int toolHealthWindowSize, Clock clock) {
        if (toolHealthWindowSize <= 0) {
            throw new IllegalArgumentException("工具健康滑动窗口大小必须大于 0");
        }
        this.toolHealthWindowSize = toolHealthWindowSize;
        this.clock = clock;
    }

    /**
     * 记录一次工具执行结果。
     *
     * @param toolId    工具 ID
     * @param success   是否成功
     * @param latencyMs 响应时间
     * @param error     错误信息（成功时为 null）
     */
    public void recordExecution(String toolId, boolean success, long latencyMs, @Nullable String error) {
        healthMap.computeIfAbsent(toolId, k -> new ToolHealthAccumulator(k, toolHealthWindowSize, clock))
                .record(success, latencyMs, error);
    }

    /**
     * 获取工具健康状态。
     */
    public ToolHealth getToolHealth(String toolId) {
        var acc = healthMap.get(toolId);
        return acc != null ? acc.snapshot() : ToolHealth.unknown(toolId);
    }

    /**
     * 评估工具熟练度。
     *
     * @param toolId 工具 ID
     * @return 熟练度 [0, 1]，基于成功率和使用频率
     */
    public float assessToolProficiency(String toolId) {
        var health = getToolHealth(toolId);
        if (health.recentSuccesses() + health.recentFailures() == 0) {
            return 0.5f; // 无数据时返回中性值
        }
        return health.successRate();
    }

    /**
     * 快速判断是否应该放弃当前工具路径。
     *
     * @param toolId              工具 ID
     * @param consecutiveFailures 连续失败次数
     * @param lastError           最近错误信息
     * @return 放弃建议
     */
    public AbandonAdvice shouldAbandon(String toolId, int consecutiveFailures, @Nullable String lastError) {
        if (consecutiveFailures == 0) {
            return AbandonAdvice.CONTINUE;
        }

        var category = categorizeError(lastError);

        // 不可恢复错误 → 快速放弃
        if (category == ErrorCategory.UNRECOVERABLE) {
            return consecutiveFailures >= 2 ? AbandonAdvice.ESCALATE : AbandonAdvice.SWITCH_STRATEGY;
        }

        // 被阻止 → 切换策略
        if (category == ErrorCategory.BLOCKED) {
            return consecutiveFailures >= 3 ? AbandonAdvice.ESCALATE : AbandonAdvice.SWITCH_STRATEGY;
        }

        // 检查工具健康状态
        var health = getToolHealth(toolId);
        if (!health.isHealthy() && consecutiveFailures > 0) {
            return AbandonAdvice.SWITCH_STRATEGY;
        }

        // 限流 → 等待后重试（但不无限等）
        if (category == ErrorCategory.RATE_LIMITED) {
            return consecutiveFailures >= 3 ? AbandonAdvice.SWITCH_STRATEGY : AbandonAdvice.CONTINUE;
        }

        // 暂时性错误 → 基于连续失败次数
        if (consecutiveFailures >= 5) {
            return AbandonAdvice.ESCALATE;
        }
        if (consecutiveFailures >= 3 && health.successRate() < 0.3f) {
            return AbandonAdvice.SWITCH_STRATEGY;
        }

        return AbandonAdvice.CONTINUE;
    }

    /**
     * 错误分类。
     */
    public ErrorCategory categorizeError(@Nullable String error) {
        if (error == null || error.isBlank()) return ErrorCategory.UNKNOWN;
        String lower = error.toLowerCase();

        if (lower.contains("not found") || lower.contains("404")
                || lower.contains("permission denied") || lower.contains("not installed")
                || lower.contains("command not found")) {
            return ErrorCategory.UNRECOVERABLE;
        }
        if (lower.contains("403") || lower.contains("captcha")
                || lower.contains("blocked") || lower.contains("login required")) {
            return ErrorCategory.BLOCKED;
        }
        if (lower.contains("429") || lower.contains("rate limit")
                || lower.contains("too many requests")) {
            return ErrorCategory.RATE_LIMITED;
        }
        if (lower.contains("timeout") || lower.contains("connection")
                || lower.contains("503") || lower.contains("502")) {
            return ErrorCategory.TRANSIENT;
        }
        return ErrorCategory.UNKNOWN;
    }

    public enum AbandonAdvice {
        CONTINUE,
        SWITCH_STRATEGY,
        ESCALATE
    }

    public enum ErrorCategory {
        UNRECOVERABLE,
        TRANSIENT,
        RATE_LIMITED,
        BLOCKED,
        UNKNOWN
    }

    /**
     * 滑动窗口累加器 — 维护最近 N 次执行的统计。
     */
    private static class ToolHealthAccumulator {
        private final String toolId;
        private final int windowSize;
        private final Clock clock;
        private final Deque<ToolExecutionRecord> records = new ArrayDeque<>();

        ToolHealthAccumulator(String toolId, int windowSize, Clock clock) {
            this.toolId = toolId;
            this.windowSize = windowSize;
            this.clock = clock;
        }

        synchronized void record(boolean success, long latencyMs, @Nullable String error) {
            records.addLast(new ToolExecutionRecord(success, Math.max(0, latencyMs), error, Instant.now(clock)));
            while (records.size() > windowSize) {
                records.removeFirst();
            }
        }

        synchronized ToolHealth snapshot() {
            int successes = 0;
            int failures = 0;
            long totalLatency = 0;
            String lastError = null;
            Instant lastExecutedAt = null;

            for (ToolExecutionRecord record : records) {
                if (record.success()) {
                    successes++;
                } else {
                    failures++;
                    lastError = record.error();
                }
                totalLatency += record.latencyMs();
                lastExecutedAt = record.executedAt();
            }

            long avgLatency = records.isEmpty() ? 0 : totalLatency / records.size();
            return new ToolHealth(toolId, successes, failures, avgLatency, lastError, lastExecutedAt);
        }
    }

    private record ToolExecutionRecord(
            boolean success,
            long latencyMs,
            @Nullable String error,
            Instant executedAt
    ) {
    }
}
