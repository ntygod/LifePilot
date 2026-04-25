package com.lifepilot.agent.suspend.model;

/**
 * 工具输出中表示挂起意图的字段约定。
 *
 * <p>工具返回 {@code ToolResult.success(Map)} 时携带：
 * <pre>
 * {
 *   _suspend: true,
 *   _suspendReason: { type: "BrowserTakeover", sessionId: "...", reason: "..." }
 * }
 * </pre>
 *
 * <p>{@code ToolExecutionCoordinator.parseSuspendReasonFromOutput} 识别这两个字段，
 * 解析为 {@link com.lifepilot.agent.model.SuspendReason} 触发 Agent 挂起。</p>
 *
 * @author zsg
 * @since 2026-04-25
 */
public final class SuspendSignal {

    /** 工具结果中标记挂起意图的布尔字段名。 */
    public static final String FIELD_SUSPEND = "_suspend";

    /** 工具结果中携带挂起原因 payload 的字段名。 */
    public static final String FIELD_REASON = "_suspendReason";

    /** SuspendReason payload 中的类型字段名。 */
    public static final String FIELD_TYPE = "type";

    private SuspendSignal() {
        // 常量类禁止实例化
    }
}
