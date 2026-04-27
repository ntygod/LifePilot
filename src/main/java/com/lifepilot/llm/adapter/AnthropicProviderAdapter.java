package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;

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
}
