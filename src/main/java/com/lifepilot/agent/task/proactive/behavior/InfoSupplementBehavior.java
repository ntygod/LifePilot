package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 信息补充行为插件 — 当知识库有与用户兴趣相关的新内容时主动推送。
 *
 * <p>detect: 检查 L3 活跃目标关键词是否匹配近期知识库更新。
 * reason: 生成摘要推荐。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class InfoSupplementBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(InfoSupplementBehavior.class);

    @Nullable private final ProactiveMemoryBridge memoryBridge;

    public InfoSupplementBehavior(@Nullable ProactiveMemoryBridge memoryBridge) {
        this.memoryBridge = memoryBridge;
    }

    @Override
    public String name() { return "info-supplement"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        if (memoryBridge == null) return List.of();

        var goals = memoryBridge.getActiveGoals();
        if (goals.isEmpty()) return List.of();

        var candidates = new ArrayList<ProactiveCandidate>();
        for (var goal : goals) {
            if (goal.checkCount() > 3) continue;
            float score = 0.35f;
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "info-" + goal.entityId(), "关于「" + goal.goal() + "」的信息",
                    score, "活跃兴趣: " + goal.goal(), goal));
        }
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String goalName = candidate.detail() instanceof GoalView g ? g.goal() : candidate.title();
            String content = "你最近在关注「" + goalName + "」，需要我帮你搜集一些相关资料吗？";
            actions.add(new ProactiveAction(candidate, content, DeliveryLevel.QUEUE, candidate.detail()));
        }
        return actions;
    }
}
