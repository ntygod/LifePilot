package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import com.lifepilot.modelservice.probe.ProbeModelsService;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * DeepSeek 适配器 — 复用 OpenAI SDK 路径，thinking 字段走 extra_body.thinking。
 *
 * <p>Phase 3 阶段仅保留构造器，行为复用 {@link OpenAiBaseProviderAdapter}；
 * Phase 4 由基类按 {@link ThinkingProtocol} 注入 thinking 字段并发出 LlmStreamEvent。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class DeepSeekProviderAdapter extends OpenAiBaseProviderAdapter {

    public DeepSeekProviderAdapter(ProviderConfig config,
                                   ChatModel chatModel,
                                   @Nullable EmbeddingModel embeddingModel,
                                   @Nullable List<CallAdvisor> defaultAdvisors,
                                   ProviderProfile profile,
                                   ThinkingProtocol thinkingProtocol,
                                   @Nullable ProbeModelsService probeModelsService) {
        super(config, chatModel, embeddingModel, defaultAdvisors, profile, thinkingProtocol,
                probeModelsService);
    }
}
