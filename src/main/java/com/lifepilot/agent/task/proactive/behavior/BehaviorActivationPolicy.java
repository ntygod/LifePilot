package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.TimeSlotResolver;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 行为插件分层激活策略 — 根据 {@link BehaviorLayer} + {@link ContextPacket} 决定
 * 某一层的插件本次心跳是否参与 detect。
 *
 * <p>激活规则：
 * <ul>
 *   <li>{@link BehaviorLayer#STANDALONE}：始终激活</li>
 *   <li>{@link BehaviorLayer#FACT_DRIVEN}：非 OUT_OF_BOUNDARY 时激活；OUT_OF_BOUNDARY 且今日未推送过且有画像时作为软降级条件激活</li>
 *   <li>{@link BehaviorLayer#HABIT_DRIVEN}：非 OUT_OF_BOUNDARY 或处于活跃时段（morning/afternoon/evening）时激活</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class BehaviorActivationPolicy {

    private static final Logger log = LoggerFactory.getLogger(BehaviorActivationPolicy.class);

    /** 判定该层的插件在当前上下文中是否应激活。 */
    public boolean shouldActivate(BehaviorLayer layer, ContextPacket ctx) {
        Objects.requireNonNull(layer, "行为分层不能为空");
        Objects.requireNonNull(ctx, "主动上下文不能为空");

        boolean result = switch (layer) {
            case STANDALONE -> true;
            case FACT_DRIVEN -> ctx.boundaryState() != BoundaryState.OUT_OF_BOUNDARY
                    || (ctx.actionsSentToday() == 0
                        && ctx.userProfile() != null
                        && !ctx.userProfile().isBlank());
            case HABIT_DRIVEN -> ctx.boundaryState() != BoundaryState.OUT_OF_BOUNDARY
                    || isActiveTimeSlot(ctx);
        };
        if (!result) {
            log.debug("分层激活: 跳过 layer={} boundary={} focus={}",
                    layer, ctx.boundaryState(), ctx.focusMode());
        }
        return result;
    }

    private boolean isActiveTimeSlot(ContextPacket ctx) {
        String slot = TimeSlotResolver.resolve(ctx);
        return "morning".equals(slot) || "afternoon".equals(slot) || "evening".equals(slot);
    }
}
