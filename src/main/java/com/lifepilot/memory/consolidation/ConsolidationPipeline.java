package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 巩固管线编排服务 — 顺序执行语义巩固和程序巩固，故障隔离。
 *
 * <p>通过 {@code @Scheduled} 定时触发，也可通过 {@link #consolidate()} 方法手动调用
 * （为 Idle-Driven 触发模式预留）。</p>
 *
 * <p>执行顺序：语义巩固 → 程序巩固 → 偏好同步。单个步骤异常不阻塞其他步骤，通过 try-catch 隔离。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ConsolidationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationPipeline.class);

    private final EpisodicToSemanticConsolidator semanticConsolidator;
    private final EpisodicToProceduralConsolidator proceduralConsolidator;
    private final MemoryProperties properties;
    private final PreferenceConsolidator preferenceConsolidator;

    /**
     * 构造巩固管线。
     *
     * @param semanticConsolidator    情景→语义巩固器
     * @param proceduralConsolidator  情景→程序巩固器
     * @param properties              记忆配置
     * @param preferenceConsolidator  L3→L4 偏好同步器，为 null 时跳过偏好同步步骤
     */
    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  EpisodicToProceduralConsolidator proceduralConsolidator,
                                  MemoryProperties properties,
                                  @Nullable PreferenceConsolidator preferenceConsolidator) {
        this.semanticConsolidator = semanticConsolidator;
        this.proceduralConsolidator = proceduralConsolidator;
        this.properties = properties;
        this.preferenceConsolidator = preferenceConsolidator;
        log.info("ConsolidationPipeline 初始化完成, cron={}, triggerMode={}, preferenceSync={}",
                this.properties.getConsolidation().getCron(),
                this.properties.getConsolidation().getTriggerMode(),
                this.preferenceConsolidator != null ? "启用" : "禁用");
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
     * 执行巩固管线 — 顺序执行语义巩固、程序巩固和偏好同步。
     *
     * <p>可被外部调用（不仅限于 {@code @Scheduled}），为 Idle-Driven 触发模式预留。</p>
     *
     * <p>单个步骤异常不阻塞其他步骤，通过 try-catch 隔离。
     * {@code preferenceConsolidator} 为 null 时跳过偏好同步步骤。</p>
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

        // 3. 偏好同步（L3→L4）— preferenceConsolidator 为 null 时跳过
        if (preferenceConsolidator != null) {
            try {
                var syncStats = preferenceConsolidator.consolidate();
                log.info("巩固管线: 偏好同步完成, created={}, reinforced={}, deleted={}",
                        syncStats.created(), syncStats.reinforced(), syncStats.deleted());
            } catch (Exception e) {
                log.warn("巩固管线: 偏好同步失败, error={}", e.getMessage(), e);
            }
        }

        log.info("巩固管线: 执行完成");
    }
}
