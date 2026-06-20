package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.model.EnvironmentState;
import com.lifepilot.agent.intelligence.model.ToolHealth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境感知器 — 感知当前执行环境状态，为决策提供上下文。
 *
 * <p>环境状态包括：用户活跃度、时间特征、工具健康度等。
 * 感知结果缓存一段时间，避免频繁计算。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class EnvironmentPerceptor {

    private final CapabilityAssessor capabilityAssessor;
    private final long cacheTtlMs;

    private final ConcurrentHashMap<Set<String>, CachedEnvironmentState> cache = new ConcurrentHashMap<>();

    public EnvironmentPerceptor(CapabilityAssessor capabilityAssessor, int cacheTtlSeconds) {
        if (cacheTtlSeconds <= 0) {
            throw new IllegalArgumentException("环境感知缓存时间必须大于 0 秒");
        }
        this.capabilityAssessor = capabilityAssessor;
        this.cacheTtlMs = cacheTtlSeconds * 1000L;
    }

    /**
     * 感知当前环境状态。结果按工具集合缓存，避免不同工具集互相污染。
     *
     * @param relevantToolIds 当前任务可能用到的工具 ID 集合
     * @return 环境状态快照
     */
    public EnvironmentState perceive(Set<String> relevantToolIds) {
        long now = System.currentTimeMillis();
        Set<String> cacheKey = Set.copyOf(relevantToolIds);
        var cached = cache.get(cacheKey);
        if (cached != null && (now - cached.cachedAt()) < cacheTtlMs) {
            return cached.state();
        }

        var timeContext = EnvironmentState.TimeContext.now();

        Map<String, ToolHealth> toolHealthMap = new HashMap<>();
        for (String toolId : cacheKey) {
            toolHealthMap.put(toolId, capabilityAssessor.getToolHealth(toolId));
        }

        var state = new EnvironmentState(
                EnvironmentState.UserActivityLevel.ACTIVE, // 默认活跃（对话中）
                timeContext,
                toolHealthMap,
                Instant.now()
        );

        cache.put(cacheKey, new CachedEnvironmentState(state, now));
        return state;
    }

    /**
     * 简化版感知 — 不指定工具集时只返回时间和活跃度。
     */
    public EnvironmentState perceive() {
        return perceive(Set.of());
    }

    private record CachedEnvironmentState(EnvironmentState state, long cachedAt) {
    }
}
