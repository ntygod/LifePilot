package com.lifepilot.memory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 记忆系统配置属性。
 *
 * <p>绑定 {@code lifepilot.memory} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory")
public class MemoryProperties {

    /** 记忆系统总开关，默认 true。 */
    private boolean enabled = true;

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

    /** 向量数据库 busy_timeout（毫秒），默认 30000。 */
    private int busyTimeoutMs = 30000;

    /** L4 程序记忆配置。 */
    private Procedural procedural = new Procedural();

    /** 巩固管线配置。 */
    private Consolidation consolidation = new Consolidation();

    /** 遗忘引擎配置。 */
    private Forgetting forgetting = new Forgetting();

    /** 实体提取配置。 */
    private Extraction extraction = new Extraction();

    /** 检索配置。 */
    private Retrieval retrieval = new Retrieval();

    /** L3.5 热记忆摘要配置。 */
    private HotDigest hotDigest = new HotDigest();

    /** 反馈闭环配置。 */
    private Feedback feedback = new Feedback();

    /** 记忆精排配置。 */
    private Reranker reranker = new Reranker();

    /** 对话压缩配置。 */
    private Compression compression = new Compression();

    /** Agentic Tool 配置 — 控制记忆 tool 的默认检索参数。 */
    private AgenticTool agenticTool = new AgenticTool();

    /** L2 情景记忆自动清理配置。 */
    private EpisodicCleanup episodicCleanup = new EpisodicCleanup();

    /** 记忆老化与邻居回链配置（memory-staleness spec）。 */
    private Staleness staleness = new Staleness();

    /** 经验总结配置。 */
    private Experience experience = new Experience();

    /** REM 式联想巩固配置（memory-rem-consolidation spec）。 */
    private Rem rem = new Rem();

    /** 检索编排层配置（retrieval-orchestrator spec）。 */
    private RetrievalOrchestrator retrievalOrchestrator = new RetrievalOrchestrator();

    /** 记忆安全加固配置（memory-security-polish spec）。 */
    private Security security = new Security();

    /** 记忆 MCP Server 配置（memory-mcp-server spec）。 */
    private McpServer mcpServer = new McpServer();

    /**
     * L4 程序记忆配置 — 控制操作模板的可靠性判断、过时淘汰和意图匹配阈值。
     *
     * @author zsg
     * @since 2026-02-28
     */
    @Setter
    @Getter
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

