package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 行为插件基类 — 抽取 reason() 公共逻辑（模板方法模式）。
 *
 * <p>子类只需实现 {@link #buildPromptVariables}（构建 prompt 变量）
 * 和 {@link #fallbackContent}（LLM 不可用时的回退文案）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public abstract class AbstractLlmBehavior implements ProactiveBehavior {

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmBehavior.class);

    @Nullable protected final GenerationRouter generationRouter;
    @Nullable protected final PromptRegistry promptRegistry;

    protected AbstractLlmBehavior(@Nullable GenerationRouter generationRouter,
                                   @Nullable PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /** 子类提供的 prompt 模板 key（如 "generation/proactive-follow-up"）。 */
    protected abstract String promptKey();

    /** 子类提供的 LLM 超时时间。 */
    protected Duration llmTimeout() { return Duration.ofSeconds(15); }

    /** 子类构建 prompt 变量。 */
    protected abstract Map<String, Object> buildPromptVariables(ProactiveCandidate candidate, ContextPacket ctx);

    /** LLM 不可用时的回退文案。 */
    protected abstract String fallbackContent(ProactiveCandidate candidate);

    /** 子类指定建议的投递级别（默认根据分数）。 */
    protected DeliveryLevel suggestLevel(ProactiveCandidate candidate) {
        return DecisionGate.scoreToLevel(candidate.score());
    }

    @Override
    public List<ProactiveAction> reason(List<ProactiveCandidate> candidates, ContextPacket ctx) {
        var actions = new ArrayList<ProactiveAction>();
        for (var candidate : candidates) {
            String content = generateContent(candidate, ctx);
            if (content == null || content.isBlank()) continue;
            actions.add(new ProactiveAction(candidate, content, suggestLevel(candidate), candidate.detail()));
        }
        return actions;
    }

    /** 生成内容：LLM 优先 → 回退模板。画像和经验自动注入 prompt context。 */
    protected String generateContent(ProactiveCandidate candidate, ContextPacket ctx) {
        if (generationRouter != null && promptRegistry != null) {
            try {
                var vars = new java.util.HashMap<>(buildPromptVariables(candidate, ctx));
                // 自动注入画像和反思经验 — 让 LLM "懂"用户
                if (ctx.userProfile() != null) {
                    vars.put("userProfile", ctx.userProfile());
                }
                if (ctx.recentExperience() != null) {
                    vars.put("recentExperience", ctx.recentExperience());
                }
                String prompt = promptRegistry.render(promptKey(), vars);
                LlmResponse response = generationRouter.call("chat", prompt, null, null, null,
                        GenerationCapability.CHAT, llmTimeout());
                if (response != null && !response.content().isBlank()) {
                    return response.content().strip();
                }
            } catch (Exception e) {
                log.debug("{}: LLM 生成失败，使用回退模板: {}", name(), e.getMessage());
            }
        }
        return fallbackContent(candidate);
    }

    /** 格式化当前时间（供 prompt 变量使用）。 */
    protected static String formatTime(ContextPacket ctx) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ctx.now().atZone(ctx.zoneId()));
    }
}
