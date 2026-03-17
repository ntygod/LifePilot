package com.lifepilot.agent.suspend.event;

/**
 * A2A 远程 Agent 任务完成恢复事件 — 远程 Agent 返回结果后发布。
 *
 * @author zsg
 * @since 2026-03-17
 */
public record A2aTaskCompletedEvent(String remoteTaskId, String resultJson) {}
