package com.lifepilot.agent.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * 挂起原因 sealed interface，定义可恢复的挂起场景。
 *
 * <p>sealed interface 保证 switch 穷尽，新场景加入后编译器会强制补齐处理逻辑。
 * 每个子类型只携带恢复时必需的最小上下文。</p>
 *
 * <p>Jackson 多态标注：{@code SqliteSuspendStore} 当前通过额外的 {@code reason_type}
 * 列 + 手动类型映射表规避了 Jackson 多态问题，但该 sealed interface 同样可能被
 * {@code ReactStep.Suspend.reason} 字段随 checkpoint 整体 roundtrip，且未来若有人
 * 直接 {@code readValue(SuspendReason.class)} 会立即崩溃。此处主动声明类型信息，
 * 作为防御性护栏（不改动 SqliteSuspendStore 既有规避路径）。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SuspendReason.WorkflowWait.class, name = "WORKFLOW_WAIT"),
        @JsonSubTypes.Type(value = SuspendReason.UserConfirmation.class, name = "USER_CONFIRMATION"),
        @JsonSubTypes.Type(value = SuspendReason.RemoteDelegation.class, name = "REMOTE_DELEGATION"),
        @JsonSubTypes.Type(value = SuspendReason.ScheduledWakeup.class, name = "SCHEDULED_WAKEUP"),
        @JsonSubTypes.Type(value = SuspendReason.ExternalDataWait.class, name = "EXTERNAL_DATA_WAIT")
})
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

    /** 定时唤醒，Agent 主动设置延迟恢复。 */
    record ScheduledWakeup(Instant wakeupAt, String reason)
            implements SuspendReason {}

    /** 等待外部数据就绪，如爬虫、ETL 或文件上传。 */
    record ExternalDataWait(String dataSourceId, String description)
            implements SuspendReason {}
}
