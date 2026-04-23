package com.lifepilot.memory.lifecycle.listeners;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Set;

/**
 * 实体转非活状态（非 {@link LifecycleState#ACTIVE} / {@link LifecycleState#COMPLETED}）时清理向量。
 *
 * <p>保留 COMPLETED 的原因：用户仍可能检索"我已完成的目标"一类历史召回，留住向量维持可检索性；
 * 而 CANCELLED / EXPIRED / SUPERSEDED / ARCHIVED / REGENERATION_NEEDED 的内容应避免继续污染
 * 向量语义检索结果。</p>
 *
 * <p>失败处理：如 {@link VectorSearcher#deleteEntityVector} 抛异常，仅记录 WARN 不阻断流程 ——
 * 检索层有 {@code lifecycle_state} 字段过滤作为兜底，即便向量残留也不会被召回。</p>
 *
 * <p>幂等性：重复投递同一事件不会产生副作用 —— SQLite 的 DELETE 对不存在的行是 no-op。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
public class VectorListener {

    private static final Logger log = LoggerFactory.getLogger(VectorListener.class);

    /** 保留向量的状态集合 —— 其余状态一律清理向量。 */
    private static final Set<LifecycleState> KEEP_VECTOR = EnumSet.of(
            LifecycleState.ACTIVE,
            LifecycleState.COMPLETED);

    private final VectorSearcher vectorSearcher;

    public VectorListener(VectorSearcher vectorSearcher) {
        this.vectorSearcher = vectorSearcher;
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
        try {
            vectorSearcher.deleteEntityVector(event.entityId());
        } catch (Exception ex) {
            // 失败记 warn；检索层用 lifecycle_state 过滤兜底
            log.warn("向量清理失败 entity={} newState={}", event.entityId(), event.newState(), ex);
        }
    }
}
