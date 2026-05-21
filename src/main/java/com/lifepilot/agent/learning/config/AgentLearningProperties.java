package com.lifepilot.agent.learning.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Agent 学习层配置属性。
 *
 * <p>绑定 {@code lifepilot.agent.learning} 配置前缀。涵盖巩固管线、遗忘引擎、
 * 实体提取、经验总结、反馈闭环、老化检测和 REM 联想巩固等学习相关配置。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.agent.learning")
public class AgentLearningProperties {

    // ─── Consolidation 巩固管线配置 ───

    /** 巩固管线配置。 */
    private Consolidation consolidation = new Consolidation();

    // ─── Forgetting 遗忘引擎配置 ───

    /** 遗忘引擎配置。 */
    private Forgetting forgetting = new Forgetting();

    // ─── Extraction 实体提取配置 ───

    /** 实体提取配置。 */
    private Extraction extraction = new Extraction();

    // ─── Experience 经验总结配置 ───

    /** 经验总结配置。 */
    private Experience experience = new Experience();

    // ─── Feedback 反馈闭环配置 ───

    /** 反馈闭环配置。 */
    private Feedback feedback = new Feedback();

    // ─── Staleness 老化检测配置 ───

    /** 记忆老化与邻居回链配置。 */
    private Staleness staleness = new Staleness();

    // ─── Rem 联想巩固配置 ───

    /** REM 式联想巩固配置。 */
    private Rem rem = new Rem();

    /**
     * 巩固管线配置 — 控制定时调度、增量窗口、频率阈值、聚类参数和模板提炼限制。
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

        /** 用户画像巩固 LLM 调用超时（秒），默认 120。 */
        private int userProfileLlmTimeoutSeconds = 120;

        /** 是否启用操作模板聚类（从 MemoryStoreProperties.Procedural 迁移），默认启用。 */
        private boolean proceduralTemplateEnabled = true;
    }

    /**
     * 遗忘引擎配置 — 控制 MaRS Hybrid 四阶段遗忘的调度、策略参数和安全限制。
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
     * 经验总结配置 — 控制 Agent 经验提炼、存储、检索注入和 Eval 集成的参数。
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
         */
        @Setter
        @Getter
        public static class Contrastive {

            /** 开关，默认 true。 */
            private boolean enabled = true;

            /** 轨迹对匹配相似度阈值，默认 0.80。 */
            private float similarityThreshold = 0.80f;

            /** 对比经验初始 importanceScore，默认 0.7。 */
            private float initialImportance = 0.7f;

            /** LLM 调用超时（秒），默认 30。 */
            private int llmTimeoutSeconds = 30;
        }

        /**
         * 执行上下文隔离配置 — 控制不同执行环境的经验是否可跨上下文检索。
         */
        @Setter
        @Getter
        public static class Isolation {

            /** 是否允许跨上下文检索，默认 false。 */
            private boolean crossContextRetrieval = false;
        }

        /**
         * 经验合并配置 — 控制相似经验的自动合并策略和限流参数。
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
     * 反馈闭环配置 — 控制用户反馈对 importanceScore 的调整幅度和过期归档调度。
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
     * 记忆老化与邻居回链配置 — 控制 staleness 检测和降权。
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

        /** 是否启用邻居刷新候选写入（本 spec 首版默认关，等消费侧就绪后再开）。 */
        private boolean neighborRefreshEnabled = false;
    }

    /**
     * REM 式联想巩固配置 — 控制巩固管线中对 L3 高 importance 实体做跨实体联想。
     */
    @Setter
    @Getter
    public static class Rem {

        /** 总开关，默认启用。 */
        private boolean enabled = true;

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
}
