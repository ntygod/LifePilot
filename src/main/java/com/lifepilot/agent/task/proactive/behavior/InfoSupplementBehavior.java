package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 信息补充行为插件 — 当知识库有与用户兴趣相关的新内容时主动推送。
 *
 * <p>detect: 检查用户活跃意图关键词是否匹配近期知识库更新。
 * reason: 生成摘要推荐。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class InfoSupplementBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(InfoSupplementBehavior.class);

    @Nullable private final IntentMemoryService intentMemoryService;

    public InfoSupplementBehavior(@Nullable IntentMemoryService intentMemoryService) {
        this.intentMemoryService = intentMemoryService;
    }

    @Override
    public String name() { return "info-supplement"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        if (intentMemoryService == null) return List.of();

        // 获取活跃意图的关键词作为兴趣信号
        var intents = intentMemoryService.getActiveIntents(ctx.userId());
        if (intents.isEmpty()) return List.of();

        // Phase 3: 基于意图关键词匹配的候选
        // 后续可对接知识库搜索 API，当前先基于意图产出轻量建议
        var candidates = new ArrayList<ProactiveCandidate>();
        for (var intent : intents) {
            if (intent.checkCount() > 3) continue; // 避免重复推送
            float score = 0.35f; // 信息补充优先级较低
            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "info-" + intent.id(), "关于「" + intent.goal() + "」的信息",
                    score, "活跃兴趣: " + intent.goal(), intent));
        }
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = "你最近在关注「" + extractGoal(candidate) + "」，需要我帮你搜集一些相关资料吗？";
            actions.add(new ProactiveAction(candidate, content, DeliveryLevel.QUEUE, candidate.detail()));
        }
        return actions;
    }

    private String extractGoal(ProactiveCandidate candidate) {
        if (candidate.detail() instanceof IntentRecord intent) return intent.goal();
        return candidate.title().replaceAll("^关于「|」的信息$", "");
    }
}
