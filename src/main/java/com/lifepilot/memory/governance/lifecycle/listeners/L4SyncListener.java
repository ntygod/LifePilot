package com.lifepilot.memory.governance.lifecycle.listeners;

import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.store.procedural.PreferenceRuleRepository;
import com.lifepilot.memory.store.procedural.ProceduralMemoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * L3 实体失活时，同步使对应 L4 {@code preference_rules} / {@code procedure_templates} 失活。
 *
 * <p>监听 {@link EntityLifecycleChanged}，在事务提交后生效。仅处理 newState ∈
 * { {@link LifecycleState#CANCELLED}, {@link LifecycleState#EXPIRED},
 *   {@link LifecycleState#SUPERSEDED}, {@link LifecycleState#ARCHIVED},
 *   {@link LifecycleState#REGENERATION_NEEDED} } 的事件。</p>
 *
 * <p>对 {@code source_entity_id} 为空或已失活的记录，由 Repository SQL 的
 * {@code deactivated_reason IS NULL} 条件兜底，实际表现为 no-op。</p>
 *
 * <p>幂等性：同一事件重放多次最终状态一致 —— 首次命中后规则已带 {@code deactivated_reason}，
 * 后续 UPDATE 不再匹配 {@code IS NULL} 过滤。</p>
 *
 * <p>底层 Repository 失败会直接抛出，避免 L3 已失活但 L4 仍继续生效。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class L4SyncListener {

    /** 触发 L4 失活的 L3 状态集合。 */
    private static final Set<LifecycleState> INACTIVATING = EnumSet.of(
            LifecycleState.CANCELLED,
            LifecycleState.EXPIRED,
            LifecycleState.SUPERSEDED,
            LifecycleState.ARCHIVED,
            LifecycleState.REGENERATION_NEEDED);

    private final PreferenceRuleRepository ruleRepo;
    private final ProceduralMemoryRepository procedureRepo;

    public L4SyncListener(PreferenceRuleRepository ruleRepo,
                          ProceduralMemoryRepository procedureRepo) {
        this.ruleRepo = Objects.requireNonNull(ruleRepo, "ruleRepo 不能为空");
        this.procedureRepo = Objects.requireNonNull(procedureRepo, "procedureRepo 不能为空");
    }

    /**
     * 处理实体生命周期变化事件。
     *
     * @param event 生命周期变化事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChanged(EntityLifecycleChanged event) {
        if (!INACTIVATING.contains(event.newState())) {
            return;
        }
        String reason = event.reason() == null ? event.newState().name() : event.reason();
        ruleRepo.deactivateBySourceEntity(event.entityId(), reason);
        procedureRepo.deactivateBySourceEntity(event.entityId(), reason);
    }
}
