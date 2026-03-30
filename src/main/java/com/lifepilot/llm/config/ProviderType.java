package com.lifepilot.llm.config;

/**
 * LLM Provider 类型枚举。
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum ProviderType {
    /** 本地 Ollama 服务 */
    OLLAMA("ollama"),
    /** HuggingFace Text Embeddings Inference（TEI） */
    TEI("tei"),
    /** OpenAI 兼容 API */
    OPENAI_COMPATIBLE("openai-compatible"),
    /** Anthropic Claude API */
    ANTHROPIC("anthropic");

    private final String configKey;

    ProviderType(String configKey) {
        this.configKey = configKey;
    }

    /**
     * 获取配置键名。
     *
     * @return 配置键
     */
    public String configKey() {
        return configKey;
    }

    /**
     * 是否使用 OpenAI 兼容 API。
     *
     * @return TEI、OPENAI_COMPATIBLE 返回 true
     */
    public boolean isOpenAiCompatible() {
        return switch (this) {
            case TEI, OPENAI_COMPATIBLE -> true;
            case OLLAMA, ANTHROPIC -> false;
        };
    }
}
