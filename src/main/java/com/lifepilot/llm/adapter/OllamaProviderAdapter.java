package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Ollama 本地适配器 — 走 OllamaChatModel 路径，本地推理无 thinking 协议。
 *
 * <p>Phase 3 阶段仅保留构造器，行为复用 {@link AbstractProviderAdapter}；本地模型
 * 默认无 thinking 协议（profile.thinkingProtocol = NONE），但构造器仍接受
 * {@link ThinkingProtocol} 参数（持 {@link com.lifepilot.llm.thinking.NoopThinkingProtocol}
 * 实例），与其他 4 个子类签名对齐。Phase 5+ 不需要再改 OllamaProviderAdapter
 * 构造器签名做连锁修改。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OllamaProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;
    protected final ThinkingProtocol thinkingProtocol;

    public OllamaProviderAdapter(ProviderConfig config,
                                 ChatModel chatModel,
                                 @Nullable EmbeddingModel embeddingModel,
                                 @Nullable List<CallAdvisor> defaultAdvisors,
                                 ProviderProfile profile,
                                 ThinkingProtocol thinkingProtocol) {
        super(config, profile.baseAdapter(), chatModel, embeddingModel, defaultAdvisors);
        this.profile = profile;
        this.thinkingProtocol = thinkingProtocol;
    }

    public ProviderProfile profile() {
        return profile;
    }

    public ThinkingProtocol thinkingProtocol() {
        return thinkingProtocol;
    }

    // streamEvents 复用 AbstractProviderAdapter 默认实现。
    // 本地推理无 thinking 协议（profile.thinkingProtocol = NONE），不发 ReasoningChunk。
}
