package com.lifepilot.memory.lifecycle.scanner;

import com.lifepilot.memory.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository.QueueItem;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 派生实体重算扫描器 —— 每 2 小时消费 {@code derivation_regeneration_queue}，把源失效
 * 级联标为 {@link LifecycleState#REGENERATION_NEEDED} 的派生实体按类型重新生成或直接
 * 转入 {@link LifecycleState#SUPERSEDED}。
 *
 * <h3>按派生实体类型的分派规则</h3>
 * <ul>
 *   <li><b>画像</b>（{@code name == "__consolidated_profile"}）：调
 *       {@link UserProfileConsolidator#consolidate()} 全量重算。该方法内部自带 2 小时防抖
 *       + 状态转写；若本 Cron 周期内触发不到，则由收尾逻辑补 SUPERSEDED 防止死锁。</li>
 *   <li><b>其他派生</b>（{@code isDerived == true} 且非画像，包括
 *       CONTRASTIVE_INSIGHT / MERGED 等）：
 *       <ul>
 *         <li>{@code ContrastiveLearner} 当前 API 是"原地增强源 EXPERIENCE"，没有对称的
 *             {@code learnFromSources(e1, e2)} 双源重算入口 —— 无法直接重算独立的
 *             contrastive_insight 实体；</li>
 *         <li>{@code EntityDeduplicator} 的 {@code dedup()} 是全局扫描 + 合并流程，也没有
 *             针对单个 MERGED 实体的 {@code regenerate(entity)} API；</li>
 *       </ul>
 *       两类 API 都未提供时，降级策略统一为：不尝试重算，直接把派生实体转
 *       {@code SUPERSEDED}（由 {@link #finalizeToSupersededIfStillPending} 收尾），
 *       避免长期停留在 {@code REGENERATION_NEEDED} 污染检索召回。</li>
 * </ul>
 *
 * <p><b>待补充</b>：如后续 {@code ContrastiveLearner}/{@code EntityDeduplicator} 提供
 * 精准的重算入口（形如 {@code learnFromSources(e1, e2)} 或 {@code regenerate(entity)}），
 * 在此类中补对应分支并保留 SUPERSEDED 兜底。</p>
 *
 * <h3>状态与事件</h3>
 * <p>所有状态转换走
 * {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 * 4 参版 —— 由 SemanticMemory 在事务提交后代发 {@code EntityLifecycleChanged}
 * （{@code source=DERIVATION_TRIGGER}），与 {@code DerivedEntityListener} 的防递归入口对齐，
 * 本扫描器不自发事件。</p>
 *
 * <h3>可靠性</h3>
 * <ul>
 *   <li>单条失败 {@code log.warn} + {@link RegenerationQueueRepository#markFailed} 并继续</li>
 *   <li>派生实体已不存在 / 已非 {@code REGENERATION_NEEDED} 直接 {@link RegenerationQueueRepository#markDone}</li>
 *   <li>{@link #processQueueNow()} 暴露给测试 / 手动触发</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
public class DerivationRegenerator {

    private static final Logger log = LoggerFactory.getLogger(DerivationRegenerator.class);

    /** 每批处理上限 —— 与 SQLite 单写入者吞吐匹配，避免阻塞其他写入。 */
    private static final int BATCH_SIZE = 50;

    /**
     * 画像实体的 {@code name} 值 —— 与 {@code UserProfileConsolidator#PROFILE_ENTITY_NAME}
     * / {@code DerivedEntityListener#PROFILE_ENTITY_NAME} 对齐。
     */
    static final String PROFILE_ENTITY_NAME = "__consolidated_profile";

    /** 降级兜底的收尾原因 —— 写入 {@code memory_entities.lifecycle_reason}。 */
    private static final String REASON_REGENERATED = "regenerated";
    private static final String REASON_NO_API = "no-regenerate-api";

    private final RegenerationQueueRepository queueRepository;
    private final SemanticMemory semanticMemory;
    @Nullable
    private final UserProfileConsolidator profileConsolidator;

    public DerivationRegenerator(RegenerationQueueRepository queueRepository,
                                 SemanticMemory semanticMemory,
                                 @Nullable UserProfileConsolidator profileConsolidator) {
        this.queueRepository = queueRepository;
        this.semanticMemory = semanticMemory;
        this.profileConsolidator = profileConsolidator;
    }

    /**
     * 定时入口 —— 每 2 小时的整点触发。
     *
     * <p>cron 表达式：秒 0、分 0、时每 2 小时（0,2,4,...,22）、日 * 月 * 周 *。</p>
     */
    @Scheduled(cron = "0 0 */2 * * *")
    public void process() {
        processQueueNow();
    }

    /** 测试友好入口 —— 与 {@link #process()} 共用逻辑。 */
    public void processQueueNow() {
        var pending = queueRepository.findPending(BATCH_SIZE);
        if (pending.isEmpty()) {
            log.debug("DerivationRegenerator 无 PENDING 项，跳过");
            return;
        }
        log.info("DerivationRegenerator 拉取 {} 条 PENDING 重算项", pending.size());

        int done = 0;
        int failed = 0;
        for (var item : pending) {
            try {
                regenerateOne(item);
                queueRepository.markDone(item.id());
                done++;
            } catch (Exception ex) {
                // 单条失败不中断整批
                log.warn("DerivationRegenerator 重算失败, queueId={}, derivedId={}, error={}",
                        item.id(), item.derivedEntityId(), ex.getMessage(), ex);
                queueRepository.markFailed(item.id(), ex.getMessage());
                failed++;
            }
        }
        log.info("DerivationRegenerator 完成: done={}, failed={}, total={}",
                done, failed, pending.size());
    }

    /**
     * 单条队列项处理 —— 回捞派生实体 → 按类型分派重算 → 收尾补 SUPERSEDED。
     *
     * @param item 队列项
     */
    private void regenerateOne(QueueItem item) {
        var derivedOpt = semanticMemory.findById(item.derivedEntityId());
        if (derivedOpt.isEmpty()) {
            // 派生实体已被硬删除，无需重算；markDone 即可
            log.debug("DerivationRegenerator 派生实体已不存在，跳过, queueId={}, derivedId={}",
                    item.id(), item.derivedEntityId());
            return;
        }
        var derived = derivedOpt.get();
        if (derived.lifecycleState() != LifecycleState.REGENERATION_NEEDED) {
            // 其他路径（如 UI 手工改状态 / ARCHIVED）已处理过，跳过
            log.debug("DerivationRegenerator 派生实体已非 REGENERATION_NEEDED，跳过, derivedId={}, state={}",
                    derived.id(), derived.lifecycleState());
            return;
        }

        if (PROFILE_ENTITY_NAME.equals(derived.name())) {
            regenerateProfile(derived);
        } else if (derived.isDerived()) {
            // ContrastiveLearner / EntityDeduplicator 无精准重算 API，降级为直接 SUPERSEDED
            log.debug("DerivationRegenerator 非画像派生实体降级为直接 SUPERSEDED, derivedId={}, name={}",
                    derived.id(), derived.name());
        } else {
            // 不该发生：非派生实体却进了队列；兜底仍走收尾 SUPERSEDED 避免死锁
            log.warn("DerivationRegenerator 非派生实体进入队列，按降级处理, derivedId={}", derived.id());
        }

        // 统一收尾：若上面分支未把派生实体转出 REGENERATION_NEEDED，补 SUPERSEDED
        finalizeToSupersededIfStillPending(derived.id());
    }

    /**
     * 画像重算 —— 调 {@link UserProfileConsolidator#consolidate()} 全量重算。
     *
     * <p>{@code consolidate} 内部 2 小时防抖会决定是否真正发 LLM 调用；若未真正重算，
     * 派生实体仍处于 {@code REGENERATION_NEEDED}，收尾逻辑会转 {@code SUPERSEDED}
     * 避免污染检索。</p>
     *
     * @param profile 画像实体
     */
    private void regenerateProfile(TemporalEntity profile) {
        if (profileConsolidator == null) {
            log.debug("DerivationRegenerator UserProfileConsolidator 未装配，画像走降级 SUPERSEDED, derivedId={}",
                    profile.id());
            return;
        }
        log.debug("DerivationRegenerator 触发画像重算, derivedId={}", profile.id());
        profileConsolidator.consolidate();
    }

    /**
     * 收尾逻辑 —— 若派生实体仍处于 {@link LifecycleState#REGENERATION_NEEDED}，
     * 通过 {@link SemanticMemory#updateLifecycleState} 转入 {@link LifecycleState#SUPERSEDED}
     * （{@code source=DERIVATION_TRIGGER}），由 SemanticMemory 代发事件，与
     * {@code DerivedEntityListener} 的入口防递归对齐。
     *
     * @param derivedId 派生实体 ID
     */
    private void finalizeToSupersededIfStillPending(String derivedId) {
        var reloadOpt = semanticMemory.findById(derivedId);
        if (reloadOpt.isEmpty()) {
            return;
        }
        var current = reloadOpt.get();
        if (current.lifecycleState() != LifecycleState.REGENERATION_NEEDED) {
            return;
        }
        // 画像类给"regenerated"原因，其他派生类给"no-regenerate-api"以区分日志
        String reason = PROFILE_ENTITY_NAME.equals(current.name())
                ? REASON_REGENERATED
                : REASON_NO_API;
        semanticMemory.updateLifecycleState(
                derivedId,
                LifecycleState.SUPERSEDED,
                reason,
                ChangeSource.DERIVATION_TRIGGER);
    }
}
