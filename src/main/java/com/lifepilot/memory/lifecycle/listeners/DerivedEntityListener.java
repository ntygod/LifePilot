package com.lifepilot.memory.lifecycle.listeners;

import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 派生实体源失效级联监听器 —— 源实体失效时，把依赖它的派生实体打
 * {@link LifecycleState#REGENERATION_NEEDED} 并入 {@code derivation_regeneration_queue}。
 *
 * <p>触发规则：
 * <ul>
 *   <li>仅 {@code newState ∈ {SUPERSEDED, CANCELLED, EXPIRED}} 才级联 —— {@code ARCHIVED}
 *       是归档终态，{@code REGENERATION_NEEDED} 是派生中间态，均不再二次触发</li>
 *   <li>入口先过滤 {@code source == DERIVATION_TRIGGER}：本监听器自身调用
 *       {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 *       会再次产生 {@link EntityLifecycleChanged}，必须避免递归环</li>
 *   <li>{@code __consolidated_profile}（画像）走"源失效比例 ≥ 20%" 阈值，避免单个
 *       源偏好失效就全量重算画像；其他派生实体（CONTRASTIVE_INSIGHT / MERGED 等）一律触发</li>
 * </ul>
 *
 * <p>不自己发 {@link EntityLifecycleChanged} —— 所有状态转换通过
 * {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 * 完成，事件由 SemanticMemory 在事务提交后代发（{@code source=DERIVATION_TRIGGER}），
 * 保证回滚安全且下游 VectorListener / L4SyncListener 不会双发。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
public class DerivedEntityListener {

    private static final Logger log = LoggerFactory.getLogger(DerivedEntityListener.class);

    /** 触发级联的源失效状态集合。 */
    private static final Set<LifecycleState> TRIGGER_STATES = EnumSet.of(
            LifecycleState.SUPERSEDED,
            LifecycleState.CANCELLED,
            LifecycleState.EXPIRED);

    /** 画像实体的 {@code name} 值 —— 与 {@code UserProfileConsolidator#PROFILE_ENTITY_NAME} 对齐。 */
    private static final String PROFILE_ENTITY_NAME = "__consolidated_profile";

    /** 画像重算阈值：源失效比例达到 20% 才触发重算。 */
    private static final double PROFILE_TRIGGER_RATIO = 0.20;

    private static final String REASON_PREFIX = "source-invalidated:";

    private final SemanticMemory semanticMemory;
    private final RegenerationQueueRepository queueRepo;
    private final Clock clock;

    public DerivedEntityListener(SemanticMemory semanticMemory,
                                 RegenerationQueueRepository queueRepo,
                                 Clock clock) {
        this.semanticMemory = semanticMemory;
        this.queueRepo = queueRepo;
        this.clock = clock;
    }

    /**
     * 处理实体生命周期变化事件。
     *
     * @param event 生命周期变化事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChanged(EntityLifecycleChanged event) {
        // ① 过滤非触发态（ARCHIVED / REGENERATION_NEEDED / ACTIVE↔COMPLETED 等）
        if (!TRIGGER_STATES.contains(event.newState())) {
            return;
        }
        // ② 防递归：本监听器自身转 REGENERATION_NEEDED 也会再进来，必须短路
        if (event.source() == ChangeSource.DERIVATION_TRIGGER) {
            return;
        }

        List<TemporalEntity> derivedList = semanticMemory.findDerivedBySourceEntity(event.entityId());
        if (derivedList.isEmpty()) {
            return;
        }

        Instant when = clock.instant();
        int triggered = 0;
        for (TemporalEntity derived : derivedList) {
            // 画像类走阈值判定；其他派生类型一律触发
            if (PROFILE_ENTITY_NAME.equals(derived.name()) && !shouldRegenerateProfile(derived)) {
                log.debug("派生实体级联: 画像源失效比例未达阈值, derived={}, source={}",
                        derived.id(), event.entityId());
                continue;
            }
            try {
                markRegenerationNeeded(derived.id(), event.entityId(), when);
                triggered++;
            } catch (Exception ex) {
                log.warn("派生实体级联失败: derived={}, source={}, error={}",
                        derived.id(), event.entityId(), ex.getMessage(), ex);
            }
        }
        log.info("派生实体级联完成: source={}, 候选={}, 实际触发={}",
                event.entityId(), derivedList.size(), triggered);
    }

    /**
     * 判定画像实体是否达到重算阈值：计算 {@code derivation_sources} 中已失效
     * （{@link LifecycleState#isRetrievable()} 为 false）的比例，达 {@link #PROFILE_TRIGGER_RATIO}
     * 才返回 {@code true}。
     *
     * <p>源实体物理缺失（{@code findById} 返回空）也计入失效 —— 源被硬删除语义上等同于
     * 失去支持该画像的证据。</p>
     *
     * @param profile 画像实体
     * @return 是否应当重算
     */
    private boolean shouldRegenerateProfile(TemporalEntity profile) {
        List<String> sources = profile.derivationSources();
        if (sources == null || sources.isEmpty()) {
            // 没有源的画像无法验证覆盖比例，保守触发一次让 regenerator 决定
            return true;
        }
        long invalid = sources.stream()
                .filter(id -> semanticMemory.findById(id)
                        .map(src -> !src.lifecycleState().isRetrievable())
                        .orElse(true))
                .count();
        double ratio = (double) invalid / sources.size();
        return ratio >= PROFILE_TRIGGER_RATIO;
    }

    /**
     * 将派生实体转 {@code REGENERATION_NEEDED} 并入队。
     *
     * <p>调用 {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
     * 让 SemanticMemory 事务提交后代发 {@link EntityLifecycleChanged}，因此本监听器不自发事件。
     * {@code source=DERIVATION_TRIGGER} 会被自身的 {@link #onLifecycleChanged} 入口短路，
     * 避免递归环。</p>
     *
     * @param derivedId  派生实体 ID
     * @param triggerId  触发本次级联的源实体 ID
     * @param when       入队时间
     */
    private void markRegenerationNeeded(String derivedId, String triggerId, Instant when) {
        semanticMemory.updateLifecycleState(
                derivedId,
                LifecycleState.REGENERATION_NEEDED,
                REASON_PREFIX + triggerId,
                ChangeSource.DERIVATION_TRIGGER);
        queueRepo.enqueue(derivedId, triggerId, when);
    }
}
