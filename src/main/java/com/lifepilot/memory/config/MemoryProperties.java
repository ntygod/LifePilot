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

    /** 向量数据库 JDBC URL，默认 jdbc:sqlite:${user.home}/.zhiwei/vectors.db。 */
    private String vectorDbUrl = "jdbc:sqlite:" + System.getProperty("user.home") + "/.zhiwei/vectors.db";

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

    public String getVectorDbUrl() { return vectorDbUrl; }
    public void setVectorDbUrl(String vectorDbUrl) { this.vectorDbUrl = vectorDbUrl; }

    /** Token 预算分配配置。 */
    private TokenBudget tokenBudget = new TokenBudget();

    /** L4 程序记忆配置。 */
    private Procedural procedural = new Procedural();

    /** 巩固管线配置。 */
    private Consolidation consolidation = new Consolidation();

    /** 遗忘引擎配置。 */
    private Forgetting forgetting = new Forgetting();

    /** 实体提取配置。 */
    private Extraction extraction = new Extraction();

    public TokenBudget getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(TokenBudget tokenBudget) { this.tokenBudget = tokenBudget; }

    public Procedural getProcedural() { return procedural; }
    public void setProcedural(Procedural procedural) { this.procedural = procedural; }

    public Consolidation getConsolidation() { return consolidation; }
    public void setConsolidation(Consolidation consolidation) { this.consolidation = consolidation; }

    public Forgetting getForgetting() { return forgetting; }
    public void setForgetting(Forgetting forgetting) { this.forgetting = forgetting; }

    public Extraction getExtraction() { return extraction; }
    public void setExtraction(Extraction extraction) { this.extraction = extraction; }

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

        /** 用户画像区域 Token 预算上限，默认 500。 */
        private int userProfileMax = 500;

        /** 当前会话历史区域 Token 预算上限，默认 4000。 */
        private int currentSessionMax = 4000;

        /** 跨会话摘要区域 Token 预算上限，默认 1000。 */
        private int crossSessionMax = 1000;

        /** 相关知识实体区域 Token 预算上限，默认 500。 */
        private int knowledgeEntityMax = 500;

        /** 操作模板区域 Token 预算上限，默认 300。 */
        private int proceduralMax = 300;

        /** 知识库片段区域 Token 预算上限，默认 500。 */
        private int knowledgeBaseMax = 500;

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

        public int getUserProfileMax() { return userProfileMax; }
        public void setUserProfileMax(int userProfileMax) { this.userProfileMax = userProfileMax; }

        public int getCurrentSessionMax() { return currentSessionMax; }
        public void setCurrentSessionMax(int currentSessionMax) { this.currentSessionMax = currentSessionMax; }

        public int getCrossSessionMax() { return crossSessionMax; }
        public void setCrossSessionMax(int crossSessionMax) { this.crossSessionMax = crossSessionMax; }

        public int getKnowledgeEntityMax() { return knowledgeEntityMax; }
        public void setKnowledgeEntityMax(int knowledgeEntityMax) { this.knowledgeEntityMax = knowledgeEntityMax; }

        public int getProceduralMax() { return proceduralMax; }
        public void setProceduralMax(int proceduralMax) { this.proceduralMax = proceduralMax; }

        public int getKnowledgeBaseMax() { return knowledgeBaseMax; }
        public void setKnowledgeBaseMax(int knowledgeBaseMax) { this.knowledgeBaseMax = knowledgeBaseMax; }
    }

    /**
     * L4 程序记忆配置 — 控制操作模板的可靠性判断、过时淘汰和意图匹配阈值。
     *
     * @author zsg
     * @since 2026-02-28
     */
    public static class Procedural {

        /** 最大模板数量，默认 200。 */
        private int maxTemplates = 200;

        /** 最低可靠性阈值 [0.0, 1.0]，默认 0.7。 */
        private float minReliability = 0.7f;

        /** 最低使用次数，默认 2。 */
        private int minUseCount = 2;

        /** 过时天数阈值，默认 90。 */
        private int staleDays = 90;

        /** 意图匹配融合评分阈值 [0.0, 1.0]，默认 0.6。 */
        private float matchThreshold = 0.6f;

        /** 新模板默认重要度 [0.0, 1.0]，默认 0.5。 */
        private float defaultImportance = 0.5f;

        public int getMaxTemplates() { return maxTemplates; }
        public void setMaxTemplates(int maxTemplates) { this.maxTemplates = maxTemplates; }

        public float getMinReliability() { return minReliability; }
        public void setMinReliability(float minReliability) { this.minReliability = minReliability; }

        public int getMinUseCount() { return minUseCount; }
        public void setMinUseCount(int minUseCount) { this.minUseCount = minUseCount; }

        public int getStaleDays() { return staleDays; }
        public void setStaleDays(int staleDays) { this.staleDays = staleDays; }

        public float getMatchThreshold() { return matchThreshold; }
        public void setMatchThreshold(float matchThreshold) { this.matchThreshold = matchThreshold; }

        public float getDefaultImportance() { return defaultImportance; }
        public void setDefaultImportance(float defaultImportance) { this.defaultImportance = defaultImportance; }
    }

    /**
     * 巩固管线配置 — 控制定时调度、增量窗口、频率阈值、聚类参数和模板提炼限制。
     *
     * @author zsg
     * @since 2026-02-28
     */
    public static class Consolidation {

        /** 巩固定时 Cron 表达式，默认每日凌晨 3:00。 */
        private String cron = "0 0 3 * * *";

        /** 触发模式：CRON / IDLE / HYBRID，默认 CRON。 */
        private String triggerMode = "CRON";

        /** 回溯天数，默认 7。 */
        private int lookbackDays = 7;

        /** 高频提及阈值，默认 3。 */
        private int highFrequencyThreshold = 3;

        /** importanceScore 单次提升步长，默认 0.1。 */
        private float importanceBoostStep = 0.1f;

        /** importanceScore 单次巩固最大提升量，默认 0.3。 */
        private float importanceBoostMax = 0.3f;

        /** 聚类余弦相似度阈值，默认 0.85。 */
        private float clusterSimilarityThreshold = 0.85f;

        /** 最小聚类大小，默认 2。 */
        private int minClusterSize = 2;

        /** 每次巩固最大新模板数，默认 10。 */
        private int maxTemplatesPerRun = 10;

        /** 最小执行步数过滤阈值，默认 2。 */
        private int minExecutionSteps = 2;

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }

        public String getTriggerMode() { return triggerMode; }
        public void setTriggerMode(String triggerMode) { this.triggerMode = triggerMode; }

        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }

        public int getHighFrequencyThreshold() { return highFrequencyThreshold; }
        public void setHighFrequencyThreshold(int highFrequencyThreshold) { this.highFrequencyThreshold = highFrequencyThreshold; }

        public float getImportanceBoostStep() { return importanceBoostStep; }
        public void setImportanceBoostStep(float importanceBoostStep) { this.importanceBoostStep = importanceBoostStep; }

        public float getImportanceBoostMax() { return importanceBoostMax; }
        public void setImportanceBoostMax(float importanceBoostMax) { this.importanceBoostMax = importanceBoostMax; }

        public float getClusterSimilarityThreshold() { return clusterSimilarityThreshold; }
        public void setClusterSimilarityThreshold(float clusterSimilarityThreshold) { this.clusterSimilarityThreshold = clusterSimilarityThreshold; }

        public int getMinClusterSize() { return minClusterSize; }
        public void setMinClusterSize(int minClusterSize) { this.minClusterSize = minClusterSize; }

        public int getMaxTemplatesPerRun() { return maxTemplatesPerRun; }
        public void setMaxTemplatesPerRun(int maxTemplatesPerRun) { this.maxTemplatesPerRun = maxTemplatesPerRun; }

        public int getMinExecutionSteps() { return minExecutionSteps; }
        public void setMinExecutionSteps(int minExecutionSteps) { this.minExecutionSteps = minExecutionSteps; }
    }

    /**
     * 遗忘引擎配置 — 控制 MaRS Hybrid 四阶段遗忘的调度、策略参数和安全限制。
     *
     * @author zsg
     * @since 2026-02-28
     */
    public static class Forgetting {

        /** 遗忘定时 Cron 表达式，默认每周日凌晨 4:00。 */
        private String cron = "0 0 4 * * SUN";

        /** 最大保留天数（FIFO 阈值），默认 365。 */
        private int maxRetentionDays = 365;

        /** LRU 未访问天数阈值，默认 90。 */
        private int lruThresholdDays = 90;

        /** Priority Decay 衰减率 λ，默认 0.02（半衰期约 35 天）。 */
        private float priorityDecayRate = 0.02f;

        /** Priority Decay 淘汰阈值，默认 0.2。 */
        private float priorityDecayThreshold = 0.2f;

        /** Reflection-Summary 最低重要度（含），默认 0.3。 */
        private float reflectionSummaryMinImportance = 0.3f;

        /** Reflection-Summary 最高重要度（不含），默认 0.8。 */
        private float reflectionSummaryMaxImportance = 0.8f;

        /** 每次遗忘最大数量，默认 100。 */
        private int maxForgetPerRun = 100;

        /** PII 实体遗忘优先级额外权重，默认 0.3。 */
        private float privacyAwareBoost = 0.3f;

        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }

        public int getMaxRetentionDays() { return maxRetentionDays; }
        public void setMaxRetentionDays(int maxRetentionDays) { this.maxRetentionDays = maxRetentionDays; }

        public int getLruThresholdDays() { return lruThresholdDays; }
        public void setLruThresholdDays(int lruThresholdDays) { this.lruThresholdDays = lruThresholdDays; }

        public float getPriorityDecayRate() { return priorityDecayRate; }
        public void setPriorityDecayRate(float priorityDecayRate) { this.priorityDecayRate = priorityDecayRate; }

        public float getPriorityDecayThreshold() { return priorityDecayThreshold; }
        public void setPriorityDecayThreshold(float priorityDecayThreshold) { this.priorityDecayThreshold = priorityDecayThreshold; }

        public float getReflectionSummaryMinImportance() { return reflectionSummaryMinImportance; }
        public void setReflectionSummaryMinImportance(float reflectionSummaryMinImportance) { this.reflectionSummaryMinImportance = reflectionSummaryMinImportance; }

        public float getReflectionSummaryMaxImportance() { return reflectionSummaryMaxImportance; }
        public void setReflectionSummaryMaxImportance(float reflectionSummaryMaxImportance) { this.reflectionSummaryMaxImportance = reflectionSummaryMaxImportance; }

        public int getMaxForgetPerRun() { return maxForgetPerRun; }
        public void setMaxForgetPerRun(int maxForgetPerRun) { this.maxForgetPerRun = maxForgetPerRun; }

        public float getPrivacyAwareBoost() { return privacyAwareBoost; }
        public void setPrivacyAwareBoost(float privacyAwareBoost) { this.privacyAwareBoost = privacyAwareBoost; }
    }

    /**
     * 实体提取配置 — 控制 RealtimeExtractor AUDN 提取的超时等参数。
     *
     * @author zsg
     * @since 2026-03-10
     */
    public static class Extraction {

        /** AUDN 实体提取 LLM 调用超时（秒），默认 60。 */
        private int timeoutSeconds = 60;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
