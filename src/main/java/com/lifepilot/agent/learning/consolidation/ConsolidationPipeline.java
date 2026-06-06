package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateApplier;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.agent.learning.consolidation.association.AssociationConsolidator;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 巩固管线编排服务 — 顺序执行 7 个巩固阶段，故障隔离。
 *
 * <p>本类不再自带定时调度。阶段触发由 {@link ConsolidationScheduler} 按
 * EVENT / CRON / IDLE 分流驱动。{@link #consolidate(boolean)} 仅供
 * 「管理端全量手动巩固」与调度器全量入口复用。</p>
 *
 * <p>执行顺序：语义巩固 → 程序巩固 → 偏好同步 → 经验合并 → 用户画像 → 经验提升 → REM 联想。
 * 单阶段异常不阻塞其他阶段，通过 try-catch 隔离。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ConsolidationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationPipeline.class);

    private final EpisodicToSemanticConsolidator semanticConsolidator;
    @Nullable
    private final EpisodicToProceduralConsolidator proceduralConsolidator;
    private final AgentLearningProperties properties;
    @Nullable
    private final PreferenceConsolidator preferenceConsolidator;
    @Nullable
    private final ExperienceMerger experienceMerger;
    @Nullable
    private final UserProfileConsolidator userProfileConsolidator;
    @Nullable
    private final ExperiencePromoter experiencePromoter;
    @Nullable
    private final AssociationCandidateGenerator remGenerator;
    @Nullable
    private final AssociationConsolidator remConsolidator;
    @Nullable
    private final AssociationCandidateApplier remApplier;

    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  @Nullable EpisodicToProceduralConsolidator proceduralConsolidator,
                                  AgentLearningProperties properties,
                                  @Nullable PreferenceConsolidator preferenceConsolidator,
                                  @Nullable ExperienceMerger experienceMerger,
                                  @Nullable UserProfileConsolidator userProfileConsolidator,
                                  @Nullable ExperiencePromoter experiencePromoter,
                                  @Nullable AssociationCandidateGenerator remGenerator,
                                  @Nullable AssociationConsolidator remConsolidator,
                                  @Nullable AssociationCandidateApplier remApplier) {
        this.semanticConsolidator = semanticConsolidator;
        this.proceduralConsolidator = proceduralConsolidator;
        this.properties = properties;
        this.preferenceConsolidator = preferenceConsolidator;
        this.experienceMerger = experienceMerger;
        this.userProfileConsolidator = userProfileConsolidator;
        this.experiencePromoter = experiencePromoter;
        this.remGenerator = remGenerator;
        this.remConsolidator = remConsolidator;
        this.remApplier = remApplier;
        log.info("ConsolidationPipeline 初始化完成, 经验合并={}, 画像巩固={}, 经验提升={}, REM 联想={}",
                this.experienceMerger != null ? "启用" : "禁用",
                this.userProfileConsolidator != null ? "启用" : "禁用",
                this.experiencePromoter != null ? "启用" : "禁用",
                (this.remGenerator != null && this.remConsolidator != null) ? "启用" : "禁用");
    }

    /**
     * 执行全量巩固管线（7 阶段顺序）。
     *
     * <p>供管理端手动触发与 {@link ConsolidationScheduler#consolidateAll(boolean)} 复用。
     * 阶段化的独立触发请使用 {@link ConsolidationScheduler} 的对应方法。</p>
     */
    public void consolidate() {
        consolidate(false);
    }

    /**
     * 执行全量巩固管线。
     *
     * @param manualTrigger 是否来自用户手动触发；手动触发会让用户画像巩固绕过最小间隔防抖
     */
    public void consolidate(boolean manualTrigger) {
        log.info("巩固管线: 开始执行（全量）");
        runSemanticConsolidation();
        runProceduralConsolidation();
        runPreferenceSync();
        runExperienceMerge();
        runUserProfileConsolidation(manualTrigger);
        runExperiencePromotion();
        runRemAssociation();
        log.info("巩固管线: 执行完成");
    }

    // ── 各阶段独立方法（供 ConsolidationScheduler 分阶段调用）──

    /** 阶段 1：语义巩固（情景→语义）。 */
    public void runSemanticConsolidation() {
        try {
            var stats = semanticConsolidator.consolidate();
            log.info("巩固管线: 语义巩固完成, conversations={}, boosted={}",
                    stats.conversationsAnalyzed(), stats.entitiesBoosted());
        } catch (Exception e) {
            log.warn("巩固管线: 语义巩固失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 2：程序巩固(情景→程序)。 */
    public void runProceduralConsolidation() {
        if (proceduralConsolidator == null) {
            log.debug("巩固管线: 程序巩固器不可用，已跳过");
            return;
        }
        try {
            var stats = proceduralConsolidator.consolidate();
            log.info("巩固管线: 程序巩固完成, traces={}, created={}",
                    stats.conversationsAnalyzed(), stats.templatesCreated());
        } catch (Exception e) {
            log.warn("巩固管线: 程序巩固失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 3：偏好同步（L3 PREFERENCE → L4 PreferenceRule）。 */
    public void runPreferenceSync() {
        if (preferenceConsolidator == null) return;
        try {
            var stats = preferenceConsolidator.consolidate();
            log.info("巩固管线: 偏好同步完成, created={}, reinforced={}, deleted={}",
                    stats.created(), stats.reinforced(), stats.deleted());
        } catch (Exception e) {
            log.warn("巩固管线: 偏好同步失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 4：经验合并（相似经验 → 元经验）。 */
    public void runExperienceMerge() {
        if (experienceMerger == null) return;
        try {
            var stats = experienceMerger.merge();
            log.info("巩固管线: 经验合并完成, candidates={}, merged={}, skipped={}",
                    stats.candidatesFound(), stats.merged(), stats.skipped());
        } catch (Exception e) {
            log.warn("巩固管线: 经验合并失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 5：用户画像巩固。 */
    public void runUserProfileConsolidation(boolean manualTrigger) {
        if (userProfileConsolidator == null) return;
        try {
            userProfileConsolidator.consolidate(manualTrigger);
            log.info("巩固管线: 用户画像巩固完成");
        } catch (Exception e) {
            log.warn("巩固管线: 用户画像巩固失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 6：高频经验提升为 L4 ProcedureTemplate。 */
    public void runExperiencePromotion() {
        if (experiencePromoter == null) return;
        try {
            experiencePromoter.promote();
        } catch (Exception e) {
            log.warn("巩固管线: 经验提升失败, error={}", e.getMessage(), e);
        }
    }

    /** 阶段 7：REM 式联想（memory-rem-consolidation spec）。 */
    public void runRemAssociation() {
        if (remGenerator == null || remConsolidator == null || !properties.getRem().isEnabled()) {
            return;
        }
        try {
            var candidates = remGenerator.generate();
            int saved = remConsolidator.consolidate(candidates);
            if (!candidates.isEmpty() || saved > 0) {
                log.info("巩固管线: REM 联想完成, candidates={}, saved={}", candidates.size(), saved);
            }
            // 阶段 7.1：将文件候选应用到 memory_relations 主库（受 apply-enabled 门控）
            if (remApplier != null && properties.getRem().isApplyEnabled()) {
                var applyResult = remApplier.apply(java.time.LocalDate.now());
                if (applyResult.applied() > 0) {
                    log.info("巩固管线: REM 候选落库, {}", applyResult);
                }
            }
        } catch (Exception e) {
            log.warn("巩固管线: REM 联想失败, error={}", e.getMessage(), e);
        }
    }
}