        /** 是否启用操作模板聚类，默认启用。 */
        private boolean templateEnabled = true;

    }

    /**
     * 巩固管线配置 — 控制定时调度、增量窗口、频率阈值、聚类参数和模板提炼限制。
     *
     * @author zsg
     * @since 2026-02-28
     */
    @Setter
    @Getter
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

        /** 去重定时 Cron 表达式，默认每日凌晨 4:30。 */
        private String dedupCron = "0 30 4 * * *";

        /** 去重语义相似度阈值 [0.0, 1.0]，默认 0.90。 */
        private float dedupSimilarityThreshold = 0.90f;

        /** 每次去重最大合并数，默认 50。 */
        private int maxDedupPerRun = 50;

        /** 短名称阈值（字符数），纯英文名称长度 ≤ 此值时强制词边界匹配，默认 2。 */
        private int shortNameThreshold = 2;

        /** 空闲触发阈值（分钟），默认 30。 */
        private int idleThresholdMinutes = 30;

        /** 空闲触发冷却期（分钟），默认 60。 */
        private int idleCooldownMinutes = 60;

        /** 经验提升最低重要度阈值 [0.0, 1.0]，默认 0.8。 */
        private float experiencePromoteMinImportance = 0.8f;

        /** 经验提升最低访问次数，默认 3。 */
        private int experiencePromoteMinAccessCount = 3;

    }

    /**
     * 遗忘引擎配置 — 控制 MaRS Hybrid 四阶段遗忘的调度、策略参数和安全限制。
     *
     * @author zsg
     * @since 2026-02-28
     */
    @Setter
    @Getter
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

        /** 受保护的重要度阈值 — importanceScore ≥ 此值的实体永不被遗忘，默认 0.9。 */
        private float protectionThreshold = 0.9f;

        /** 受保护的实体类型列表 — 这些类型的实体永不被遗忘，默认 PREFERENCE/HABIT/GOAL。 */
        private Set<String> protectedTypes = Set.of("PREFERENCE", "HABIT", "GOAL");

        /** 近期访问保护天数 — 在此天数内被访问过的实体受保护，默认 7 天。 */
        private int recentAccessProtectionDays = 7;

        /** 高频访问保护阈值 — accessCount ≥ 此值的实体受保护，默认 10。 */
        private int highAccessCountProtection = 10;

    }

    /**
     * 实体提取配置 — 控制 RealtimeExtractor AUDN 提取的超时等参数。
     *
     * @author zsg
     * @since 2026-03-10
     */
    @Setter
    @Getter
    public static class Extraction {

        /** AUDN 实体提取 LLM 调用超时（秒），默认 60。 */
        private int timeoutSeconds = 60;

        /** 单次提取最大实体数，默认 10。 */
        private int maxEntitiesPerExtraction = 10;

        /** 最小提取置信度阈值 [0.0, 1.0]，低于此值的决策将被丢弃，默认 0.3。 */
        private float minExtractionConfidence = 0.3f;

        /** 实体名称最大长度，默认 100。 */
        private int maxEntityNameLength = 100;

        /** 描述最小长度，ADD 类型决策的 description 低于此值将被丢弃，默认 2。 */
        private int minDescriptionLength = 2;

        /** 注入提示词的已有实体摘要上限，默认 50。 */
        private int existingEntitySummaryLimit = 50;

    }

    /**
     * 检索配置 — 控制混合检索结果的相关性过滤参数。
     *
     * @author zsg
     * @since 2026-03-15
     */
    @Setter
    @Getter
    public static class Retrieval {

        /** RRF 融合分数最低阈值，低于此值的检索结果将被过滤。0.0 表示不过滤，默认 0.08。 */
        private float minFusedScore = 0.08f;

        /** 查询精炼后最大长度（字符数），超过则截断。 */
        private int queryMaxLength = 100;

        /** 查询最短长度（字符数），短于此值跳过精炼直接返回原文。 */
        private int queryMinLength = 10;

        /** 跨会话 BM25 搜索最低分数阈值，低于此值的结果被过滤。 */
        private float minCrossSessionBm25Score = 1.0f;

        /** 检索质量低分阈值，topScore 低于此值时触发预算缩减。 */
        private float lowQualityScoreThreshold = 0.3f;

        /** 低质量检索时知识实体预算缩减比例（0~1）。 */
        private float lowQualityBudgetRatio = 0.5f;

        /** 时间衰减率 — 每天衰减的比例（线性衰减）。 */
        private float timeDecayRate = 0.002f;

        /** 时间衰减因子最小值 — 防止老实体完全被忽略。 */
        private float minTimeDecayFactor = 0.5f;

        /** 跨会话消息语义相似度最低阈值 [0.0, 1.0]，默认 0.45。 */
        private float minCrossSessionSemanticScore = 0.45f;

        /** 查询改写模式：rewrite / hyde / none，默认 none。 */
        private String queryRewriteMode = "none";

        /** rewrite 模式最大改写变体数，默认 3。 */
        private int maxRewrites = 3;

        /** 查询改写 LLM 调用超时（毫秒），默认 5000。 */
        private int rewriteTimeoutMs = 5000;

        /** 向量检索最低相似度阈值 [0.0, 1.0]，默认 0.15。 */
        private float minVectorSimilarity = 0.15f;

        /** 话题切换检测余弦相似度阈值 [0.0, 1.0]，默认 0.3。 */
        private float topicSwitchThreshold = 0.3f;

        /** 检索排序中 trust_score 的加成权重，默认 0.08。 */
        private float trustScoreBoostWeight = 0.08f;

        /** REGENERATION_NEEDED 结果的生命周期扣分，默认 0.12。 */
        private float staleLifecyclePenalty = 0.12f;

        /** COMPLETED 历史结果的生命周期扣分，默认 0.02。 */
        private float historicalLifecyclePenalty = 0.02f;

    }

    /**
     * L3.5 热记忆摘要配置。
     *
     * <p>控制从 L3 可消费实体派生的小预算 Prompt 摘要。当前实现按需构建快照，
     * 不额外持久化事实；所有内容必须仍可追溯到 source entity。</p>
     *
     * @author zsg
     * @since 2026-05-05
     */
    @Setter
    @Getter
    public static class HotDigest {

        /** 是否启用热记忆摘要，默认 true。 */
        private boolean enabled = true;

        /** 用户画像摘要 token 预算。 */
        private int userProfileTokenBudget = 500;

        /** 项目记忆摘要 token 预算。 */
        private int projectMemoryTokenBudget = 400;

        /** 任务级经验摘要 token 预算。 */
        private int experienceTokenBudget = 500;

        /** 事实摘要 token 预算。 */
        private int factsTokenBudget = 400;

        /** 用户画像最多条目数。 */
        private int userProfileMaxEntries = 6;

        /** 项目记忆最多条目数。 */
        private int projectMemoryMaxEntries = 4;

        /** 经验最多条目数。 */
        private int experienceMaxEntries = 3;

        /** 事实最多条目数。 */
        private int factsMaxEntries = 4;

    }

    /**
     * 反馈闭环配置 — 控制用户反馈对 importanceScore 的调整幅度和过期归档调度。
     *
     * @author zsg
     * @since 2026-03-13
     */
    @Setter
    @Getter
    public static class Feedback {

        /** like 反馈的 importanceScore 正向调整步长。 */
        private float likeBoost = 0.1f;

        /** dislike 反馈的 importanceScore 负向调整步长。 */
        private float dislikePenalty = 0.05f;

        /** 过期实体归档定时任务 Cron 表达式（默认每小时执行一次）。 */
        private String expirationCron = "0 0 * * * *";

    }

    /**
     * 记忆精排配置 — 控制 HybridRetriever 中可选的 Reranker 精排步骤。
     *
     * @author zsg
     * @since 2026-03-15
     */
    @Setter
    @Getter
    public static class Reranker {

        /** 记忆精排强制关闭开关，默认 true（Reranker 可用时自动启用；设为 false 强制禁用）。 */
        private boolean enabled = true;

        /** 精排返回数量，默认 10。 */
        private int topK = 10;

    }

    /**
     * 对话压缩配置 — 控制压缩策略、滑动窗口大小和窗口重叠。
     *
     * @author zsg
     * @since 2026-03-15
     */
    @Setter
    @Getter
    public static class Compression {

        /** 压缩策略：whole / sliding-window，默认 sliding-window。 */
        private String strategy = "sliding-window";

        /** 滑动窗口大小（消息数），默认 20。 */
        private int windowSize = 20;

        /** 窗口重叠消息数，默认 2。 */
        private int windowOverlap = 2;

    }

    /**
     * Agentic Tool 配置 — 控制记忆 tool 的默认检索参数。
     *
     * @author zsg
     * @since 2026-03-18
     */
    @Setter
    @Getter
    public static class AgenticTool {
        /** 知识实体 / 跨会话检索默认返回数量。 */
        private int defaultTopK = 10;
        /** 知识库文档检索默认返回数量。 */
        private int docsDefaultTopK = 5;

    }

    /**
     * L2 情景记忆自动清理配置 — 控制过期对话记录的定时清理策略。
     *
     * @author zsg
     * @since 2026-03-18
     */
    @Setter
    @Getter
    public static class EpisodicCleanup {
        /** 清理 Cron 表达式，默认每日凌晨 5:00。 */
        private String cron = "0 0 5 * * *";
        /** 保留天数，默认 90。 */
        private int retentionDays = 90;
        /** 单次最大清理数量，默认 500。 */
        private int maxCleanupPerRun = 500;

    }

    /**
     * 记忆老化与邻居回链配置。
     *
     * <p>控制 {@code StalenessCoordinator} 在新事实写入后自动识别并标记"可能过时"的
     * 邻居实体，把它们从 ACTIVE 迁入 STALE_CANDIDATE 生命周期态，召回时显著降权。</p>
     *
     * @author zsg
     * @since 2026-05-09
     */
    @Setter
    @Getter
    public static class Staleness {
        /** 总开关，默认启用。 */
        private boolean enabled = true;

        /** 邻居识别的最低语义相似度。 */
        private float detectionSimilarityThreshold = 0.85f;

        /** 单次检测最多标记的邻居数。 */
        private int maxNeighborsPerDetection = 3;

        /** 允许触发 staleness 检测的实体类型白名单。 */
        private Set<String> detectableTypes = Set.of("PREFERENCE", "HABIT", "LOCATION", "GOAL");

        /** 召回时对 STALE_CANDIDATE 应用的分数惩罚比例（0~1）。默认 0.35，得分乘 0.65。 */
        private float retrievalPenalty = 0.35f;

        /**
         * 是否启用邻居刷新候选写入（本 spec 首版默认关，等消费侧就绪后再开）。
         */
        private boolean neighborRefreshEnabled = false;
    }

    /**
     * 经验总结配置 — 控制 Agent 经验提炼、存储、检索注入和 Eval 集成的参数。
     *
     * @author zsg
     * @since 2026-03-18
     */
    @Setter
    @Getter
    public static class Experience {
        /** 经验总结总开关，默认 true。 */
        private boolean enabled = true;
        /** LLM 输入截断上限（Token），默认 4000。 */
        private int maxInputTokens = 4000;
        /** 去重语义相似度阈值 [0.0, 1.0]，默认 0.90。 */
        private float dedupSimilarityThreshold = 0.90f;
        /** 经验最大保留天数，默认 90。 */
        private int maxRetentionDays = 90;
        /** LLM 调用超时（秒），默认 120。 */
        private int llmTimeoutSeconds = 120;
        /** 工具调用有效率门控阈值 [0.0, 1.0]，默认 0.3。 */
        private float minToolSuccessRatio = 0.3f;

        /** 效果反馈配置。 */
        private Effectiveness effectiveness = new Effectiveness();
        /** 对比学习配置。 */
        private Contrastive contrastive = new Contrastive();
        /** 执行上下文隔离配置。 */
        private Isolation isolation = new Isolation();
        /** 经验合并配置。 */
        private Merge merge = new Merge();
        /** 子任务反思配置。 */
        private Subtask subtask = new Subtask();

        /**
         * 效果反馈配置 — 控制经验注入后的有效性评估和 importanceScore 动态调整。
         *
         * @author zsg
         * @since 2026-03-18
         */
        @Setter
        @Getter
        public static class Effectiveness {
            /** 效果判定的工具调用有效率阈值，默认 0.5。 */
            private float successRatioThreshold = 0.5f;
            /** 有效经验 importanceScore 提升步长，默认 0.05。 */
            private float positiveBoost = 0.05f;
            /** 无效经验 importanceScore 衰减步长，默认 0.03。 */
            private float negativeDecay = 0.03f;
            /** 淘汰阈值，importanceScore 低于此值时归档，默认 0.1。 */
            private float evictionThreshold = 0.1f;

        }

        /**
         * 对比学习配置 — 控制成功/失败轨迹对比分析的触发条件和参数。
         *
         * @author zsg
         * @since 2026-03-18
         */
        public static class Contrastive {
            /** 开关，默认 true。 */
            private boolean enabled = true;
            /** 轨迹对匹配相似度阈值，默认 0.80。 */
            private float similarityThreshold = 0.80f;
            /** 对比经验初始 importanceScore，默认 0.7。 */
            private float initialImportance = 0.7f;
            /** LLM 调用超时（秒），默认 30。 */
            private int llmTimeoutSeconds = 30;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public float getSimilarityThreshold() { return similarityThreshold; }
            public void setSimilarityThreshold(float similarityThreshold) { this.similarityThreshold = similarityThreshold; }

            public float getInitialImportance() { return initialImportance; }
            public void setInitialImportance(float initialImportance) { this.initialImportance = initialImportance; }

            public int getLlmTimeoutSeconds() { return llmTimeoutSeconds; }
            public void setLlmTimeoutSeconds(int llmTimeoutSeconds) { this.llmTimeoutSeconds = llmTimeoutSeconds; }
        }

        /**
         * 执行上下文隔离配置 — 控制不同执行环境的经验是否可跨上下文检索。
         *
         * @author zsg
         * @since 2026-03-18
         */
        @Setter
        @Getter
        public static class Isolation {
            /** 是否允许跨上下文检索，默认 false。 */
            private boolean crossContextRetrieval = false;

        }

        /**
         * 经验合并配置 — 控制相似经验的自动合并策略和限流参数。
         *
         * @author zsg
         * @since 2026-03-18
         */
        @Setter
        @Getter
        public static class Merge {
            /** 开关，默认 true。 */
            private boolean enabled = true;
            /** 合并相似度阈值，默认 0.85。 */
            private float similarityThreshold = 0.85f;
            /** 每次巩固最大合并数，默认 10。 */
            private int maxMergesPerRun = 10;
            /** LLM 调用超时（秒），默认 30。 */
            private int llmTimeoutSeconds = 30;

        }

        /**
         * 子任务反思配置 — 控制从工具调用序列中提取细粒度经验的触发条件和参数。
         *
         * @author zsg
         * @since 2026-03-18
         */
        @Setter
        @Getter
        public static class Subtask {
            /** 开关，默认 true。 */
            private boolean enabled = true;
            /** 触发反思的最小连续工具调用数，默认 3。 */
            private int minToolSequence = 3;
            /** 子任务经验初始 importanceScore，默认 0.4。 */
            private float initialImportance = 0.4f;
            /** LLM 输入截断上限（Token），默认 2000。 */
            private int maxInputTokens = 2000;
            /** LLM 调用超时（秒），默认 120。 */
            private int llmTimeoutSeconds = 120;

        }
    }

    /**
     * REM 式联想巩固配置 — 控制巩固管线中对 L3 高 importance 实体做跨实体联想，
     * 通过 LLM 推断潜在语义关系并写入文件审计（不直改 L3 relations 主库）。
     *
     * @author zsg
     * @since 2026-05-09
     */
    @Setter
    @Getter
    public static class Rem {
        /** 总开关，默认关闭（等稳定后再启用）。 */
        private boolean enabled = false;
        /** 选择的 seed 实体数量上限。 */
        private int seedLimit = 10;
        /** 每个 seed 的邻居数量上限。 */
        private int neighborLimit = 5;
        /** 允许作为 seed 的实体类型。 */
        private Set<String> seedTypes = Set.of("GOAL", "TOPIC", "PROJECT");
        /** 最小置信度阈值；低于此值不入库。 */
        private float minConfidence = 0.65f;
        /** LLM 调用超时（秒）。 */
        private int llmTimeoutSeconds = 20;
        /** 同 (source, target, type) 去重窗口（小时）。 */
        private int deduplicationWindowHours = 24;
    }

    /**
     * 检索编排层配置 — 控制 {@code RetrievalOrchestrator} 的 topK 与开关。
     *
     * @author zsg
     * @since 2026-05-09
     */
    @Setter
    @Getter
    public static class RetrievalOrchestrator {
        /** 总开关，默认关闭（作为上层可选接口）。 */
        private boolean enabled = false;
        /** 默认单次检索返回的结果上限。 */
        private int defaultTopK = 10;
        /** 每个 source 单独调用的 topK。 */
        private int perSourceTopK = 5;
    }

    /**
     * 记忆安全配置 — 控制 prompt injection / trust-outlier 检测。
     *
     * @author zsg
     * @since 2026-05-09
     */
    @Setter
    @Getter
    public static class Security {
        /** 注入检测总开关，默认关闭（先集成再打开）。 */
        private boolean injectionDetectionEnabled = false;
        /** Mahalanobis 距离异常阈值。 */
        private float outlierThreshold = 3.0f;
        /** 每 space 样本窗口大小。 */
        private int sampleWindowSize = 1000;
        /** true 时 SUSPICIOUS 也当作 BLOCKED 处理。 */
        private boolean blockOnSuspicious = false;
    }

    /**
     * 记忆 MCP Server 配置 — 把知微记忆暴露为 MCP server 供外部 Agent 调用。
     *
     * @author zsg
     * @since 2026-05-09
     */
    @Setter
    @Getter
    public static class McpServer {
        /** 总开关，默认关闭（本地环境可选）。 */
        private boolean enabled = false;
        /** Server 名称。 */
        private String serverName = "zhiwei-memory";
        /** Server 版本。 */
        private String serverVersion = "1.0.0";
    }

}
