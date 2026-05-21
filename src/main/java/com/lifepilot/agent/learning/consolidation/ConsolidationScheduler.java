package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 巩固调度器 — 将原 ConsolidationPipeline 的 7 阶段解耦为独立调度单元。
 *
 * <p>每个阶段有独立的触发策略（EVENT / CRON / IDLE），互不阻塞。
 * 当前实现作为 ConsolidationPipeline 的上层调度包装，后续逐步替代。</p>
 *
 * <p>触发策略：
 * <ul>
 *   <li>EVENT：对话结束后触发语义巩固、程序巩固</li>
 *   <li>EVENT_DEBOUNCED：对话结束后 30min 防抖触发用户画像巩固</li>
 *   <li>DAILY_CRON：每日定时触发偏好同步、经验合并、经验提升</li>
 *   <li>IDLE：空闲 30min 后触发 REM 联想</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
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
     * 对话结束后触发事件驱动阶段（语义巩固 + 程序巩固）。
     *
     * <p>由 ConversationCompletionHook 或 Spring Event 监听器调用。
     * 在 Virtual Thread 中异步执行，不阻塞对话结束流程。</p>
     */
    public void onConversationCompleted() {
        Thread.startVirtualThread(() -> {
            executeWithIsolation("语义巩固（事件驱动）", () -> pipeline.consolidate(false));
        });
        // 记录画像防抖事件
        lastProfileEventTime = Instant.now();
    }

    /**
     * 每日定时触发（偏好同步 + 经验合并 + 经验提升）。
     *
     * <p>由 @Scheduled 或外部调度器调用。当前委托给 pipeline.consolidate()，
     * 后续拆分为独立阶段。</p>
     */
    public void executeDailyCronStages() {
        executeWithIsolation("每日巩固", () -> pipeline.consolidate(false));
    }

    /**
     * 空闲触发（REM 联想）。
     *
     * <p>由 IdleDetector 调用。</p>
     */
    public void executeIdleStages() {
        executeWithIsolation("空闲巩固（REM 联想）", () -> pipeline.consolidate(false));
    }

    /**
     * 检查用户画像防抖窗口是否满足。
     *
     * <p>由定时轮询（每分钟）调用。满足条件时触发画像巩固。</p>
     */
    public void checkProfileDebounce() {
        if (lastProfileEventTime == null) return;
        Instant now = Instant.now();

        // 防抖窗口未满足
        if (Duration.between(lastProfileEventTime, now).compareTo(profileDebounceWindow) < 0) return;

        // 冷却期未满足
        if (lastProfileExecutionTime != null
                && Duration.between(lastProfileExecutionTime, now).compareTo(profileDebounceWindow) < 0) return;

        executeWithIsolation("用户画像巩固（防抖）", () -> pipeline.consolidate(false));
        lastProfileExecutionTime = now;
        lastProfileEventTime = null;
    }

    /**
     * 手动触发全量巩固（管理端入口）。
     */
    public void consolidateAll() {
        pipeline.consolidate(true);
    }

    private void executeWithIsolation(String stageName, Runnable stage) {
        try {
            stage.run();
            log.debug("巩固调度: {} 完成", stageName);
        } catch (Exception e) {
            log.warn("巩固调度: {} 失败, error={}", stageName, e.getMessage());
        }
    }
}
