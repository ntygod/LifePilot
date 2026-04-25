package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * ExternalDataWait -> DataReady，
 * BrowserTakeover -> BrowserTakeoverCompleted。</p>
 *
 * <p>持久化路径：{@code ReactStep.Resume} 携带 ResumePayload 进入 stateJson，
 * 从 SuspendStore 加载时若没有类型标签则只能拿到 sealed interface 抽象，
 * 因此必须显式声明 {@link JsonTypeInfo} 让反序列化能恢复到具体子类型。</p>
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
        @JsonSubTypes.Type(value = ResumePayload.WorkflowResult.class, name = "WorkflowResult"),
        @JsonSubTypes.Type(value = ResumePayload.UserDecision.class, name = "UserDecision"),
        @JsonSubTypes.Type(value = ResumePayload.RemoteResult.class, name = "RemoteResult"),
        @JsonSubTypes.Type(value = ResumePayload.WakeupSignal.class, name = "WakeupSignal"),
        @JsonSubTypes.Type(value = ResumePayload.DataReady.class, name = "DataReady"),
        @JsonSubTypes.Type(value = ResumePayload.BrowserTakeoverCompleted.class, name = "BrowserTakeoverCompleted")
})
public sealed interface ResumePayload permits
        ResumePayload.WorkflowResult,
        ResumePayload.UserDecision,
        ResumePayload.RemoteResult,
        ResumePayload.WakeupSignal,
        ResumePayload.DataReady,
        ResumePayload.BrowserTakeoverCompleted {

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

    /**
     * 浏览器人工接管完成信号。
     *
     * <p>当用户在前端点"放弃任务"时，上层监听器应把 note 以
     * {@link #USER_CANCELLED_PREFIX} 开头拼接原始备注，让 AgentOrchestrator
     * 在恢复入口直接硬终止，而不是把取消意图塞给 LLM 自行解读。</p>
     *
     * @param sessionId 浏览器会话 ID
     * @param note      用户可选备注（失败原因、放弃说明等，可空）
     */
    record BrowserTakeoverCompleted(String sessionId, @Nullable String note)
            implements ResumePayload {

        /**
         * 用户取消任务时 note 的前缀约定。
         *
         * <p>保持为字符串前缀而非布尔字段是降级选择 — 协议上对前端 / orchestrator /
         * LLM 都向后兼容，orchestrator 层检测到前缀直接走终止分支，避免 LLM 再绕一步。</p>
         */
        public static final String USER_CANCELLED_PREFIX = "[USER_CANCELLED]";

        /**
         * 判断 note 是否表示用户显式取消。
         *
         * <p>{@link JsonIgnore} 避免 Jackson 把该计算属性序列化成 {@code userCancelled} 字段，
         * 污染 SuspendStore 中 JSON 结构（反序列化时 record 只认 {@code sessionId}/{@code note}）。</p>
         */
        @JsonIgnore
        public boolean isUserCancelled() {
            return note != null && note.startsWith(USER_CANCELLED_PREFIX);
        }
    }
}
