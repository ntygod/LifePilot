package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 洞察推送行为插件 — 基于语义记忆中的实体变化检测模式趋势。
 *
 * <p>detect: 查询最近新增/更新的实体（目标、习惯、话题），识别变化趋势。
 * reason: 使用 LLM 生成可解释的洞察，自动注入画像/经验。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class InsightBehavior extends AbstractLlmBehavior {

    private static final Logger log = LoggerFactory.getLogger(InsightBehavior.class);
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);
    private static final String PROMPT_KEY = "generation/proactive-insight";
    private static final Set<EntityType> WATCHED_TYPES = Set.of(
            EntityType.GOAL, EntityType.HABIT, EntityType.TOPIC, EntityType.PROJECT);

    private final SemanticMemory semanticMemory;

    public InsightBehavior(SemanticMemory semanticMemory,
                           GenerationRouter generationRouter,
                           PromptRegistry promptRegistry) {
        super(generationRouter, promptRegistry);
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "语义记忆不能为空");
    }

    @Override
    public String name() { return "insight"; }

    @Override
    public BehaviorLayer layer() { return BehaviorLayer.FACT_DRIVEN; }

    @Override
    protected String promptKey() { return PROMPT_KEY; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        var candidates = new ArrayList<ProactiveCandidate>();
        Instant recentSince = ctx.now().minus(RECENT_WINDOW);

        for (var type : WATCHED_TYPES) {
            try {
                var entities = semanticMemory.findCurrentByType(type);
                for (var entity : entities) {
                    if (entity.createdAt().isAfter(recentSince)
                            || entity.updatedAt().isAfter(recentSince)) {
                        float score = computeScore(entity, ctx.now());
                        if (score >= 0.3f) {
                            String changeType = entity.createdAt().isAfter(recentSince)
                                    ? "新增" + type.label() : "更新" + type.label();
                            candidates.add(new ProactiveCandidate(
                                    UUID.randomUUID().toString(), name(),
                                    type.name().toLowerCase() + "-" + entity.name(),
                                    changeType + ": " + entity.name(),
                                    score, changeType, entity));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("InsightBehavior: 查询 {} 失败: {}", type, e.getMessage());
            }
        }
        log.debug("InsightBehavior.detect: candidates={}", candidates.size());
        return candidates;
    }

    @Override
    protected Map<String, Object> buildPromptVariables(ProactiveCandidate candidate, ContextPacket ctx) {
        return Map.of(
                "currentTime", formatTime(ctx),
                "changeType", candidate.rationale(),
                "changeDetail", candidate.title(),
                "relatedTopics", "");
    }

    private float computeScore(TemporalEntity entity, Instant now) {
        float score = 0.4f;
        score += entity.importanceScore() * 0.2f;
        if (Duration.between(entity.createdAt(), now).toDays() <= 3) score += 0.15f;
        if (entity.accessCount() > 3) score += 0.1f;
        return Math.max(0f, Math.min(1f, score));
    }
}
