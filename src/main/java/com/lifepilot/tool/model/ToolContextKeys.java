package com.lifepilot.tool.model;

/**
 * 工具执行上下文字段常量。
 *
 * @author zsg
 * @since 2026-03-23
 */
public final class ToolContextKeys {

    public static final String SESSION_ID = "sessionId";
    public static final String TURN_ID = "turnId";
    public static final String STREAM_ID = "streamId";
    public static final String USER_ID = "userId";
    public static final String CHANNEL_TYPE = "channelType";
    public static final String SOURCE_ID = "sourceId";
    public static final String SOURCE_KIND = "sourceKind";
    public static final String CHANNEL_PLATFORM = "channelPlatform";
    public static final String CHANNEL_INSTANCE_ID = "channelInstanceId";
    public static final String CALLER_TRACE_ID = "callerTraceId";
    public static final String CALLER_DEPTH = "callerDepth";
    public static final String CALLER_BUDGET = "callerBudget";
    public static final String TASK_ID = "taskId";
    public static final String WORKSPACE_ID = "workspaceId";

    private ToolContextKeys() {
    }
}
