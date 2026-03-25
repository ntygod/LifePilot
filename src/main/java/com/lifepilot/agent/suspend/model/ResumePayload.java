package com.lifepilot.agent.suspend.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 恢复载荷 sealed interface，与 SuspendReason 一一对应。
 *
 * <p>恢复时通过类型匹配校验配对正确性：
 * WorkflowWait -> WorkflowResult，
 * UserConfirmation -> UserDecision，
 * RemoteDelegation -> RemoteResult，
 * ScheduledWakeup -> WakeupSignal，
 * ExternalDataWait -> DataReady。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public sealed interface ResumePayload permits
        ResumePayload.WorkflowResult,
        ResumePayload.UserDecision,
        ResumePayload.RemoteResult,
        ResumePayload.WakeupSignal,
        ResumePayload.DataReady {

    /** 工作流完成结果。 */
    record WorkflowResult(String executionId, String status, String outputJson)
            implements ResumePayload {}

    /** 用户确认决定。 */
    record UserDecision(String confirmationId, boolean approved, @Nullable String reason)
            implements ResumePayload {}

    /** 远程 Agent 返回结果。 */
    record RemoteResult(String remoteTaskId, String resultJson)
            implements ResumePayload {}

    /** 定时唤醒信号。 */
    record WakeupSignal(Instant actualWakeupAt)
            implements ResumePayload {}

    /** 外部数据就绪。 */
    record DataReady(String dataSourceId, String dataLocationOrContent)
            implements ResumePayload {}
}
