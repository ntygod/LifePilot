package com.lifepilot.agent.model;

import java.time.Instant;

/**
 * 挂起原因 sealed interface，定义可恢复的挂起场景。
 *
 * <p>sealed interface 保证 switch 穷尽，新场景加入后编译器会强制补齐处理逻辑。
 * 每个子类型只携带恢复时必需的最小上下文。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public sealed interface SuspendReason permits
        SuspendReason.WorkflowWait,
        SuspendReason.UserConfirmation,
        SuspendReason.RemoteDelegation,
        SuspendReason.ScheduledWakeup,
        SuspendReason.ExternalDataWait,
        SuspendReason.BrowserTakeover {

    /** 等待异步工作流完成。 */
    record WorkflowWait(String executionId, String workflowId, String workflowName)
            implements SuspendReason {}

    /** 等待用户确认高风险工具执行。 */
    record UserConfirmation(String toolId, String inputJson, String riskLevel, String confirmationId)
            implements SuspendReason {}

    /** 等待 A2A 远程 Agent 返回结果。 */
    record RemoteDelegation(String remoteTaskId, String remoteAgentUrl, String delegatedGoal)
            implements SuspendReason {}

    /** 定时唤醒，Agent 主动设置延迟恢复。 */
    record ScheduledWakeup(Instant wakeupAt, String reason)
            implements SuspendReason {}

    /** 等待外部数据就绪，如爬虫、ETL 或文件上传。 */
    record ExternalDataWait(String dataSourceId, String description)
            implements SuspendReason {}

    /**
     * 等待用户在浏览器中完成人工接管（验证码、登录、扫码、人机验证、账号保护）。
     *
     * @param sessionId      浏览器会话 ID
     * @param reason         展示给用户的接管原因说明
     * @param requestedAt    请求时刻
     * @param timeoutSeconds 前端提示的挂起等待超时秒数，可空则由前端采用默认值
     */
    record BrowserTakeover(String sessionId, String reason, Instant requestedAt, Integer timeoutSeconds)
            implements SuspendReason {}
}
