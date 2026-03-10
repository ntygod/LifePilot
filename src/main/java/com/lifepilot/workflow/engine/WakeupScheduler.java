package com.lifepilot.workflow.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.workflow.model.WorkflowEventType;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.repository.WorkflowRepository;

/**
 * 工作流唤醒调度器 — 定期扫描到期的 WAITING/PAUSED 实例并触发恢复。
 *
 * <p>由 TaskScheduler 按 {@code lifepilot.workflow.wakeup.scan-interval-seconds}
 * 配置的间隔定期调度 {@link #scan()} 方法。扫描逻辑：
 * <ul>
 *   <li>WAITING 且 wakeUpAt 到期 → 提交异步恢复执行</li>
 *   <li>PAUSED 且 wakeUpAt 到期 → 根据 blockedReason 中的 autoApprove 标志决定自动批准或标记失败</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WakeupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WakeupScheduler.class);

    /** 解析 blockedReason 中 autoApprove 标志的正则。 */
    private static final Pattern AUTO_APPROVE_PATTERN =
            Pattern.compile("autoApprove=(true|false)");

    private final WorkflowRepository repository;
    private final WorkflowRunner runner;
    private final WorkflowEventRecorder eventRecorder;

    public WakeupScheduler(WorkflowRepository repository,
                           WorkflowRunner runner,
                           WorkflowEventRecorder eventRecorder) {
        this.repository = repository;
        this.runner = runner;
        this.eventRecorder = eventRecorder;
    }

    /**
     * 定时扫描任务（由 TaskScheduler 调度）。
     *
     * <p>扫描 WAITING 且 wakeUpAt 到期的实例 → 提交异步恢复。
     * 扫描 PAUSED 且审批超时的实例 → 根据 autoApproveOnTimeout 处理。
     */
    public void scan() {
        Instant now = Instant.now();

        // 扫描到期的 WAITING 实例
        List<WorkflowInstance> expiredWaiting = repository.findExpiredWaitingInstances(now);
        for (WorkflowInstance instance : expiredWaiting) {
            try {
                log.info("唤醒到期 WAITING 实例: instanceId={}, wakeUpAt={}",
                        instance.id(), instance.wakeUpAt());
                runner.submitAsyncResume(instance.id());
            } catch (Exception e) {
                log.error("唤醒 WAITING 实例失败: instanceId={}, error={}",
                        instance.id(), e.getMessage(), e);
            }
        }

        // 扫描超时的 PAUSED 实例
        List<WorkflowInstance> expiredPaused = repository.findExpiredPausedInstances(now);
        for (WorkflowInstance instance : expiredPaused) {
            try {
                handleApprovalTimeout(instance);
            } catch (Exception e) {
                log.error("处理审批超时失败: instanceId={}, error={}",
                        instance.id(), e.getMessage(), e);
            }
        }
    }

    /**
     * 处理审批超时：根据 blockedReason 中的 autoApprove 标志决定自动批准或标记失败。
     */
    private void handleApprovalTimeout(WorkflowInstance instance) {
        boolean autoApprove = parseAutoApprove(instance.blockedReason());

        if (autoApprove) {
            log.info("审批超时自动批准: instanceId={}, stepId={}",
                    instance.id(), instance.blockedStepId());

            // 自动批准 → 提交异步恢复
            runner.submitAsyncResume(instance.id());

            eventRecorder.record(WorkflowEventType.APPROVAL_DECIDED, instance.id(),
                    instance.workflowId(), instance.blockedStepId(),
                    Map.of("decision", "AUTO_APPROVED", "reason", "timeout"));
        } else {
            log.info("审批超时标记失败: instanceId={}, stepId={}",
                    instance.id(), instance.blockedStepId());

            // 超时且不自动批准 → 转为 FAILED
            var failed = instance.toBuilder()
                    .state(WorkflowState.FAILED)
                    .failureReason("审批超时: stepId=" + instance.blockedStepId())
                    .completedAt(Instant.now())
                    .wakeUpAt(null)
                    .blockedStepId(null)
                    .blockedReason(null)
                    .updatedAt(Instant.now())
                    .build();

            repository.updateInstance(failed);

            eventRecorder.record(WorkflowEventType.INSTANCE_STATE_CHANGED, instance.id(),
                    instance.workflowId(), instance.blockedStepId(),
                    Map.of("from", WorkflowState.PAUSED.name(),
                           "to", WorkflowState.FAILED.name(),
                           "reason", "approval_timeout"));
        }
    }

    /**
     * 从 blockedReason 中解析 autoApprove 标志。
     *
     * <p>blockedReason 格式示例：{@code "approval:timeout=86400s,autoApprove=true"}
     *
     * @param blockedReason 阻塞原因字符串
     * @return autoApprove 标志，解析失败时默认 false
     */
    static boolean parseAutoApprove(String blockedReason) {
        if (blockedReason == null) {
            return false;
        }
        Matcher matcher = AUTO_APPROVE_PATTERN.matcher(blockedReason);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return false;
    }
}
