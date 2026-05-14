package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.memory.consolidation.association.AssociationConsolidator;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.procedural.ProcedureTemplate;
import com.lifepilot.memory.procedural.TemplateStep;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.support.SqliteBusyRetry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 巩固管线编排服务 — 顺序执行语义巩固和程序巩固，故障隔离。
 *
 * <p>通过 {@code @Scheduled} 定时触发，也可通过 {@link #consolidate()} 方法手动调用
 * （为 Idle-Driven 触发模式预留）。</p>
 *
 * <p>执行顺序：语义巩固 → 程序巩固。单个巩固器异常不阻塞另一个，通过 try-catch 隔离。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ConsolidationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationPipeline.class);

    private final EpisodicToSemanticConsolidator semanticConsolidator;
    @Nullable
    private final EpisodicToProceduralConsolidator proceduralConsolidator;
    private final MemoryProperties properties;
    @Nullable
    private final PreferenceConsolidator preferenceConsolidator;
    @Nullable
    private final SemanticMemory semanticMemory;
    @Nullable
    private final ProceduralMemory proceduralMemory;
    @Nullable
    private final ExperienceMerger experienceMerger;
    @Nullable
    private final UserProfileConsolidator userProfileConsolidator;
    @Nullable
    private final AssociationCandidateGenerator remGenerator;
    @Nullable
    private final AssociationConsolidator remConsolidator;

    /**
     * 构造巩固管线（含 REM 联想）。
     */
    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  @Nullable EpisodicToProceduralConsolidator proceduralConsolidator,
                                  MemoryProperties properties,
                                  @Nullable PreferenceConsolidator preferenceConsolidator,
                                  @Nullable SemanticMemory semanticMemory,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  @Nullable ExperienceMerger experienceMerger,
                                  @Nullable UserProfileConsolidator userProfileConsolidator,
                                  @Nullable AssociationCandidateGenerator remGenerator,
                                  @Nullable AssociationConsolidator remConsolidator) {
        this.semanticConsolidator = semanticConsolidator;
        this.proceduralConsolidator = proceduralConsolidator;
        this.properties = properties;
        this.preferenceConsolidator = preferenceConsolidator;
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
        this.experienceMerger = experienceMerger;
        this.userProfileConsolidator = userProfileConsolidator;
        this.remGenerator = remGenerator;
        this.remConsolidator = remConsolidator;
        log.info("ConsolidationPipeline 初始化完成, cron={}, triggerMode={}, 经验合并={}, 画像巩固={}, REM 联想={}",
                this.properties.getConsolidation().getCron(),
                this.properties.getConsolidation().getTriggerMode(),
                this.experienceMerger != null ? "启用" : "禁用",
                this.userProfileConsolidator != null ? "启用" : "禁用",
                (this.remGenerator != null && this.remConsolidator != null) ? "启用" : "禁用");
    }

    /**
     * 定时执行入口 — 由 Spring {@code @Scheduled} 按 Cron 表达式触发。
     *
     * <p>IDLE 模式下跳过 Cron 触发，仅 CRON 和 HYBRID 模式执行。</p>
     */
    @Scheduled(cron = "${lifepilot.memory.consolidation.cron}")
    public void scheduledConsolidate() {
        String mode = properties.getConsolidation().getTriggerMode();
        if ("IDLE".equalsIgnoreCase(mode)) {
            log.debug("巩固管线: IDLE 模式下跳过 Cron 触发");
            return;
        }
        log.info("巩固管线: 定时任务触发");
        consolidate();
    }

    /**
     * 执行巩固管线 — 顺序执行语义巩固和程序巩固。
     *
     * <p>可被外部调用（不仅限于 {@code @Scheduled}），为 Idle-Driven 触发模式预留。</p>
     *
     * <p>单个巩固器异常不阻塞另一个，通过 try-catch 隔离。</p>
     */
    public void consolidate() {
        consolidate(false);
    }

    /**
     * 执行巩固管线。
     *
     * @param manualTrigger 是否来自用户手动触发；手动触发会让用户画像巩固绕过最小间隔防抖
     */
    public void consolidate(boolean manualTrigger) {
        log.info("巩固管线: 开始执行");

        // 1. 语义巩固（情景→语义）
        try {
            var semanticStats = semanticConsolidator.consolidate();
            log.info("巩固管线: 语义巩固完成, conversations={}, boosted={}",
                    semanticStats.conversationsAnalyzed(), semanticStats.entitiesBoosted());
        } catch (Exception e) {
            log.warn("巩固管线: 语义巩固失败, error={}", e.getMessage(), e);
        }

        // 2. 程序巩固（情景→程序）
        if (proceduralConsolidator != null) {
            try {
                var proceduralStats = proceduralConsolidator.consolidate();
                log.info("巩固管线: 程序巩固完成, traces={}, created={}",
                        proceduralStats.conversationsAnalyzed(), proceduralStats.templatesCreated());
            } catch (Exception e) {
                log.warn("巩固管线: 程序巩固失败, error={}", e.getMessage(), e);
            }
        } else {
            log.debug("巩固管线: 程序巩固器不可用，已跳过");
        }

        // 3. 偏好同步（L3 PREFERENCE → L4 PreferenceRule）
        if (preferenceConsolidator != null) {
            try {
                var prefStats = preferenceConsolidator.consolidate();
                log.info("巩固管线: 偏好同步完成, created={}, reinforced={}, deleted={}",
                        prefStats.created(), prefStats.reinforced(), prefStats.deleted());
            } catch (Exception e) {
                log.warn("巩固管线: 偏好同步失败, error={}", e.getMessage(), e);
            }
        }

        // 4. 经验合并（相似经验 → 元经验）
        if (experienceMerger != null) {
            try {
                var mergeStats = experienceMerger.merge();
                log.info("巩固管线: 经验合并完成, candidates={}, merged={}, skipped={}",
                        mergeStats.candidatesFound(), mergeStats.merged(), mergeStats.skipped());
            } catch (Exception e) {
                log.warn("巩固管线: 经验合并失败, error={}", e.getMessage(), e);
            }
        }

        // 5. 用户画像巩固
        if (userProfileConsolidator != null) {
            try {
                userProfileConsolidator.consolidate(manualTrigger);
                log.info("巩固管线: 用户画像巩固完成");
            } catch (Exception e) {
                log.warn("巩固管线: 用户画像巩固失败, error={}", e.getMessage(), e);
            }
        }

        // 6. 高频经验提升为 L4 ProcedureTemplate
        if (semanticMemory != null && proceduralMemory != null) {
            try {
                int promoted = promoteHighFrequencyExperiences();
                if (promoted > 0) {
                    log.info("巩固管线: 经验提升完成, promoted={}", promoted);
                }
            } catch (Exception e) {
                log.warn("巩固管线: 经验提升失败, error={}", e.getMessage(), e);
            }
        }

        // 7. REM 式联想（memory-rem-consolidation spec）
        if (remGenerator != null && remConsolidator != null
                && properties.getRem().isEnabled()) {
            try {
                var candidates = remGenerator.generate();
                int saved = remConsolidator.consolidate(candidates);
                if (!candidates.isEmpty() || saved > 0) {
                    log.info("巩固管线: REM 联想完成, candidates={}, saved={}",
                            candidates.size(), saved);
                }
            } catch (Exception e) {
                log.warn("巩固管线: REM 联想失败, error={}", e.getMessage(), e);
            }
        }

        log.info("巩固管线: 执行完成");
    }

    /**
     * 将高频经验（importanceScore 和 accessCount 达到配置阈值）提升为 L4 ProcedureTemplate。
     *
     * @return 提升的经验数量
     */
    private int promoteHighFrequencyExperiences() {
        var consolidationConfig = properties.getConsolidation();
        float minImportance = consolidationConfig.getExperiencePromoteMinImportance();
        int minAccessCount = consolidationConfig.getExperiencePromoteMinAccessCount();

        var experiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
        int promoted = 0;

        for (var exp : experiences) {
            if (exp.importanceScore() >= minImportance && exp.accessCount() >= minAccessCount) {
                // 检查是否已存在同名模板（避免重复提升）
                var existing = proceduralMemory.findById(exp.id());
                if (existing.isPresent()) continue;

                var now = Instant.now();
                // 经验提升为 L4 模板 — sourceEntityId 指向源 L3 EXPERIENCE 实体 id。
                // 源实体保持 ACTIVE：后续真正失活时再由 L4SyncListener 级联模板失活。
                var template = new ProcedureTemplate(
                        exp.id(),
                        exp.name(),
                        exp.description(),
                        exp.name(),
                        List.of(new TemplateStep(1, "experience", "apply",
                                Map.of("scenario", exp.name()), exp.description(), false)),
                        Map.of(),
                        1.0f,
                        0,
                        null,
                        List.of(exp.sourceConversationId()),
                        now,
                        now,
                        exp.id(),
                        null
                );
                SqliteBusyRetry.run(() -> proceduralMemory.save(template));
                promoted++;
                log.debug("巩固管线: 经验提升为模板, entityId={}, name={}", exp.id(), exp.name());
            }
        }
        return promoted;
    }
}
