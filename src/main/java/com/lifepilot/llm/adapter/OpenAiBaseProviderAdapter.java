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
 * <p>Phase 3 / Phase 4 阶段子类仅保留构造器，行为完全复用基类实现（含 streamEvents 默认实现）；
 * Phase 10 起按 thinkingProtocol 把原始 SSE chunk 解析为带 ReasoningChunk 的 LlmStreamEvent 流时再重写。
 *
 * <p><b>永远是 concrete class，不要改成 abstract</b>：本类既是 DeepSeek / Qwen /
 * OpenAiOfficial 三个子类的共享基类，<b>也是 NONE 协议（zhipu / moonshot / minimax /
 * siliconflow）provider 的 fallback adapter</b>。如果改成 abstract 会让 NONE 协议
 * provider 失去可用 adapter，导致 ProviderAdapterFactory 路由失败。
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

    // streamEvents 复用 AbstractProviderAdapter 默认实现（chatModel.stream + chunkToEvents）。
    // Phase 10 起按 ThinkingProtocol.extractReasoning 解析原始 SSE chunk 时再重写本方法注入 ReasoningChunk。
}
