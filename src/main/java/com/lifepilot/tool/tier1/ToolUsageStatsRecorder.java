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
 * 判断 session_count 是否需要 +1。日切时自动清空上一天的条目，
 * 避免长时间运行后 Map 无限膨胀。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolUsageStatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolUsageStatsRecorder.class);

    private final ToolUsageStatsRepository repository;
    private final ConcurrentHashMap<String, Set<String>> sessionDailyTools = new ConcurrentHashMap<>();
    private volatile String lastKnownDate = LocalDate.now().toString();

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
        rolloverIfNewDay(today);

        String sessionKey = event.sessionId() + "|" + today;
        boolean newSessionToday = !sessionDailyTools.containsKey(sessionKey);
        Set<String> seen = sessionDailyTools.computeIfAbsent(
                sessionKey, k -> ConcurrentHashMap.newKeySet());
        boolean firstTime = seen.add(event.toolId());
        try {
            if (newSessionToday) {
                // 当日首次见到该 session，登记到 daily_active_sessions 作为 coverage 分母来源
                repository.recordActiveSession(event.sessionId(), today);
            }
            repository.recordInvocation(event.toolId(), today, firstTime);
        } catch (Exception e) {
            log.warn("记录工具使用统计失败: toolId={}, session={}", event.toolId(), event.sessionId(), e);
        }
    }

    /** 日期变更时清除昨日及更早的条目，防止 Map 无限膨胀。 */
    private void rolloverIfNewDay(String today) {
        if (today.equals(lastKnownDate)) {
            return;
        }
        synchronized (this) {
            if (today.equals(lastKnownDate)) {
                return;
            }
            int before = sessionDailyTools.size();
            String suffix = "|" + today;
            sessionDailyTools.keySet().removeIf(k -> !k.endsWith(suffix));
            lastKnownDate = today;
            log.info("ToolUsageStatsRecorder 日切清理完成: beforeSize={}, afterSize={}",
                    before, sessionDailyTools.size());
        }
    }
}
