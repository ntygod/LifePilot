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
    /** DeepSeek API */
    DEEPSEEK("deepseek"),
    /** 百度文心一言 API */
    WENXIN("wenxin"),
    /** 阿里通义千问 API */
    QWEN("qwen"),
    /** 智谱 GLM API */
    GLM("glm"),
    /** OpenAI 兼容 API */
    OPENAI_COMPATIBLE("openai-compatible");

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
     * @return DEEPSEEK、QWEN、GLM、OPENAI_COMPATIBLE 返回 true
     */
    public boolean isOpenAiCompatible() {
        return switch (this) {
            case DEEPSEEK, QWEN, GLM, OPENAI_COMPATIBLE -> true;
            case OLLAMA, WENXIN -> false;
        };
    }
}
