package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.model.EnvironmentState;
import com.lifepilot.agent.intelligence.model.ToolHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(EnvironmentPerceptor.class);
    private static final long CACHE_TTL_MS = 60_000; // 60 秒缓存

    private final CapabilityAssessor capabilityAssessor;

    private volatile EnvironmentState cachedState;
    private volatile long cachedAt;

    public EnvironmentPerceptor(CapabilityAssessor capabilityAssessor) {
        this.capabilityAssessor = capabilityAssessor;
    }

    /**
     * 感知当前环境状态。结果缓存 60 秒。
     *
     * @param relevantToolIds 当前任务可能用到的工具 ID 集合
     * @return 环境状态快照
     */
    public EnvironmentState perceive(Set<String> relevantToolIds) {
        long now = System.currentTimeMillis();
        if (cachedState != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cachedState;
        }

        var timeContext = EnvironmentState.TimeContext.now();

        Map<String, ToolHealth> toolHealthMap = new HashMap<>();
        for (String toolId : relevantToolIds) {
            toolHealthMap.put(toolId, capabilityAssessor.getToolHealth(toolId));
        }

        var state = new EnvironmentState(
                EnvironmentState.UserActivityLevel.ACTIVE, // 默认活跃（对话中）
                timeContext,
                toolHealthMap,
                Instant.now()
        );

        this.cachedState = state;
        this.cachedAt = now;
        return state;
    }

    /**
     * 简化版感知 — 不指定工具集时只返回时间和活跃度。
     */
    public EnvironmentState perceive() {
        return perceive(Set.of());
    }
}
