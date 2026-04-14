package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.intent.IntentRecord;
import com.lifepilot.agent.task.proactive.intent.IntentType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 情境准备行为插件 — 在即将到来的事件或截止日期前准备相关上下文。
 *
 * <p>detect: 检查意图记忆中即将触发的条件型/习惯型意图。
 * reason: 汇总相关对话历史，生成准备建议。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ContextPrepBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(ContextPrepBehavior.class);
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(15);

    @Nullable private final IntentMemoryService intentMemoryService;
    @Nullable private final GenerationRouter generationRouter;

    public ContextPrepBehavior(@Nullable IntentMemoryService intentMemoryService,
                               @Nullable GenerationRouter generationRouter) {
        this.intentMemoryService = intentMemoryService;
        this.generationRouter = generationRouter;
    }

    @Override
    public String name() { return "context-prep"; }

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
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = generatePrep(candidate);
            if (content == null || content.isBlank()) continue;
            actions.add(new ProactiveAction(candidate, content, DeliveryLevel.NOTIFY, candidate.detail()));
        }
        return actions;
    }

    private String generatePrep(ProactiveCandidate candidate) {
        if (generationRouter != null) {
            try {
                String prompt = "你是个人助手。用户有一个待办事项：「" + candidate.title()
                        + "」。请用一句话（不超过40字）提醒用户可以提前准备什么。语气自然，不要说教。只输出提醒内容。";
                LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                        GenerationCapability.CHAT, LLM_TIMEOUT);
                if (response != null && !response.content().isBlank()) return response.content().strip();
            } catch (Exception e) {
                log.debug("ContextPrepBehavior: LLM 失败: {}", e.getMessage());
            }
        }
        return "你之前提到「" + candidate.title() + "」，要不要提前准备一下？";
    }
}
