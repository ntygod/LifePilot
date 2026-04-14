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

    @Nullable
    private final TrustUpgradeService trustUpgradeService;

    public DecisionGate() { this(null); }

    public DecisionGate(@Nullable TrustUpgradeService trustUpgradeService) {
        this.trustUpgradeService = trustUpgradeService;
    }

    /** 分数 → 投递级别映射。 */
    public static DeliveryLevel scoreToLevel(float score) {
        if (score >= 0.7f) return DeliveryLevel.INTERRUPT;
        if (score >= 0.5f) return DeliveryLevel.NOTIFY;
        if (score >= 0.3f) return DeliveryLevel.QUEUE;
        return DeliveryLevel.SILENT;
    }

    /**
     * 评估候选动作列表，返回通过门控的 (动作, 投递级别) 对。
     *
     * @param actions 所有插件 reason() 产出的动作
     * @param ctx     心跳上下文
     * @return 通过门控的评估结果列表，按分数降序排列
     */
    public List<GatedAction> evaluate(List<ProactiveAction> actions, ContextPacket ctx) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

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
     * 门控通过的动作 + 最终投递级别。
     */
    public record GatedAction(ProactiveAction action, DeliveryLevel level) {}
}
