package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
 * <p>Jackson 多态标注：作为 {@code ReactStep.Resume.payload} 的字段类型，
 * 会随 checkpoint 整体 roundtrip；sealed interface 默认不写类型信息，
 * 必须显式声明否则反序列化崩溃（与 {@code ReactStep} 同理）。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ResumePayload.WorkflowResult.class, name = "WORKFLOW_RESULT"),
        @JsonSubTypes.Type(value = ResumePayload.UserDecision.class, name = "USER_DECISION"),
        @JsonSubTypes.Type(value = ResumePayload.RemoteResult.class, name = "REMOTE_RESULT"),
        @JsonSubTypes.Type(value = ResumePayload.WakeupSignal.class, name = "WAKEUP_SIGNAL"),
        @JsonSubTypes.Type(value = ResumePayload.DataReady.class, name = "DATA_READY")
})
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
