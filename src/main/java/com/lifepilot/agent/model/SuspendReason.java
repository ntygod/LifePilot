package com.lifepilot.agent.model;

import java.time.Instant;

/**
 * 挂起原因 sealed interface — 定义 5 种挂起场景。
 *
 * <p>sealed interface 保证 switch 穷举，新增场景编译器强制处理。
 * 每个 permit 只携带恢复时必需的最小上下文，所有字段不可变。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public sealed interface SuspendReason permits
        SuspendReason.WorkflowWait,
        SuspendReason.UserConfirmation,
        SuspendReason.RemoteDelegation,
        SuspendReason.ScheduledWakeup,
        SuspendReason.ExternalDataWait {

    /** 等待异步工作流完成。 */
    record WorkflowWait(String executionId, String workflowId, String workflowName)
            implements SuspendReason {}

    /** 等待用户确认高风险工具执行。 */
    record UserConfirmation(String toolId, String inputJson, String riskLevel, String confirmationId)
            implements SuspendReason {}

    /** 等待 A2A 远程 Agent 返回结果。 */
    record RemoteDelegation(String remoteTaskId, String remoteAgentUrl, String delegatedGoal)
            implements SuspendReason {}

    /** 定时恢复 — Agent 主动设置延迟。 */
    record ScheduledWakeup(Instant wakeupAt, String reason)
            implements SuspendReason {}

    /** 等待外部数据就绪（爬虫/ETL/文件上传等）。 */
    record ExternalDataWait(String dataSourceId, String description)
            implements SuspendReason {}
}
