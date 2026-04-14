package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentType;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.*;

/**
 * 情境准备行为插件 — 在即将到来的事件或截止日期前准备相关上下文。
 *
 * <p>detect: 检查意图记忆中即将触发的条件型/习惯型意图。
 * reason: 汇总相关对话历史，生成准备建议，自动注入画像/经验。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ContextPrepBehavior extends AbstractLlmBehavior {

    private static final Logger log = LoggerFactory.getLogger(ContextPrepBehavior.class);
    private static final String PROMPT_KEY = "generation/proactive-context-prep";

    @Nullable private final IntentMemoryService intentMemoryService;
    @Nullable private final AgentConfigProperties config;

    public ContextPrepBehavior(@Nullable IntentMemoryService intentMemoryService,
                               @Nullable GenerationRouter generationRouter,
                               @Nullable PromptRegistry promptRegistry,
                               @Nullable AgentConfigProperties config) {
        super(generationRouter, promptRegistry);
        this.intentMemoryService = intentMemoryService;
        this.config = config;
    }

    @Override
    protected Duration llmTimeout() {
        return Duration.ofSeconds(config != null ? config.getTask().getProactiveEngineLlmTimeoutSeconds() : 15);
    }

    @Override
    public String name() { return "context-prep"; }

    @Override
    protected String promptKey() { return PROMPT_KEY; }

    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        if (intentMemoryService == null) return List.of();

        var intents = intentMemoryService.getActiveIntents(ctx.userId());
        var candidates = new ArrayList<ProactiveCandidate>();

        for (var intent : intents) {
            // 条件型和习惯型意图更需要提前准备
            if (intent.intentType() != IntentType.CONDITIONAL
                    && intent.intentType() != IntentType.RECURRING) continue;
            if (intent.checkCount() > 2) continue;

            float score = 0.45f;
            if (intent.intentType() == IntentType.CONDITIONAL) score += 0.1f;

            candidates.add(new ProactiveCandidate(
                    UUID.randomUUID().toString(), name(),
                    "prep-" + intent.id(), intent.goal(),
                    score, "需要准备: " + intent.intentType(), intent));
        }
        return candidates;
    }

    @Override
    protected Map<String, Object> buildPromptVariables(ProactiveCandidate candidate, ContextPacket ctx) {
        return Map.of(
                "currentTime", formatTime(ctx),
                "todoItem", candidate.title(),
                "itemType", candidate.rationale());
    }

    @Override
    protected String fallbackContent(ProactiveCandidate candidate) {
        return "你之前提到「" + candidate.title() + "」，要不要提前准备一下？";
    }
}
