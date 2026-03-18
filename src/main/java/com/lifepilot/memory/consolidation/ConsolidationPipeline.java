package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.procedural.ProcedureTemplate;
import com.lifepilot.memory.procedural.TemplateStep;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
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

    /**
     * 构造巩固管线。
     *
     * @param semanticConsolidator   情景→语义巩固器
     * @param proceduralConsolidator 情景→程序巩固器
     * @param properties             记忆配置
     * @param preferenceConsolidator L3→L4 偏好同步器（可选）
     * @param semanticMemory         L3 语义记忆（可选，用于经验提升）
     * @param proceduralMemory       L4 程序记忆（可选，用于经验提升）
     * @param experienceMerger       经验合并器（可选，用于相似经验合并为元经验）
     */
    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  EpisodicToProceduralConsolidator proceduralConsolidator,
                                  MemoryProperties properties,
                                  @Nullable PreferenceConsolidator preferenceConsolidator,
                                  @Nullable SemanticMemory semanticMemory,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  @Nullable ExperienceMerger experienceMerger) {
        this.semanticConsolidator = semanticConsolidator;
        this.proceduralConsolidator = proceduralConsolidator;
        this.properties = properties;
        this.preferenceConsolidator = preferenceConsolidator;
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
        this.experienceMerger = experienceMerger;
        log.info("ConsolidationPipeline 初始化完成, cron={}, triggerMode={}, 经验合并={}",
                this.properties.getConsolidation().getCron(),
                this.properties.getConsolidation().getTriggerMode(),
                this.experienceMerger != null ? "启用" : "禁用");
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
        try {
            var proceduralStats = proceduralConsolidator.consolidate();
            log.info("巩固管线: 程序巩固完成, traces={}, created={}",
                    proceduralStats.conversationsAnalyzed(), proceduralStats.templatesCreated());
        } catch (Exception e) {
            log.warn("巩固管线: 程序巩固失败, error={}", e.getMessage(), e);
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

        // 3.5 经验合并（相似经验 → 元经验）
        if (experienceMerger != null) {
            try {
                var mergeStats = experienceMerger.merge();
                log.info("巩固管线: 经验合并完成, candidates={}, merged={}, skipped={}",
                        mergeStats.candidatesFound(), mergeStats.merged(), mergeStats.skipped());
            } catch (Exception e) {
                log.warn("巩固管线: 经验合并失败, error={}", e.getMessage(), e);
            }
        }

        // 4. 高频经验提升为 L4 ProcedureTemplate
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

        log.info("巩固管线: 执行完成");
    }

    /**
     * 将高频经验（importanceScore ≥ 0.8 且 accessCount ≥ 3）提升为 L4 ProcedureTemplate。
     *
     * @return 提升的经验数量
     */
    private int promoteHighFrequencyExperiences() {
        var experiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
        int promoted = 0;

        for (var exp : experiences) {
            if (exp.importanceScore() >= 0.8f && exp.accessCount() >= 3) {
                // 检查是否已存在同名模板（避免重复提升）
                var existing = proceduralMemory.findById(exp.id());
                if (existing.isPresent()) continue;

                var now = Instant.now();
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
                        now
                );
                proceduralMemory.save(template);

                // 归档已提升的经验
                semanticMemory.archive(exp);
                promoted++;
                log.debug("巩固管线: 经验提升为模板, entityId={}, name={}", exp.id(), exp.name());
            }
        }
        return promoted;
    }
}
