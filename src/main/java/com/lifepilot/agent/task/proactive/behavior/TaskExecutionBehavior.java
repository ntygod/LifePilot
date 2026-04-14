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

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        if (intentMemoryService == null) return List.of();

        var intents = intentMemoryService.getActiveIntents(ctx.userId());
        var candidates = new ArrayList<ProactiveCandidate>();

        for (var intent : intents) {
            // 只处理条件型意图
            if (intent.intentType() != IntentType.CONDITIONAL) continue;
            if (intent.triggerCondition() == null) continue;

            // Phase 3: 简单的触发检查 — 检查次数达到阈值视为条件就绪
            // 后续可对接外部数据源做真实条件评估
            if (intent.checkCount() >= 3) {
                float score = 0.55f;
                candidates.add(new ProactiveCandidate(
                        UUID.randomUUID().toString(), name(),
                        "exec-" + intent.id(), intent.goal(),
                        score, "条件可能已满足: " + intent.triggerCondition(), intent));
            }
        }
        return candidates;
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
                intentMemoryService.fulfillIntent(intent.id());
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
