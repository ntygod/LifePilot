package com.lifepilot.tool.tier1;

import com.lifepilot.tool.pipeline.ToolInvocationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听 ToolInvocationEvent 写入每日统计。
 *
 * <p>通过内存 Map 跟踪"(sessionId + date) → 已见过的 toolId 集合"，
 * 判断 session_count 是否需要 +1。该 Map 只活一天（轮转时重启）
 * 不做持久化，统计粒度"近似"即可。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolUsageStatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolUsageStatsRecorder.class);

    private final ToolUsageStatsRepository repository;
    private final ConcurrentHashMap<String, Set<String>> sessionDailyTools = new ConcurrentHashMap<>();

    public ToolUsageStatsRecorder(ToolUsageStatsRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onInvocation(ToolInvocationEvent event) {
        if (!event.success()) {
            return;
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            return;    // 无 session 上下文，跳过
        }
        String today = LocalDate.now().toString();
        String sessionKey = event.sessionId() + "|" + today;
        Set<String> seen = sessionDailyTools.computeIfAbsent(
                sessionKey, k -> ConcurrentHashMap.newKeySet());
        boolean firstTime = seen.add(event.toolId());
        try {
            repository.recordInvocation(event.toolId(), today, firstTime);
        } catch (Exception e) {
            log.warn("记录工具使用统计失败: toolId={}, session={}", event.toolId(), event.sessionId(), e);
        }
    }
}
