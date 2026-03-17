package com.lifepilot.agent.suspend;

import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.suspend.event.*;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * 统一恢复监听器 — 收口所有恢复信号，匹配挂起记录后调用 resumeFromSuspend。
 *
 * <p>每个 {@code @EventListener} 方法对应一种恢复事件类型，
 * 查询 SuspendStore 匹配挂起记录，构建对应的 ResumePayload，
 * 调用 {@link ReactAgentLoop#resumeFromSuspend(String, ResumePayload)}。
 * 未匹配时 log.warn 并丢弃，不抛异常。</p>
 *
 * <p>通过 {@link com.lifepilot.agent.suspend.config.SuspendAutoConfiguration} 注册为 Bean，
 * 不使用 {@code @Component} 以确保依赖顺序正确。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public class AgentResumeListener {

    private static final Logger log = LoggerFactory.getLogger(AgentResumeListener.class);

    private final ReactAgentLoop agentLoop;
    private final SuspendStore suspendStore;

    public AgentResumeListener(ReactAgentLoop agentLoop, SuspendStore suspendStore) {
        this.agentLoop = agentLoop;
        this.suspendStore = suspendStore;
    }

    /** 工作流完成 → 恢复等待该工作流的 Agent。 */
    @EventListener
    public void onWorkflowCompleted(WorkflowCompletedEvent event) {
        List<SuspendedAgent> candidates = suspendStore.findByReasonType("WorkflowWait");
        var matched = candidates.stream()
                .filter(sa -> sa.suspendReason() instanceof com.lifepilot.agent.model.SuspendReason.WorkflowWait ww
                        && ww.executionId().equals(event.executionId()))
                .findFirst();
        if (matched.isPresent()) {
            var payload = new ResumePayload.WorkflowResult(
                    event.executionId(), event.status(), event.outputJson());
            agentLoop.resumeFromSuspend(matched.get().traceId(), payload);
        } else {
            log.warn("工作流完成事件未匹配到挂起的 Agent: executionId={}", event.executionId());
        }
    }

    /** 用户确认 → 恢复等待用户确认的 Agent。 */
    @EventListener
    public void onUserConfirmation(UserConfirmationEvent event) {
        List<SuspendedAgent> candidates = suspendStore.findByReasonType("UserConfirmation");
        var matched = candidates.stream()
                .filter(sa -> sa.suspendReason() instanceof com.lifepilot.agent.model.SuspendReason.UserConfirmation uc
                        && uc.confirmationId().equals(event.confirmationId()))
                .findFirst();
        if (matched.isPresent()) {
            var payload = new ResumePayload.UserDecision(
                    event.confirmationId(), event.approved(), event.reason());
            agentLoop.resumeFromSuspend(matched.get().traceId(), payload);
        } else {
            log.warn("用户确认事件未匹配到挂起的 Agent: confirmationId={}", event.confirmationId());
        }
    }

    /** A2A 远程任务完成 → 恢复等待远程 Agent 的 Agent。 */
    @EventListener
    public void onA2aTaskCompleted(A2aTaskCompletedEvent event) {
        List<SuspendedAgent> candidates = suspendStore.findByReasonType("RemoteDelegation");
        var matched = candidates.stream()
                .filter(sa -> sa.suspendReason() instanceof com.lifepilot.agent.model.SuspendReason.RemoteDelegation rd
                        && rd.remoteTaskId().equals(event.remoteTaskId()))
                .findFirst();
        if (matched.isPresent()) {
            var payload = new ResumePayload.RemoteResult(event.remoteTaskId(), event.resultJson());
            agentLoop.resumeFromSuspend(matched.get().traceId(), payload);
        } else {
            log.warn("A2A 任务完成事件未匹配到挂起的 Agent: remoteTaskId={}", event.remoteTaskId());
        }
    }

    /** 定时唤醒 → 恢复指定 traceId 的 Agent。 */
    @EventListener
    public void onScheduledWakeup(ScheduledWakeupEvent event) {
        var suspended = suspendStore.load(event.traceId());
        if (suspended.isPresent()) {
            var payload = new ResumePayload.WakeupSignal(event.actualWakeupAt());
            agentLoop.resumeFromSuspend(event.traceId(), payload);
        } else {
            log.warn("定时唤醒事件未匹配到挂起的 Agent: traceId={}", event.traceId());
        }
    }

    /** 外部数据就绪 → 恢复等待外部数据的 Agent。 */
    @EventListener
    public void onExternalDataReady(ExternalDataReadyEvent event) {
        List<SuspendedAgent> candidates = suspendStore.findByReasonType("ExternalDataWait");
        var matched = candidates.stream()
                .filter(sa -> sa.suspendReason() instanceof com.lifepilot.agent.model.SuspendReason.ExternalDataWait edw
                        && edw.dataSourceId().equals(event.dataSourceId()))
                .findFirst();
        if (matched.isPresent()) {
            var payload = new ResumePayload.DataReady(
                    event.dataSourceId(), event.dataLocationOrContent());
            agentLoop.resumeFromSuspend(matched.get().traceId(), payload);
        } else {
            log.warn("外部数据就绪事件未匹配到挂起的 Agent: dataSourceId={}", event.dataSourceId());
        }
    }
}
