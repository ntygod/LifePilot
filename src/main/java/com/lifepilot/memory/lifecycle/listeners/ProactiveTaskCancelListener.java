package com.lifepilot.memory.lifecycle.listeners;

import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.ProactiveTaskCancelled;
import com.lifepilot.memory.semantic.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 主动任务取消时，级联将关联的 L3 insight 实体从 {@code ACTIVE} 转 {@code CANCELLED}。
 *
 * <p>事件由 {@code ProactiveMemoryBridge} 在 {@code markGoalFulfilled / cancel} 时发布，
 * 承载 {@code relatedInsightEntityIds} —— 这批 insight 本身只在任务上下文内有意义，
 * 任务终结后应避免被后续对话检索再召回并误导 LLM 推理。</p>
 *
 * <p>转换规则：
 * <ul>
 *   <li>只处理 {@code ACTIVE} 实体，其他态（含已 {@code CANCELLED}）幂等跳过</li>
 *   <li>实体不存在（如已被其他流程物理清理）静默跳过，不抛异常</li>
 *   <li>空 {@code relatedInsightEntityIds} 直接返回 —— ProactiveTaskCancelled record
 *       构造器保证入参非 null</li>
 * </ul>
 *
 * <p>不自己发 {@link com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged} —— 通过调
 * {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 * 让 SemanticMemory 在事务提交后代发（source={@link ChangeSource#PROACTIVE_CANCEL}），
 * 避免与 SemanticMemory 代发机制双发事件。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class ProactiveTaskCancelListener {

    private static final Logger log = LoggerFactory.getLogger(ProactiveTaskCancelListener.class);
    private static final String REASON_PREFIX = "proactive-task-cancelled:";

    private final SemanticMemory semanticMemory;

    public ProactiveTaskCancelListener(SemanticMemory semanticMemory) {
        this.semanticMemory = semanticMemory;
    }

    /**
     * 处理主动任务取消事件。
     *
     * @param event 主动任务取消事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(ProactiveTaskCancelled event) {
        if (event.relatedInsightEntityIds().isEmpty()) {
            log.debug("主动任务取消: 无关联 insight, taskId={}", event.taskId());
            return;
        }
        String reason = REASON_PREFIX + event.taskId();
        int cancelledCount = 0;
        for (String id : event.relatedInsightEntityIds()) {
            var existingOpt = semanticMemory.findById(id);
            if (existingOpt.isEmpty()) {
                log.debug("主动任务取消: insight 不存在, taskId={}, insight={}", event.taskId(), id);
                continue;
            }
            if (existingOpt.get().lifecycleState() != LifecycleState.ACTIVE) {
                log.debug("主动任务取消: insight 非 ACTIVE 跳过, taskId={}, insight={}, state={}",
                        event.taskId(), id, existingOpt.get().lifecycleState());
                continue;
            }
            try {
                semanticMemory.updateLifecycleState(
                        id, LifecycleState.CANCELLED, reason, ChangeSource.PROACTIVE_CANCEL);
                cancelledCount++;
            } catch (Exception ex) {
                log.warn("主动任务取消: 级联转 CANCELLED 失败, taskId={}, insight={}, error={}",
                        event.taskId(), id, ex.getMessage(), ex);
            }
        }
        log.info("主动任务取消级联完成: taskId={}, 总数={}, 实际转换={}",
                event.taskId(), event.relatedInsightEntityIds().size(), cancelledCount);
    }
}
