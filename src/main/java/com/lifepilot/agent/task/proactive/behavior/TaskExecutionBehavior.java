package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRecord;
import com.lifepilot.agent.task.proactive.intent.IntentStatus;
import com.lifepilot.agent.task.proactive.intent.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 任务代行行为插件 — 当意图触发条件满足时提示或执行任务。
 *
 * <p>detect: 检查条件型意图的触发条件。
 * reason: 生成执行计划建议（B 级）或直接执行（C 级）。</p>
 *
 * <p>Phase 3 仅实现 B 级（建议），C 级执行对接工作流引擎在后续迭代。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class TaskExecutionBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionBehavior.class);

    @Nullable private final IntentMemoryService intentMemoryService;
    @Nullable private final TrustUpgradeService trustUpgradeService;

    public TaskExecutionBehavior(@Nullable IntentMemoryService intentMemoryService,
                                  @Nullable TrustUpgradeService trustUpgradeService) {
        this.intentMemoryService = intentMemoryService;
        this.trustUpgradeService = trustUpgradeService;
    }

    @Override
    public String name() { return "task-execution"; }

    /**
     * 检测条件型意图是否触发。
     *
     * <p>当前版本尚未对接真实条件评估（价格 API、天气 API 等），
     * 因此 detect 始终返回空列表，避免产生无依据的自主行动。
     * 真实条件评估将在后续迭代中实现。</p>
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
            if (!(candidate.detail() instanceof IntentRecord intent)) continue;

            AutonomyLevel autonomy = trustUpgradeService != null
                    ? trustUpgradeService.getLevel(ctx.userId(), name())
                    : AutonomyLevel.B;

            String content;
            DeliveryLevel level;

            if (autonomy.canExecute()) {
                // C 级：告知用户已执行（Phase 3 先用文案占位，实际执行待对接工作流）
                content = "你之前说过「" + intent.triggerCondition() + "」，条件看起来已经满足了。我已帮你处理。";
                level = DeliveryLevel.INTERRUPT;
                // 标记意图为已触发
                if (intentMemoryService != null) {
                    intentMemoryService.fulfillIntent(intent.id());
                }
            } else {
                // B 级：展示执行计划等确认
                content = "你之前说过「" + intent.triggerCondition() + "」，条件看起来已经满足了。需要我帮你处理吗？";
                level = DeliveryLevel.NOTIFY;
            }

            actions.add(new ProactiveAction(candidate, content, level, intent));
        }
        return actions;
    }
}
