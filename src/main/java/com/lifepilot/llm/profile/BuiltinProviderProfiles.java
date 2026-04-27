package com.lifepilot.llm.profile;

import com.lifepilot.llm.config.ProviderCapability;

import java.util.List;
import java.util.Set;

/**
 * 内置 ProviderProfile 列表 — 项目随版本演进维护，不进 DB。
 *
 * <p>11 个内置 profile 覆盖主流 provider。自部署兼容服务（vLLM / LM Studio /
 * Xinference）由用户挑最接近的 profile + 改 baseUrl，不需单独 profile。
 *
 * @author zsg
 * @since 2026-04-27
 */
public final class BuiltinProviderProfiles {

    private BuiltinProviderProfiles() {
    }

    public static final ProviderProfile DEEPSEEK_OFFICIAL = new ProviderProfile(
            "deepseek-official",
            "DeepSeek 官方",
            BaseAdapterType.OPENAI_BASE,
            "https://api.deepseek.com",
            ThinkingProtocolId.DEEPSEEK,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            // 保守策略：始终注入 reasoning_content，缺则空字符串占位 — 满足 DeepSeek 多轮契约。
            // 理论上「仅 tool_call 场景注入」(deepseekContentOnlyReasoning) 更精确符合官方文档，
            // 但当前 LlmResponse.toolCalls 尚未 wiring 进 transcript payload_json（详见
            // memory/project_reasoning_content_wiring_gap.md），ChatHistoryAssembler
            // 永远拿不到 tool_calls，会错走「无 tool_call 不注入」分支导致第 3 轮 400。
            // 待真实持久化 wiring 完成后再切回 deepseekContentOnlyReasoning()。
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile QWEN_DASHSCOPE = new ProviderProfile(
            "qwen-dashscope",
            "通义千问 (DashScope)",
            BaseAdapterType.OPENAI_BASE,
            "https://dashscope.aliyuncs.com/compatible-mode",
            ThinkingProtocolId.QWEN,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.DASHSCOPE_EXPLICIT,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile OPENAI_OFFICIAL = new ProviderProfile(
            "openai-official",
            "OpenAI 官方",
            BaseAdapterType.OPENAI_BASE,
            "https://api.openai.com",
            ThinkingProtocolId.OPENAI_REASONING_EFFORT,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.OPENAI_AUTO,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING,
                    ProviderCapability.VISION, ProviderCapability.NATIVE_AUDIO),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile ANTHROPIC_OFFICIAL = new ProviderProfile(
            "anthropic-official",
            "Anthropic 官方",
            BaseAdapterType.ANTHROPIC_BASE,
            "https://api.anthropic.com",
            ThinkingProtocolId.ANTHROPIC,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.ANTHROPIC_EPHEMERAL,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
            MultiTurnHistoryRules.anthropicThinkingBlock()
    );

    public static final ProviderProfile OLLAMA_LOCAL = new ProviderProfile(
            "ollama-local",
            "Ollama 本地",
            BaseAdapterType.OLLAMA,
            "http://localhost:11434",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.PROMPT_ONLY,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.ollama(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile TEI_LOCAL = new ProviderProfile(
            "tei-local",
            "HuggingFace TEI 本地",
            BaseAdapterType.TEI,
            "http://localhost:8080",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.PROMPT_ONLY,
            PromptCacheStrategyId.NOOP,
            new ModelDiscoveryEndpoint("/info", "X-Unused", "${apiKey}", "$.model_id", null),
            Set.of(ProviderCapability.EMBEDDING, ProviderCapability.RERANK),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile VOLCENGINE_ARK = new ProviderProfile(
            "volcengine-ark",
            "火山方舟 (Ark)",
            BaseAdapterType.OPENAI_BASE,
            "https://ark.cn-beijing.volces.com/api",
            ThinkingProtocolId.DEEPSEEK,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            // 同 DEEPSEEK_OFFICIAL 注释：tool_calls wiring 完成前先用保守策略，避免 400。
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile ZHIPU_BIGMODEL = new ProviderProfile(
            "zhipu-bigmodel",
            "智谱清言 (BigModel)",
            BaseAdapterType.OPENAI_BASE,
            "https://open.bigmodel.cn/api/paas",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile MOONSHOT_KIMI = new ProviderProfile(
            "moonshot-kimi",
            "月之暗面 Kimi",
            BaseAdapterType.OPENAI_BASE,
            "https://api.moonshot.cn",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile MINIMAX_TEXT = new ProviderProfile(
            "minimax-text",
            "MiniMax",
            BaseAdapterType.OPENAI_BASE,
            "https://api.minimax.chat",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile SILICONFLOW = new ProviderProfile(
            "siliconflow",
            "硅基流动 (SiliconFlow)",
            BaseAdapterType.OPENAI_BASE,
            "https://api.siliconflow.cn",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING, ProviderCapability.RERANK),
            MultiTurnHistoryRules.standard()
    );

    /**
     * 获取所有内置 profile。
     *
     * @return 不可变列表
     */
    public static List<ProviderProfile> all() {
        return List.of(
                DEEPSEEK_OFFICIAL,
                QWEN_DASHSCOPE,
                OPENAI_OFFICIAL,
                ANTHROPIC_OFFICIAL,
                OLLAMA_LOCAL,
                TEI_LOCAL,
                VOLCENGINE_ARK,
                ZHIPU_BIGMODEL,
                MOONSHOT_KIMI,
                MINIMAX_TEXT,
                SILICONFLOW
        );
    }
}
