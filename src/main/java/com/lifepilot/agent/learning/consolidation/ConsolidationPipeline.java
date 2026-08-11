package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateApplier;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.agent.learning.consolidation.association.AssociationConsolidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 巩固管线编排服务 — 顺序执行 7 个巩固阶段。
 *
 * <p>本类不再自带定时调度。阶段触发由 {@link ConsolidationScheduler} 按
 * EVENT / CRON / IDLE 分流驱动。{@link #consolidate(boolean)} 仅供
 * 「管理端全量手动巩固」与调度器全量入口复用。</p>
 *
 * <p>执行顺序：语义巩固 → 程序巩固 → 偏好同步 → 经验合并 → 用户画像 → 经验提升 → REM 联想。
 * 任一阶段失败直接向调用方暴露，避免跨层记忆不一致被静默掩盖。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ConsolidationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationPipeline.class);

    private final EpisodicToSemanticConsolidator semanticConsolidator;
    private final EpisodicToProceduralConsolidator proceduralConsolidator;
    private final AgentLearningProperties properties;
    private final PreferenceConsolidator preferenceConsolidator;
    private final ExperienceMerger experienceMerger;
    private final UserProfileConsolidator userProfileConsolidator;
    private final ExperiencePromoter experiencePromoter;
    private final AssociationCandidateGenerator remGenerator;
    private final AssociationConsolidator remConsolidator;
    private final AssociationCandidateApplier remApplier;

    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  EpisodicToProceduralConsolidator proceduralConsolidator,
                                  AgentLearningProperties properties,
                                  PreferenceConsolidator preferenceConsolidator,
                                  ExperienceMerger experienceMerger,
                                  UserProfileConsolidator userProfileConsolidator,
                                  ExperiencePromoter experiencePromoter,
                                  AssociationCandidateGenerator remGenerator,
                                  AssociationConsolidator remConsolidator,
                                  AssociationCandidateApplier remApplier) {
        this.semanticConsolidator = Objects.requireNonNull(semanticConsolidator, "semanticConsolidator 不能为空");
        this.proceduralConsolidator = Objects.requireNonNull(proceduralConsolidator, "proceduralConsolidator 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.preferenceConsolidator = Objects.requireNonNull(preferenceConsolidator, "preferenceConsolidator 不能为空");
        this.experienceMerger = Objects.requireNonNull(experienceMerger, "experienceMerger 不能为空");
        this.userProfileConsolidator = Objects.requireNonNull(userProfileConsolidator, "userProfileConsolidator 不能为空");
        this.experiencePromoter = Objects.requireNonNull(experiencePromoter, "experiencePromoter 不能为空");
        this.remGenerator = Objects.requireNonNull(remGenerator, "remGenerator 不能为空");
        this.remConsolidator = Objects.requireNonNull(remConsolidator, "remConsolidator 不能为空");
        this.remApplier = Objects.requireNonNull(remApplier, "remApplier 不能为空");
        log.info("ConsolidationPipeline 初始化完成, 经验合并={}, 画像巩固={}, 经验提升={}, REM 联想={}",
                "启用",
                "启用",
                "启用",
                this.properties.getRem().isEnabled() ? "启用" : "禁用");
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
        var stats = semanticConsolidator.consolidate();
        log.info("巩固管线: 语义巩固完成, conversations={}, boosted={}",
                stats.conversationsAnalyzed(), stats.entitiesBoosted());
    }

    /** 阶段 2：程序巩固(情景→程序)。 */
    public void runProceduralConsolidation() {
        var stats = proceduralConsolidator.consolidate();
        log.info("巩固管线: 程序巩固完成, traces={}, created={}",
                stats.conversationsAnalyzed(), stats.templatesCreated());
    }

    /** 阶段 3：偏好同步（L3 PREFERENCE → L4 PreferenceRule）。 */
    public void runPreferenceSync() {
        var stats = preferenceConsolidator.consolidate();
        log.info("巩固管线: 偏好同步完成, created={}, reinforced={}, deleted={}",
                stats.created(), stats.reinforced(), stats.deleted());
    }

    /** 阶段 4：经验合并（相似经验 → 元经验）。 */
    public void runExperienceMerge() {
        var stats = experienceMerger.merge();
        log.info("巩固管线: 经验合并完成, candidates={}, merged={}, skipped={}",
                stats.candidatesFound(), stats.merged(), stats.skipped());
    }

    /** 阶段 5：用户画像巩固。 */
    public void runUserProfileConsolidation(boolean manualTrigger) {
        userProfileConsolidator.consolidate(manualTrigger);
        log.info("巩固管线: 用户画像巩固完成");
    }

    /** 阶段 6：高频经验提升为 L4 ProcedureTemplate。 */
    public void runExperiencePromotion() {
        experiencePromoter.promote();
        log.info("巩固管线: 经验提升完成");
    }

    /** 阶段 7：REM 式联想（memory-rem-consolidation spec）。 */
    public void runRemAssociation() {
        if (!properties.getRem().isEnabled()) {
            return;
        }
        var candidates = remGenerator.generate();
        int saved = remConsolidator.consolidate(candidates);
        if (!candidates.isEmpty() || saved > 0) {
            log.info("巩固管线: REM 联想完成, candidates={}, saved={}", candidates.size(), saved);
        }
        // 阶段 7.1：将文件候选应用到 memory_relations 主库（受 apply-enabled 门控）
        if (properties.getRem().isApplyEnabled()) {
            var applyResult = remApplier.apply(java.time.LocalDate.now());
            if (applyResult.applied() > 0) {
                log.info("巩固管线: REM 候选落库, {}", applyResult);
            }
        }
    }
}
