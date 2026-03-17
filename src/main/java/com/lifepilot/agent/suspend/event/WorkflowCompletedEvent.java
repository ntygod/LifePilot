package com.lifepilot.agent.suspend.event;

/**
 * 工作流完成恢复事件 — 异步工作流执行完毕后发布。
 *
 * @author zsg
 * @since 2026-03-17
 */
public record WorkflowCompletedEvent(String executionId, String status, String outputJson) {}
