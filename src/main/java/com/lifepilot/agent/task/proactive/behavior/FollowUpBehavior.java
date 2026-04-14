package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 主动追问行为插件 — 基于意图记忆追踪用户未完成的目标和话题。
 *
 * <p>detect: 查询活跃意图，过滤创建超过 24h 且检查次数合理的。
 * reason: 使用 LLM 生成自然追问（带回退模板）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class FollowUpBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(FollowUpBehavior.class);
    private static final String PROMPT_KEY = "generation/proactive-follow-up";

    private final IntentMemoryService intentMemoryService;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final PromptRegistry promptRegistry;
    @Nullable private final AgentConfigProperties config;

    public FollowUpBehavior(IntentMemoryService intentMemoryService,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable PromptRegistry promptRegistry) {
        this(intentMemoryService, generationRouter, promptRegistry, null);
    }

    public FollowUpBehavior(IntentMemoryService intentMemoryService,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable PromptRegistry promptRegistry,
                            @Nullable AgentConfigProperties config) {
        this.intentMemoryService = intentMemoryService;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = config;
    }

    private Duration minAge() {
        return Duration.ofHours(config != null ? config.getTask().getProactiveEngineFollowUpMinAgeHours() : 24);
    }

    private int maxCheckCount() {
        return config != null ? config.getTask().getProactiveEngineFollowUpMaxCheckCount() : 5;
    }

    private Duration llmTimeout() {
        return Duration.ofSeconds(config != null ? config.getTask().getProactiveEngineLlmTimeoutSeconds() : 15);
    }

    @Override
    public String name() { return "follow-up"; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // 意图维护已上移到 ProactiveEngine.heartbeat() 统一执行
        var intents = intentMemoryService.getActiveIntents(ctx.userId());
        var candidates = new ArrayList<ProactiveCandidate>();

        for (var intent : intents) {
            if (Duration.between(intent.createdAt(), ctx.now()).compareTo(minAge()) < 0) continue;
            if (intent.checkCount() >= maxCheckCount()) continue;

            float score = computeScore(intent, ctx.now());
            if (score < 0.3f) continue;

            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "intent-" + intent.id(), intent.goal(),
                    score, "活跃意图: " + intent.intentType(), intent));
        }
        log.debug("FollowUpBehavior.detect: intents={}, candidates={}", intents.size(), candidates.size());
        return candidates;
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = generateFollowUp(candidate, ctx);
            if (content == null || content.isBlank()) continue;

            actions.add(new ProactiveAction(candidate, content, DeliveryLevel.NOTIFY, candidate.detail()));

            if (candidate.detail() instanceof IntentRecord intent) {
                intentMemoryService.incrementCheckCount(intent.id());
            }
        }
        return actions;
    }

    private float computeScore(IntentRecord intent, Instant now) {
        long ageDays = Duration.between(intent.createdAt(), now).toDays();
        float score = 0.5f;
        if (ageDays > 7) score -= (ageDays - 7) * 0.02f;
        score -= intent.checkCount() * 0.05f;
        if (intent.intentType() == IntentType.CONDITIONAL) score += 0.1f;
        return Math.max(0f, Math.min(1f, score));
    }

    private String generateFollowUp(ProactiveCandidate candidate, ContextPacket ctx) {
        if (generationRouter != null && promptRegistry != null) {
            try {
                String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                        "currentTime", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ctx.now().atZone(ctx.zoneId())),
                        "intentGoal", candidate.title(),
                        "conversationSummary", candidate.rationale(),
                        "daysSinceLastChat", String.valueOf(computeDaysSince(candidate))));
                LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                        GenerationCapability.CHAT, llmTimeout());
                if (response != null && !response.content().isBlank()) return response.content().strip();
            } catch (Exception e) {
                log.debug("FollowUpBehavior: LLM 生成失败，使用回退模板: {}", e.getMessage());
            }
        }
        return "你之前提到过「" + candidate.title() + "」，进展怎么样了？";
    }

    private long computeDaysSince(ProactiveCandidate candidate) {
        if (candidate.detail() instanceof IntentRecord intent) {
            return Duration.between(intent.createdAt(), Instant.now()).toDays();
        }
        return 1;
    }
}
