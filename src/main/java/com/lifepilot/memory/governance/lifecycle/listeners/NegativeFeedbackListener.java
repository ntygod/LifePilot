package com.lifepilot.memory.governance.lifecycle.listeners;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackLedgerRepository;
import com.lifepilot.memory.governance.lifecycle.feedback.FeedbackThresholdConfig;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.Objects;

/**
 * 负反馈累计监听器：写账本 → 判定阈值 → 对达阈值的 ACTIVE 实体转 {@code SUPERSEDED}。
 *
 * <p>流水线：
 * <ol>
 *   <li>每次 {@link EntityWeightChanged} 一律入账（含正反馈，便于审计 delta 时序）</li>
 *   <li>仅当 {@code delta < 0} 才判定阈值 —— 正反馈不推进 SUPERSEDED</li>
 *   <li>判定规则（或）：累计负向次数 ≥ {@code memory.feedback.negative-threshold-count}，
 *       或本次 {@code cumulativeScore < memory.feedback.negative-threshold-score}</li>
 *   <li>只转 {@code ACTIVE} 实体；已是其他态（含 {@code SUPERSEDED}）的幂等跳过</li>
 * </ol>
 *
 * <p>不自己发 {@link com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged} —— 通过调
 * {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 * 让 SemanticMemory 在事务提交后代发（source={@link ChangeSource#NEGATIVE_FEEDBACK}），
 * 避免双发事件污染下游 VectorListener / L4SyncListener。</p>
 *
 * <p>幂等性：账本追加每次都会新增一行（审计需求不去重），但 SUPERSEDED 转换只对 {@code ACTIVE}
 * 实体生效，同一实体再次触发不会产生重复状态变化事件。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
public class NegativeFeedbackListener {

    private static final Logger log = LoggerFactory.getLogger(NegativeFeedbackListener.class);
    private static final String SUPERSEDED_REASON = "NEGATIVE_FEEDBACK_THRESHOLD";

    private final FeedbackLedgerRepository ledger;
    private final SemanticMemory semanticMemory;
    private final FeedbackThresholdConfig cfg;
    private final Clock clock;

    public NegativeFeedbackListener(FeedbackLedgerRepository ledger,
                                    SemanticMemory semanticMemory,
                                    FeedbackThresholdConfig cfg,
                                    Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.cfg = Objects.requireNonNull(cfg, "cfg 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 处理权重变化事件。
     *
     * @param event 权重变化事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWeightChanged(EntityWeightChanged event) {
        Objects.requireNonNull(event, "权重变化事件不能为空");
        // ① 无差别写账本（正向也写入，用于审计）
        ledger.append(event.entityId(), event.delta(), event.cumulativeScore(),
                event.source(), clock.instant());

        // ② 只对负向 delta 判定阈值
        if (event.delta() >= 0) {
            return;
        }

        // ③ 判定：或关系 —— 计数 / 累计分任一满足即触发
        int negativeCount = ledger.countNegative(event.entityId());
        boolean triggeredByCount = negativeCount >= cfg.getNegativeThresholdCount();
        boolean triggeredByScore = event.cumulativeScore() < cfg.getNegativeThresholdScore();
        if (!triggeredByCount && !triggeredByScore) {
            return;
        }

        // ④ 仅 ACTIVE 实体才转 SUPERSEDED；实体不存在或已非 ACTIVE 的幂等跳过
        var existingOpt = semanticMemory.findById(event.entityId());
        if (existingOpt.isEmpty()) {
            log.debug("负反馈判定: 实体不存在, entity={}", event.entityId());
            return;
        }
        var existing = existingOpt.get();
        if (existing.lifecycleState() != LifecycleState.ACTIVE) {
            log.debug("负反馈判定: 非 ACTIVE 状态跳过, entity={}, state={}",
                    event.entityId(), existing.lifecycleState());
            return;
        }

        // ⑤ 调 SemanticMemory 代发 EntityLifecycleChanged（source=NEGATIVE_FEEDBACK）
        semanticMemory.updateLifecycleState(
                event.entityId(),
                LifecycleState.SUPERSEDED,
                SUPERSEDED_REASON,
                ChangeSource.NEGATIVE_FEEDBACK);
        log.info("负反馈触发 SUPERSEDED: entity={}, negativeCount={}, cumulative={}, byCount={}, byScore={}",
                event.entityId(), negativeCount, event.cumulativeScore(),
                triggeredByCount, triggeredByScore);
    }
}
