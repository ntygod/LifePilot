package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Ollama 本地适配器 — 走 OllamaChatModel 路径，本地推理无 thinking 协议。
 *
 * <p>Phase 3 阶段仅保留构造器，行为复用 {@link AbstractProviderAdapter}；本地模型
 * 默认无 thinking 协议（profile.thinkingProtocol = NONE），不持有 ThinkingProtocol 实例。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OllamaProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;

    public OllamaProviderAdapter(ProviderConfig config,
                                 ChatModel chatModel,
                                 @Nullable EmbeddingModel embeddingModel,
                                 @Nullable List<CallAdvisor> defaultAdvisors,
                                 ProviderProfile profile) {
        super(config, chatModel, embeddingModel, defaultAdvisors);
        this.profile = profile;
    }

    public ProviderProfile profile() {
        return profile;
    }
}
