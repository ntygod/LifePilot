package com.lifepilot.tool.model;

/**
 * 工具执行上下文字段常量 — 由调用方（{@code ToolExecutionCoordinator} / {@code ToolBridge}）
 * 通过 {@link ToolInput#context()} 传递给工具 executor，承载非 LLM 参数（会话 / 来源 / 调用者）。
 *
 * <p>所有 key 都是约定字符串，使用方需确保 value 类型与下方注释一致；类型不匹配时
 * 工具应静默降级（按 null 处理）而不是抛错。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public final class ToolContextKeys {

    /** 当前会话 ID（{@link String}）— 用于审计、限流、按会话隔离副作用。 */
    public static final String SESSION_ID = "sessionId";
    /** 当前轮次 ID（{@link String}）— 同会话内多轮请求的关联键。 */
    public static final String TURN_ID = "turnId";
    /** SSE 流标识（{@link String}）— 工具触发 UI 更新或审批弹窗时回写到正确流。 */
    public static final String STREAM_ID = "streamId";
    /** 用户 ID（{@link String}）— 用于权限判定。 */
    public static final String USER_ID = "userId";
    /** 渠道类型（{@link String}）— 如 web / wechat-work / dingtalk / feishu / cron。 */
    public static final String CHANNEL_TYPE = "channelType";
    /** 渠道源 ID（{@link String}）— InteractionSource.sourceId 的副本。 */
    public static final String SOURCE_ID = "sourceId";
    /** 渠道源类型（{@link com.lifepilot.interaction.model.SourceKind}）— 区分 USER / CRON / HEARTBEAT 等。 */
    public static final String SOURCE_KIND = "sourceKind";
    /** 渠道平台标识（{@link String}）— 多平台同 type 时区分实例。 */
    public static final String CHANNEL_PLATFORM = "channelPlatform";
    /** 渠道实例 ID（{@link String}）— 多租户场景区分单平台多账号。 */
    public static final String CHANNEL_INSTANCE_ID = "channelInstanceId";
    /** 父级调用 traceId（{@link String}）— 工具触发子 Agent 时传给子调用关联根 trace。 */
    public static final String CALLER_TRACE_ID = "callerTraceId";
    /** 嵌套调用层级（{@link Integer}）— 用于防止递归过深。 */
    public static final String CALLER_DEPTH = "callerDepth";
    /** 子调用预算（{@link com.lifepilot.agent.model.Budget}）— spawn_workers / 子 Agent 时透传。 */
    public static final String CALLER_BUDGET = "callerBudget";
    /** 受限工具白名单（{@link java.util.List}{@code <String>}）— 子 Agent 限制可见工具集时使用。 */
    public static final String ALLOWED_TOOL_IDS = "allowedToolIds";
    /** 当前任务 ID（{@link String}）— cron 触发时关联 cron_task 表。 */
    public static final String TASK_ID = "taskId";
    /** 工作区 ID（{@link String}）— 涉及工作区文件的工具用于解析路径根。 */
    public static final String WORKSPACE_ID = "workspaceId";
    /** 调用方 ReactAgentState 引用（{@link com.lifepilot.agent.model.ReactAgentState}）—
     * 供 meta 工具（{@code tools.search/describe/list}）访问 activatedToolIds 等当轮状态。 */
    public static final String CALLER_STATE = "callerState";

    private ToolContextKeys() {
    }
}
