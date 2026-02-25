package com.lifepilot.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统配置属性。
 *
 * <p>绑定 {@code lifepilot.memory} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.memory")
public class MemoryProperties {

    /** 记忆系统总开关，默认 true。 */
    private boolean enabled = true;

    /** L1 工作记忆 Token 预算上限，默认 8000。 */
    private int workingMemoryTokenBudget = 8000;

    /** 会话空闲超时（分钟），超时后自动 flush，默认 30。 */
    private int idleSessionTimeoutMinutes = 30;

    /** 触发压缩的 Token 阈值，默认 4000。 */
    private int compressionThresholdTokens = 4000;

    /** 记忆巩固回溯天数，默认 7。 */
    private int consolidationLookbackDays = 7;

    /** 遗忘阈值 [0.0, 1.0]，低于此值的记忆将被遗忘，默认 0.7。 */
    private double forgettingThreshold = 0.7;

    /** 最大保留天数，超过后自动归档，默认 180。 */
    private int maxRetentionDays = 180;

    /** sqlite-vec 向量维度，默认 1024。 */
    private int embeddingDimensions = 1024;

    /** 冲突检测语义匹配阈值 [0.0, 1.0]，默认 0.92。 */
    private float semanticMatchThreshold = 0.92f;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkingMemoryTokenBudget() { return workingMemoryTokenBudget; }
    public void setWorkingMemoryTokenBudget(int workingMemoryTokenBudget) { this.workingMemoryTokenBudget = workingMemoryTokenBudget; }

    public int getIdleSessionTimeoutMinutes() { return idleSessionTimeoutMinutes; }
    public void setIdleSessionTimeoutMinutes(int idleSessionTimeoutMinutes) { this.idleSessionTimeoutMinutes = idleSessionTimeoutMinutes; }

    public int getCompressionThresholdTokens() { return compressionThresholdTokens; }
    public void setCompressionThresholdTokens(int compressionThresholdTokens) { this.compressionThresholdTokens = compressionThresholdTokens; }

    public int getConsolidationLookbackDays() { return consolidationLookbackDays; }
    public void setConsolidationLookbackDays(int consolidationLookbackDays) { this.consolidationLookbackDays = consolidationLookbackDays; }

    public double getForgettingThreshold() { return forgettingThreshold; }
    public void setForgettingThreshold(double forgettingThreshold) { this.forgettingThreshold = forgettingThreshold; }

    public int getMaxRetentionDays() { return maxRetentionDays; }
    public void setMaxRetentionDays(int maxRetentionDays) { this.maxRetentionDays = maxRetentionDays; }

    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }

    public float getSemanticMatchThreshold() { return semanticMatchThreshold; }
    public void setSemanticMatchThreshold(float semanticMatchThreshold) { this.semanticMatchThreshold = semanticMatchThreshold; }

    /** Token 预算分配配置。 */
    private TokenBudget tokenBudget = new TokenBudget();

    public TokenBudget getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(TokenBudget tokenBudget) { this.tokenBudget = tokenBudget; }

    /**
     * Token 预算分配配置 — 控制上下文窗口四区域的预算比例和场景切换阈值。
     *
     * @author zsg
     * @since 2026-02-25
     */
    public static class TokenBudget {

        /** 系统提示词区固定比例，默认 0.10。 */
        private float systemPromptRatio = 0.10f;

        /** 用户消息区固定比例，默认 0.15。 */
        private float userMessageRatio = 0.15f;

        /** 高相关度场景的工作记忆比例（占 75% 中的份额），默认 40。 */
        private float highRelevanceWorkingMemory = 40.0f;

        /** 高相关度场景的检索比例，默认 35。 */
        private float highRelevanceRetrieval = 35.0f;

        /** 长对话场景的工作记忆比例，默认 60。 */
        private float longConversationWorkingMemory = 60.0f;

        /** 长对话场景的检索比例，默认 15。 */
        private float longConversationRetrieval = 15.0f;

        /** 默认场景的工作记忆比例，默认 50。 */
        private float defaultWorkingMemory = 50.0f;

        /** 默认场景的检索比例，默认 25。 */
        private float defaultRetrieval = 25.0f;

        /** 高相关度判断阈值，默认 0.9。 */
        private float highRelevanceThreshold = 0.9f;

        /** 长对话轮次判断阈值，默认 10。 */
        private int longConversationTurnsThreshold = 10;

        public float getSystemPromptRatio() { return systemPromptRatio; }
        public void setSystemPromptRatio(float systemPromptRatio) { this.systemPromptRatio = systemPromptRatio; }

        public float getUserMessageRatio() { return userMessageRatio; }
        public void setUserMessageRatio(float userMessageRatio) { this.userMessageRatio = userMessageRatio; }

        public float getHighRelevanceWorkingMemory() { return highRelevanceWorkingMemory; }
        public void setHighRelevanceWorkingMemory(float highRelevanceWorkingMemory) { this.highRelevanceWorkingMemory = highRelevanceWorkingMemory; }

        public float getHighRelevanceRetrieval() { return highRelevanceRetrieval; }
        public void setHighRelevanceRetrieval(float highRelevanceRetrieval) { this.highRelevanceRetrieval = highRelevanceRetrieval; }

        public float getLongConversationWorkingMemory() { return longConversationWorkingMemory; }
        public void setLongConversationWorkingMemory(float longConversationWorkingMemory) { this.longConversationWorkingMemory = longConversationWorkingMemory; }

        public float getLongConversationRetrieval() { return longConversationRetrieval; }
        public void setLongConversationRetrieval(float longConversationRetrieval) { this.longConversationRetrieval = longConversationRetrieval; }

        public float getDefaultWorkingMemory() { return defaultWorkingMemory; }
        public void setDefaultWorkingMemory(float defaultWorkingMemory) { this.defaultWorkingMemory = defaultWorkingMemory; }

        public float getDefaultRetrieval() { return defaultRetrieval; }
        public void setDefaultRetrieval(float defaultRetrieval) { this.defaultRetrieval = defaultRetrieval; }

        public float getHighRelevanceThreshold() { return highRelevanceThreshold; }
        public void setHighRelevanceThreshold(float highRelevanceThreshold) { this.highRelevanceThreshold = highRelevanceThreshold; }

        public int getLongConversationTurnsThreshold() { return longConversationTurnsThreshold; }
        public void setLongConversationTurnsThreshold(int longConversationTurnsThreshold) { this.longConversationTurnsThreshold = longConversationTurnsThreshold; }
    }
}
