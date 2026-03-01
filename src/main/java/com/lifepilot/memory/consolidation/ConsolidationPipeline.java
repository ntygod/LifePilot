package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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

    /**
     * 构造巩固管线。
     *
     * @param semanticConsolidator   情景→语义巩固器
     * @param proceduralConsolidator 情景→程序巩固器
     * @param properties             记忆配置
     */
    public ConsolidationPipeline(EpisodicToSemanticConsolidator semanticConsolidator,
                                  EpisodicToProceduralConsolidator proceduralConsolidator,
                                  MemoryProperties properties) {
        this.semanticConsolidator = semanticConsolidator;
        this.proceduralConsolidator = proceduralConsolidator;
        this.properties = properties;
        log.info("ConsolidationPipeline 初始化完成, cron={}, triggerMode={}",
                this.properties.getConsolidation().getCron(),
                this.properties.getConsolidation().getTriggerMode());
    }

    /**
     * 定时执行入口 — 由 Spring {@code @Scheduled} 按 Cron 表达式触发。
     */
    @Scheduled(cron = "${lifepilot.memory.consolidation.cron}")
    public void scheduledConsolidate() {
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

        log.info("巩固管线: 执行完成");
    }
}
