package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 任务代行行为插件 — 当目标触发条件满足时提示或执行任务。
 *
 * <p>detect: 当前版本返回空列表（真实条件评估未对接）。
 * reason: 生成执行计划建议（B 级）或直接执行（C 级）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class TaskExecutionBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionBehavior.class);

    @Nullable private final ProactiveMemoryBridge memoryBridge;
    @Nullable private final TrustUpgradeService trustUpgradeService;

    public TaskExecutionBehavior(@Nullable ProactiveMemoryBridge memoryBridge,
                                  @Nullable TrustUpgradeService trustUpgradeService) {
        this.memoryBridge = memoryBridge;
        this.trustUpgradeService = trustUpgradeService;
    }

    @Override
    public String name() { return "task-execution"; }

    @Override
    public com.lifepilot.agent.task.proactive.behavior.BehaviorLayer layer() {
        return com.lifepilot.agent.task.proactive.behavior.BehaviorLayer.EXPERIENCE_DRIVEN;
    }

    /**
     * 检测目标是否触发条件满足。
     *
     * <p>当前版本尚未对接真实条件评估（价格 API、天气 API 等），
     * 因此 detect 始终返回空列表，避免产生无依据的自主行动。</p>
     */
    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // TODO: 对接外部数据源实现真实条件评估后启用
        return List.of();
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            if (!(candidate.detail() instanceof GoalView goal)) continue;

            AutonomyLevel autonomy = trustUpgradeService != null
                    ? trustUpgradeService.getLevel(ctx.userId(), name())
                    : AutonomyLevel.B;

            String content;
            DeliveryLevel level;

            if (autonomy.canExecute()) {
                content = "你之前提到「" + goal.goal() + "」，条件看起来已经满足了。我已帮你处理。";
                level = DeliveryLevel.INTERRUPT;
                if (memoryBridge != null) {
                    memoryBridge.markGoalFulfilled(goal.entityId());
                }
            } else {
                content = "你之前提到「" + goal.goal() + "」，条件看起来已经满足了。需要我帮你处理吗？";
                level = DeliveryLevel.NOTIFY;
            }

            actions.add(new ProactiveAction(candidate, content, level, goal));
        }
        return actions;
    }
}
