package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
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
 * <p>detect: 当前版本返回空列表（CONDITIONAL/RECURRING 意图类型无 L3 等价物，
 * 待对接外部数据源后启用）。
 * reason: 汇总相关对话历史，生成准备建议，自动注入画像/经验。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ContextPrepBehavior extends AbstractLlmBehavior {

    private static final Logger log = LoggerFactory.getLogger(ContextPrepBehavior.class);
    private static final String PROMPT_KEY = "generation/proactive-context-prep";

    @Nullable private final ProactiveMemoryBridge memoryBridge;
    @Nullable private final AgentConfigProperties config;

    public ContextPrepBehavior(@Nullable ProactiveMemoryBridge memoryBridge,
                               @Nullable GenerationRouter generationRouter,
                               @Nullable PromptRegistry promptRegistry,
                               @Nullable AgentConfigProperties config) {
        super(generationRouter, promptRegistry);
        this.memoryBridge = memoryBridge;
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

    /**
     * 检测需要提前准备的情境。
     *
     * <p>当前版本无法区分条件型/习惯型目标（L3 GOAL 实体不区分 IntentType），
     * 且真实条件评估（价格 API、日程 API）尚未对接。
     * 返回空列表，避免产生无依据的推送。后续迭代启用。</p>
     */
    @Override
    public List<ProactiveCandidate> detect(ContextPacket ctx) {
        // TODO: 对接外部数据源 + L3 实体属性扩展后启用
        return List.of();
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
