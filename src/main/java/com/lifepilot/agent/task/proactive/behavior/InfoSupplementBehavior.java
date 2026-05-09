package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 信息补充行为插件 — 当知识库有与用户兴趣相关的新内容时主动推送。
 *
 * <p>TODO: 当前为占位实现，detect 始终返回空列表。
 * 后续需对接知识库查询，只在确实有新增内容时才产出候选。</p>
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
    public com.lifepilot.agent.task.proactive.behavior.BehaviorLayer layer() {
        return com.lifepilot.agent.task.proactive.behavior.BehaviorLayer.STANDALONE;
    }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // TODO: 对接知识库查询，检查是否有与活跃目标匹配的新增内容
        return List.of();
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
