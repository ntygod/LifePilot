package com.lifepilot.memory.lifecycle.listeners;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.projection.MemoryProjectionService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Set;

/**
 * 实体转非活状态（非 {@link LifecycleState#ACTIVE} / {@link LifecycleState#COMPLETED}）时登记向量清理投影任务。
 *
 * <p>保留 COMPLETED 的原因：用户仍可能检索"我已完成的目标"一类历史召回，留住向量维持可检索性；
 * 而 CANCELLED / EXPIRED / SUPERSEDED / ARCHIVED / REGENERATION_NEEDED 的内容应避免继续污染
 * 向量语义检索结果。</p>
 *
 * <p>所有清理都进入 {@code memory_projection_outbox}，由投影 processor 幂等删除向量。
 * 不再保留 {@code VectorSearcher} 直删 fallback，投影服务缺失应在装配阶段失败。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class VectorListener {

    /** 保留向量的状态集合 —— 其余状态一律清理向量。 */
    private static final Set<LifecycleState> KEEP_VECTOR = EnumSet.of(
            LifecycleState.ACTIVE,
            LifecycleState.COMPLETED);

    private final MemoryProjectionService projectionService;

    public VectorListener(MemoryProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    /**
     * 处理实体生命周期变化事件。
     *
     * @param event 生命周期变化事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChanged(EntityLifecycleChanged event) {
        if (KEEP_VECTOR.contains(event.newState())) {
            return;
        }
        projectionService.enqueueVectorDeleteAfterCommit(event.entityId());
    }
}
