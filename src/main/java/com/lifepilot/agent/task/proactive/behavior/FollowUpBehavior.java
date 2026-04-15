package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 主动追问行为插件 — 基于 L3 目标实体追踪用户未完成的目标。
 *
 * <p>detect: 查询 L3 GOAL 实体，过滤创建超过 24h 且追问次数合理的。
 * reason: 使用 LLM 生成自然追问（带回退模板），自动注入画像/经验 + 目标上下文。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class FollowUpBehavior extends AbstractLlmBehavior {

    private static final Logger log = LoggerFactory.getLogger(FollowUpBehavior.class);
    private static final String PROMPT_KEY = "generation/proactive-follow-up";

    private final ProactiveMemoryBridge memoryBridge;
    @Nullable private final AgentConfigProperties config;

    public FollowUpBehavior(ProactiveMemoryBridge memoryBridge,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable PromptRegistry promptRegistry,
                            @Nullable AgentConfigProperties config) {
        super(generationRouter, promptRegistry);
        this.memoryBridge = memoryBridge;
        this.config = config;
    }

    private Duration minAge() {
        return Duration.ofHours(config != null ? config.getTask().getProactiveEngineFollowUpMinAgeHours() : 24);
    }

    private int maxCheckCount() {
        return config != null ? config.getTask().getProactiveEngineFollowUpMaxCheckCount() : 5;
    }

    @Override
    protected Duration llmTimeout() {
        return Duration.ofSeconds(config != null ? config.getTask().getProactiveEngineLlmTimeoutSeconds() : 15);
    }

    @Override
    public String name() { return "follow-up"; }

    @Override
    protected String promptKey() { return PROMPT_KEY; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        var goals = memoryBridge.getActiveGoals();
        var candidates = new ArrayList<ProactiveCandidate>();

        for (var goal : goals) {
            if (Duration.between(goal.createdAt(), ctx.now()).compareTo(minAge()) < 0) continue;
            if (goal.checkCount() >= maxCheckCount()) continue;

            float score = computeScore(goal, ctx.now());
            if (score < 0.3f) continue;

            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "goal-" + goal.entityId(), goal.goal(),
                    score, "活跃目标 (重要度:" + String.format("%.1f", goal.importanceScore()) + ")",
                    goal));
        }
        log.debug("FollowUpBehavior.detect: goals={}, candidates={}", goals.size(), candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = super.reason(candidates, ctx);
        // 只对成功产出 action 的候选递增追问计数，避免 LLM 失败白白消耗配额
        for (var action : actions) {
            if (action.candidate().detail() instanceof GoalView goal) {
                memoryBridge.incrementCheckCount(goal.entityId());
            }
        }
        return actions;
    }

    @Override
    protected Map<String, Object> buildPromptVariables(ProactiveCandidate candidate, ContextPacket ctx) {
        var vars = new HashMap<String, Object>();
        vars.put("currentTime", formatTime(ctx));
        vars.put("intentGoal", candidate.title());
        vars.put("conversationSummary", candidate.rationale());
        vars.put("daysSinceLastChat", String.valueOf(computeDaysSince(candidate, ctx.now())));

        // 注入丰富的目标上下文（演变历史 + 关联实体 + 对话片段）
        if (candidate.detail() instanceof GoalView goal) {
            String goalContext = memoryBridge.enrichGoalContext(goal.entityId(), goal.goal());
            if (!goalContext.isBlank()) {
                vars.put("goalContext", goalContext);
            }
        }
        return vars;
    }

    @Override
    protected String fallbackContent(ProactiveCandidate candidate) {
        return "你之前提到过「" + candidate.title() + "」，进展怎么样了？";
    }

    @Override
    protected DeliveryLevel suggestLevel(ProactiveCandidate candidate) {
        return DeliveryLevel.NOTIFY;
    }

    private float computeScore(GoalView goal, Instant now) {
        long ageDays = Duration.between(goal.createdAt(), now).toDays();
        float score = 0.4f;
        // 重要度加成（L3 importanceScore 范围 0-1）
        score += goal.importanceScore() * 0.2f;
        // 时间衰减
        if (ageDays > 7) score -= (ageDays - 7) * 0.02f;
        // 追问次数衰减
        score -= goal.checkCount() * 0.05f;
        return Math.max(0f, Math.min(1f, score));
    }

    private long computeDaysSince(ProactiveCandidate candidate, Instant now) {
        if (candidate.detail() instanceof GoalView goal) {
            return Duration.between(goal.createdAt(), now).toDays();
        }
        return 1;
    }
}
