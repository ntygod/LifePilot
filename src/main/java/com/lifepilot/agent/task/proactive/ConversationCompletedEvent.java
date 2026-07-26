package com.lifepilot.agent.task.proactive;

import org.springframework.context.ApplicationEvent;
import org.springframework.lang.Nullable;

/**
 * 对话完成事件 — 由对话管线发布，触发认知闭环的"最后一公里"。
 *
 * <p>目前在 SSE 端点的对话完成回调中发布。
 * 任何新增的对话入口（WebSocket、Channel adapter 等）也应发布此事件。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ConversationCompletedEvent extends ApplicationEvent {

    private final String userId;
    private final String sessionId;
    @Nullable
    private final String turnId;
    @Nullable
    private final String summary;
    private final boolean memoryLearningEnabled;
    @Nullable
    private final String memoryLearningSkipReason;

    public ConversationCompletedEvent(Object source,
                                      String userId,
                                      String sessionId,
                                      @Nullable String turnId,
                                      @Nullable String summary,
                                      boolean memoryLearningEnabled,
                                      @Nullable String memoryLearningSkipReason) {
        super(source);
        this.userId = userId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.summary = summary;
        this.memoryLearningEnabled = memoryLearningEnabled;
        this.memoryLearningSkipReason = memoryLearningSkipReason;
    }

    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    @Nullable public String getTurnId() { return turnId; }
    @Nullable public String getSummary() { return summary; }
    public boolean isMemoryLearningEnabled() { return memoryLearningEnabled; }
    @Nullable public String getMemoryLearningSkipReason() { return memoryLearningSkipReason; }
}
