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
 * OpenAI 兼容 Adapter 基类 — 共享 OpenAI SDK 路径。
 *
 * <p>承载 DeepSeek / Qwen / OpenAI 官方 / 智谱 / 月之暗面 / 火山 / MiniMax / 硅基流动等
 * provider 的共同行为（同一条 OpenAiChatModel 路径）。子类按需重写 ChatOptions 构造、
 * 特殊字段注入、协议私有错误码处理。
 *
 * <p>Phase 3 阶段子类仅保留构造器，行为复用基类实现；Phase 4 起将引入
 * {@code streamEvents()} 抽象方法，由本类按 thinkingProtocol 把 ChatResponse 流分派为
 * LlmStreamEvent 流。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OpenAiBaseProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;
    protected final ThinkingProtocol thinkingProtocol;

    public OpenAiBaseProviderAdapter(ProviderConfig config,
                                     ChatModel chatModel,
                                     @Nullable EmbeddingModel embeddingModel,
                                     @Nullable List<CallAdvisor> defaultAdvisors,
                                     ProviderProfile profile,
                                     ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, embeddingModel, defaultAdvisors);
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
