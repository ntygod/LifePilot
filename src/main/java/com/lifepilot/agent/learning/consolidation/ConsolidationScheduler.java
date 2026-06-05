package com.lifepilot.agent.learning.consolidation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;

/**
 * 巩固调度器 — 巩固系统的唯一调度真源，将 7 个巩固阶段按特性独立触发。
 *
 * <p>触发策略：
 * <ul>
 *   <li><b>EVENT</b>：对话结束后立即触发语义巩固(阶段1) + 程序巩固(阶段2)，秒级延迟</li>
 *   <li><b>EVENT + 防抖</b>：对话结束记录事件，满足防抖窗口后触发用户画像巩固(阶段5)</li>
 *   <li><b>DAILY_CRON</b>：每日定时触发偏好同步(3) + 经验合并(4) + 经验提升(6)</li>
 *   <li><b>IDLE</b>：系统空闲超阈值后触发 REM 联想(阶段7)</li>
 * </ul>
 *
 * <p>本类只负责「何时触发哪些阶段」；各阶段的实际执行与故障隔离委托给
 * {@link ConsolidationPipeline} 的阶段方法。空闲判定（查询最近交互时间）由
 * {@code AgentLearningAutoConfiguration} 持有 JdbcTemplate 完成后调用 {@link #runIdleStages()}。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
public class ConsolidationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationScheduler.class);

    private final ConsolidationPipeline pipeline;
    private final Duration profileDebounceWindow;

    /** 用户画像防抖：上次事件时间。 */
    @Nullable
    private volatile Instant lastProfileEventTime;
    /** 用户画像防抖：上次执行时间。 */
    @Nullable
    private volatile Instant lastProfileExecutionTime;

    public ConsolidationScheduler(ConsolidationPipeline pipeline,
                                  Duration profileDebounceWindow) {
        this.pipeline = pipeline;
        this.profileDebounceWindow = profileDebounceWindow;
    }

    /**
     * 对话结束触发（事件驱动）— 语义巩固 + 程序巩固，并登记画像防抖事件。
     *
     * <p>在虚拟线程中异步执行，不阻塞对话结束流程。</p>
     */
    public void onConversationCompleted() {
        Thread.startVirtualThread(() -> {
            pipeline.runSemanticConsolidation();
            pipeline.runProceduralConsolidation();
        });
        // 记录画像防抖事件时间（由 checkProfileDebounce 轮询消费）
        lastProfileEventTime = Instant.now();
    }

    /**
     * 每日定时阶段 — 偏好同步 + 经验合并 + 经验提升。
     *
     * <p>由 {@code @Scheduled(daily-cron)} 触发。</p>
     */
    public void runDailyStages() {
        log.info("巩固调度: 每日阶段开始");
        pipeline.runPreferenceSync();
        pipeline.runExperienceMerge();
        pipeline.runExperiencePromotion();
        log.info("巩固调度: 每日阶段完成");
    }

    /**
     * 空闲阶段 — REM 联想。
     *
     * <p>由空闲检测（AgentLearningAutoConfiguration 轮询）触发。</p>
     */
    public void runIdleStages() {
        log.info("巩固调度: 空闲阶段（REM 联想）开始");
        pipeline.runRemAssociation();
    }

    /**
     * 检查用户画像防抖窗口是否满足，满足则触发画像巩固(阶段5)。
     *
     * <p>由定时轮询（每分钟）调用。需同时满足：距上次事件 ≥ 防抖窗口，
     * 且距上次执行 ≥ 防抖窗口（冷却）。</p>
     */
    public void checkProfileDebounce() {
        if (lastProfileEventTime == null) return;
        Instant now = Instant.now();

        // 防抖窗口未满足
        if (Duration.between(lastProfileEventTime, now).compareTo(profileDebounceWindow) < 0) return;

        // 冷却期未满足
        if (lastProfileExecutionTime != null
                && Duration.between(lastProfileExecutionTime, now).compareTo(profileDebounceWindow) < 0) return;

        pipeline.runUserProfileConsolidation(false);
        lastProfileExecutionTime = now;
        lastProfileEventTime = null;
    }

    /**
     * 全量巩固（管理端入口）— 顺序执行全部 7 阶段。
     *
     * @param manualTrigger 手动触发使画像巩固绕过最小间隔防抖
     */
    public void consolidateAll(boolean manualTrigger) {
        pipeline.consolidate(manualTrigger);
    }
}
