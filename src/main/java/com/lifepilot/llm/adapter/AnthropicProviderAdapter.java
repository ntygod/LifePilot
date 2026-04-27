package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.stream.LlmStreamEvent;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Anthropic 原生适配器 — Spring AI AnthropicChatModel 路径 + thinking block 协议。
 *
 * <p>不复用 OpenAI 基类（协议本质不同：content 数组、thinking block + signature）。
 * Phase 3 阶段仅保留构造器，行为复用 {@link AbstractProviderAdapter}；Phase 4 起
 * 将由本类按 {@link ThinkingProtocol} 注入 thinking 字段并发出 LlmStreamEvent。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AnthropicProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;
    protected final ThinkingProtocol thinkingProtocol;

    public AnthropicProviderAdapter(ProviderConfig config,
                                    ChatModel chatModel,
                                    @Nullable List<CallAdvisor> defaultAdvisors,
                                    ProviderProfile profile,
                                    ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, null, defaultAdvisors);
        this.profile = profile;
        this.thinkingProtocol = thinkingProtocol;
    }

    public ProviderProfile profile() {
        return profile;
    }

    public ThinkingProtocol thinkingProtocol() {
        return thinkingProtocol;
    }

    /**
     * Anthropic 路径的流式事件实现 — 简化版仅发 ContentChunk + UsageEvent。
     *
     * <p>Phase 4 简化版未解析 thinking block 与 signature；Phase 10 由
     * {@link ThinkingProtocol#extractReasoning} / {@link ThinkingProtocol#extractReasoningSignature}
     * 解析 thinking 块发出 ReasoningChunk。
     */
    @Override
    public Flux<LlmStreamEvent> streamEvents(Prompt prompt, List<ToolCallback> toolCallbacks) {
        return chatModel.stream(prompt).flatMap(this::chunkToEvents);
    }
}
