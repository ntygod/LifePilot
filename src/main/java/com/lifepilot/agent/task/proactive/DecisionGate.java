package com.lifepilot.agent.task.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统一决策门控 — 防过度打扰的最后防线。
 *
 * <p>负责全局维度的约束检查和投递级别调整，
 * 插件级约束（话题静音、冷却等）由插件自行在 detect 阶段过滤。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class DecisionGate {

    private static final Logger log = LoggerFactory.getLogger(DecisionGate.class);

    /** 时间偏好低于此值时降级投递。 */
    private static final float LOW_PREFERENCE_THRESHOLD = 0.3f;

    /** 静态默认阈值（与历史兼容）。 */
    private static final float DEFAULT_NOTIFY_THRESHOLD = 0.5f;
    private static final float DEFAULT_INTERRUPT_THRESHOLD = 0.7f;
    private static final float QUEUE_THRESHOLD = 0.3f;

    @Nullable private final TrustUpgradeService trustUpgradeService;
    @Nullable private final ProactiveMemoryBridge memoryBridge;

    /** Boundary / Focus 阈值偏移（新维度）。 */
    private final float boundaryNotifyDelta;
    private final float boundaryInterruptDelta;
    private final float outOfBoundaryDelta;

    public DecisionGate(@Nullable TrustUpgradeService trustUpgradeService,
                        @Nullable ProactiveMemoryBridge memoryBridge) {
        this(trustUpgradeService, memoryBridge, -0.15f, -0.15f, 0.25f);
    }

    public DecisionGate(@Nullable TrustUpgradeService trustUpgradeService,
                        @Nullable ProactiveMemoryBridge memoryBridge,
                        float boundaryNotifyDelta,
                        float boundaryInterruptDelta,
                        float outOfBoundaryDelta) {
        this.trustUpgradeService = trustUpgradeService;
        this.memoryBridge = memoryBridge;
        this.boundaryNotifyDelta = boundaryNotifyDelta;
        this.boundaryInterruptDelta = boundaryInterruptDelta;
        this.outOfBoundaryDelta = outOfBoundaryDelta;
    }

    /**
     * 分数 → 投递级别映射（静态默认，历史兼容）。
     *
     * @deprecated 请使用 {@link #scoreToLevel(float, ContextPacket)} 以启用 boundary / focus 动态阈值
     */
    @Deprecated
    public static DeliveryLevel scoreToLevel(float score) {
        if (score >= DEFAULT_INTERRUPT_THRESHOLD) return DeliveryLevel.INTERRUPT;
        if (score >= DEFAULT_NOTIFY_THRESHOLD) return DeliveryLevel.NOTIFY;
        if (score >= QUEUE_THRESHOLD) return DeliveryLevel.QUEUE;
        return DeliveryLevel.SILENT;
    }

    /**
     * 分数 → 投递级别映射（动态版本，按 boundary / focus 调整阈值）。
     *
     * <p>调整规则（叠加）：
     * <ul>
     *   <li>IN_BOUNDARY：NOTIFY 阈值 += {@code boundaryNotifyDelta}（默认 -0.15，更易触发）；
     *       INTERRUPT 阈值 += {@code boundaryInterruptDelta}</li>
     *   <li>OUT_OF_BOUNDARY：两级阈值均 += {@code outOfBoundaryDelta}（默认 +0.25，更难触发）</li>
     *   <li>FOCUS_MODE：最终 NOTIFY → QUEUE，INTERRUPT 保留（真紧急场景保留打断能力）</li>
     * </ul>
     * </p>
     */
    public DeliveryLevel scoreToLevel(float score, ContextPacket ctx) {
        float notifyThreshold = DEFAULT_NOTIFY_THRESHOLD;
        float interruptThreshold = DEFAULT_INTERRUPT_THRESHOLD;

        if (ctx != null) {
            if (ctx.isInBoundary()) {
                notifyThreshold += boundaryNotifyDelta;
                interruptThreshold += boundaryInterruptDelta;
            } else if (ctx.isOutOfBoundary()) {
                notifyThreshold += outOfBoundaryDelta;
                interruptThreshold += outOfBoundaryDelta;
            }
        }
        // 边界保护：QUEUE < NOTIFY < INTERRUPT ≤ 1
        notifyThreshold = Math.max(QUEUE_THRESHOLD + 0.01f, Math.min(0.99f, notifyThreshold));
        interruptThreshold = Math.max(notifyThreshold + 0.01f, Math.min(0.999f, interruptThreshold));

        DeliveryLevel level;
        if (score >= interruptThreshold) level = DeliveryLevel.INTERRUPT;
        else if (score >= notifyThreshold) level = DeliveryLevel.NOTIFY;
        else if (score >= QUEUE_THRESHOLD) level = DeliveryLevel.QUEUE;
        else level = DeliveryLevel.SILENT;

        // Focus 降级 — 只降 NOTIFY（INTERRUPT 保留，QUEUE/SILENT 不变）
        if (ctx != null && ctx.isInFocusMode() && level == DeliveryLevel.NOTIFY) {
            level = DeliveryLevel.QUEUE;
        }
        return level;
    }

    /**
     * 评估候选动作列表，返回通过门控的 (动作, 投递级别) 对。
     */
    public List<GatedAction> evaluate(List<ProactiveAction> actions, ContextPacket ctx) {
        if (actions == null || actions.isEmpty()) return List.of();

        // ── 硬边界（一票否决） ──
        if (ctx.isWithinQuietHours()) {
            log.debug("决策门控: 安静时段，全部跳过");
            return List.of();
        }
        if (ctx.isFullscreen()) {
            log.debug("决策门控: 全屏模式，全部跳过");
            return List.of();
        }
        int remaining = ctx.remainingSlots();
        if (remaining <= 0) {
            log.debug("决策门控: 每日上限已满，全部跳过");
            return List.of();
        }

        // ── 按分数降序排列 ──
        var sorted = actions.stream()
                .sorted(Comparator.comparingDouble((ProactiveAction a) -> a.candidate().score()).reversed())
                .toList();

        // ── 软约束 + 投递级别分配 ──
        var result = new ArrayList<GatedAction>();
        for (var action : sorted) {
            if (result.size() >= remaining) {
                log.debug("决策门控: 额度用尽，跳过 topic={}", action.candidate().topicKey());
                break;
            }
            DeliveryLevel level = action.suggestedLevel();

            // 编码中降级: INTERRUPT → NOTIFY
            if (ctx.isFocusedCoding() && level == DeliveryLevel.INTERRUPT) {
                level = DeliveryLevel.NOTIFY;
                log.debug("决策门控: 编码中，INTERRUPT→NOTIFY topic={}", action.candidate().topicKey());
            }

            // 自主度约束: A 级最高 NOTIFY，不允许 INTERRUPT
            if (trustUpgradeService != null) {
                AutonomyLevel autonomy = trustUpgradeService.getLevel(
                        ctx.userId(), action.candidate().behaviorName());
                if (autonomy == AutonomyLevel.A && level == DeliveryLevel.INTERRUPT) {
                    level = DeliveryLevel.NOTIFY;
                    log.debug("决策门控: 自主度A，INTERRUPT→NOTIFY behavior={}",
                            action.candidate().behaviorName());
                }
            }

            // 偏好降级: 当前时段或行为领域偏好低 → 降一级
            if (memoryBridge != null && level.isNotifiable()) {
                if (isLowPreferenceTimeSlot(ctx) || isLowPreferenceBehavior(ctx, action)) {
                    DeliveryLevel downgraded = level == DeliveryLevel.INTERRUPT ? DeliveryLevel.NOTIFY : DeliveryLevel.QUEUE;
                    log.debug("决策门控: 偏好低，{}→{} behavior={}",
                            level, downgraded, action.candidate().behaviorName());
                    level = downgraded;
                }
            }

            // Boundary / Focus 动态调整 — 不允许升级，只允许降级
            DeliveryLevel boundaryLevel = applyBoundaryAndFocus(action.candidate().score(), level, ctx);
            if (boundaryLevel != level) {
                log.debug("决策门控: boundary/focus 调整，{}→{} topic={}, boundary={}, focus={}",
                        level, boundaryLevel, action.candidate().topicKey(),
                        ctx.boundaryState(), ctx.focusMode());
                level = boundaryLevel;
            }

            // SILENT 级别不占额度，仅记录
            if (level == DeliveryLevel.SILENT) {
                log.debug("决策门控: SILENT topic={}", action.candidate().topicKey());
                continue;
            }

            result.add(new GatedAction(action, level));
        }
        return List.copyOf(result);
    }

    /**
     * 基于 boundary/focus 调整投递级别。
     *
     * <p>规则：
     * <ul>
     *   <li>boundaryState = UNKNOWN 且 focusMode = NORMAL：不做任何调整（保持插件建议级别）</li>
     *   <li>boundary 已知时：基于 score + ctx 算动态阈值级别，取 min(当前级别, 动态级别)</li>
     *   <li>FOCUS_MODE：NOTIFY → QUEUE（INTERRUPT 保留）</li>
     * </ul>
     * </p>
     */
    private DeliveryLevel applyBoundaryAndFocus(float score, DeliveryLevel currentLevel, ContextPacket ctx) {
        DeliveryLevel level = currentLevel;

        // 只有在 boundary 已知时才按动态阈值映射；UNKNOWN 保持插件级别不变
        if (ctx.isInBoundary() || ctx.isOutOfBoundary()) {
            DeliveryLevel mapped = scoreToLevel(score, ctx);
            if (mapped.ordinal() < level.ordinal()) {
                level = mapped;
            }
        }

        // Focus 降级始终生效（独立于 boundary 是否已知）
        if (ctx.isInFocusMode() && level == DeliveryLevel.NOTIFY) {
            level = DeliveryLevel.QUEUE;
        }
        return level;
    }

    /** 检查当前时段的用户偏好是否低。 */
    private boolean isLowPreferenceTimeSlot(ContextPacket ctx) {
        if (memoryBridge == null) return false;
        String timeSlot = TimeSlotResolver.resolve(ctx);
        return memoryBridge.getPreferences("proactive-timing").stream()
                .filter(p -> p.key().equals(timeSlot) && p.observationCount() >= 3)
                .anyMatch(p -> ProactiveMemoryBridge.parseFloat(p.value(), 0.5f) < LOW_PREFERENCE_THRESHOLD);
    }

    /** 检查行为领域的用户偏好是否低。 */
    private boolean isLowPreferenceBehavior(ContextPacket ctx, ProactiveAction action) {
        if (memoryBridge == null) return false;
        return memoryBridge.getPreferences("proactive-domain").stream()
                .filter(p -> p.key().equals(action.candidate().behaviorName()) && p.observationCount() >= 3)
                .anyMatch(p -> ProactiveMemoryBridge.parseFloat(p.value(), 0.5f) < LOW_PREFERENCE_THRESHOLD);
    }

    /** 门控通过的动作 + 最终投递级别。 */
    public record GatedAction(ProactiveAction action, DeliveryLevel level) {}
}
