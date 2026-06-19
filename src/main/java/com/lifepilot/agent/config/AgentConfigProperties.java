package com.lifepilot.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Agent 引擎配置属性。
 *
 * <p>使用 JavaBean 风格（嵌套 static class + getter/setter），
 * 满足 Spring Boot {@code @ConfigurationProperties} 绑定要求。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.agent")
public class AgentConfigProperties {

    private boolean enabled = true;
    /** 手动覆盖用户位置（优先于 IP 自动检测），为空时自动检测。 */
    private String location = "";
    /** IP 地理定位 API 地址，为空时禁用自动检测。 */
    private String ipApiUrl = "http://ip-api.com/json/?fields=city,regionName,country&lang=zh-CN";
    private LoopConfig loop = new LoopConfig();
    private BudgetConfig budget = new BudgetConfig();
    private ContextConfig context = new ContextConfig();
    private SessionConfig session = new SessionConfig();
    private CheckpointConfig checkpoint = new CheckpointConfig();
    private ExecutionRetryConfig executionRetry = new ExecutionRetryConfig();
    private DebugConfig debug = new DebugConfig();
    private TaskConfig task = new TaskConfig();

    /** ReAct 循环配置（替代原 LoopConfig）。 */
    public static class LoopConfig {
        /** 单次循环最大迭代次数。 */
        private int maxIterations = 25;
        /** 连续工具调用失败最大次数。 */
        private int maxConsecutiveFailures = 3;
        private int maxEarlyStopRejects = 2;
        /** 单个工具波次允许的最大并发数。 */
        private int maxParallelToolCalls = 4;
        /** LLM 调用场景标识。 */
        private String llmScene = "agent_react";
        /** 默认 temperature（会话未配置时使用）。 */
        private double defaultTemperature = 0.7;

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
        public void setMaxConsecutiveFailures(int maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }
        public int getMaxEarlyStopRejects() { return maxEarlyStopRejects; }
        public void setMaxEarlyStopRejects(int maxEarlyStopRejects) { this.maxEarlyStopRejects = maxEarlyStopRejects; }
        public int getMaxParallelToolCalls() { return maxParallelToolCalls; }
        public void setMaxParallelToolCalls(int maxParallelToolCalls) { this.maxParallelToolCalls = maxParallelToolCalls; }
        public String getLlmScene() { return llmScene; }
        public void setLlmScene(String llmScene) { this.llmScene = llmScene; }
        public double getDefaultTemperature() { return defaultTemperature; }
        public void setDefaultTemperature(double defaultTemperature) { this.defaultTemperature = defaultTemperature; }

        /** 开始周期性回顾的迭代阈值（iteration 从 0 开始计数，默认值 5 表示第 6 轮首次触发）。 */
        private int reflectAfterIterations = 5;
        /** 周期性回顾间隔（每隔 N 轮触发）。 */
        private int reflectInterval = 3;
        /** 工具失败时是否触发回顾。 */
        private boolean reflectOnToolFailure = true;
        /** 停滞检测阈值：连续相同工具调用次数。 */
        private int stallDetectionThreshold = 3;

        public int getReflectAfterIterations() { return reflectAfterIterations; }
        public void setReflectAfterIterations(int reflectAfterIterations) { this.reflectAfterIterations = reflectAfterIterations; }
        public int getReflectInterval() { return reflectInterval; }
        public void setReflectInterval(int reflectInterval) { this.reflectInterval = reflectInterval; }
        public boolean isReflectOnToolFailure() { return reflectOnToolFailure; }
        public void setReflectOnToolFailure(boolean reflectOnToolFailure) { this.reflectOnToolFailure = reflectOnToolFailure; }
        public int getStallDetectionThreshold() { return stallDetectionThreshold; }
        public void setStallDetectionThreshold(int stallDetectionThreshold) { this.stallDetectionThreshold = stallDetectionThreshold; }
    }

    /** 预算配置。 */
    @Setter
    @Getter
    public static class BudgetConfig {
        /** 对话总 Token 预算（整个对话允许消耗的总 Token）。 */
        private int defaultMaxTokens = 20000000;
        private int defaultMaxSteps = 30;
        private int defaultMaxDurationSeconds = 300;

    }

    /** 上下文配置。 */
    @Getter
    public static class ContextConfig {
        /**
         * 单次 LLM 调用的最大上下文 Token 数。
         * <p>实际使用时取 min(此值, 模型的 maxContextWindow)。</p>
         */
        @Setter
        private int maxContextTokens = 2000000;
        @Setter
        private int outputReservedTokens = 8192;
        /** Token 分配比例。 */
        private TokenAllocation tokenAllocation = new TokenAllocation();
        private SliceConfig slice = new SliceConfig();
        private PruningConfig pruning = new PruningConfig();
        private CompactionConfig compaction = new CompactionConfig();
        private MessageBuildConfig messageBuild = new MessageBuildConfig();
        private ReportConfig report = new ReportConfig();

        public void setTokenAllocation(TokenAllocation tokenAllocation) {
            this.tokenAllocation = tokenAllocation != null ? tokenAllocation : new TokenAllocation();
        }

        public void setSlice(SliceConfig slice) {
            this.slice = slice != null ? slice : new SliceConfig();
        }

        public void setPruning(PruningConfig pruning) {
            this.pruning = pruning != null ? pruning : new PruningConfig();
        }

        public void setCompaction(CompactionConfig compaction) {
            this.compaction = compaction != null ? compaction : new CompactionConfig();
        }

        public void setMessageBuild(MessageBuildConfig messageBuild) {
            this.messageBuild = messageBuild != null ? messageBuild : new MessageBuildConfig();
        }

        public void setReport(ReportConfig report) {
            this.report = report != null ? report : new ReportConfig();
        }

        /** Token 分配比例。 */
        @Setter
        @Getter
        public static class TokenAllocation {
            private int systemPromptPercent = 15;
            private int historyPercent = 30;
            private int memoryPercent = 25;
            private int toolSchemaPercent = 10;
            private int toolResultPercent = 10;
            private int reservedBufferPercent = 10;

        }

        @Setter
        @Getter
        public static class SliceConfig {
            private int recentArtifactLimit = 3;
        }

        @Setter
        @Getter
        public static class PruningConfig {
            private boolean enabled = true;
            private String toolResultMode = "recent_only";
            private int recentToolResultLimit = 4;
            private int failedToolResultLimit = 2;
            private int toolResultPreviewChars = 240;
        }

        @Setter
        @Getter
        public static class CompactionConfig {
            private boolean enabled = true;
            private int triggerThresholdPercent = 75;
            private int keepRecentTurns = 2;
            private int minTurnCount = 6;
            private int maxSourceEntries = 80;
            private int summaryMaxChars = 500;
            /**
             * 固定开销预留 token 数 — 代表 systemPrompt + skill/mcp catalog + 自动注入
             * （热摘要/决策信号/工作区/检查点）等非 transcript 的每轮固定开销。
             *
             * <p>压缩触发阈值在有效窗口中先扣除该预留，使压缩在真实逼近窗口时及时触发，
             * 而非仅按 transcript token 判断导致偏晚。</p>
             */
            private int fixedOverheadReserveTokens = 8000;
        }

        @Setter
        @Getter
        public static class MessageBuildConfig {
            private boolean hygieneEnabled = true;
            private boolean dropEmptyAssistantMessages = true;
            private boolean dropOrphanToolResponses = true;
            private boolean keepOnlyFirstSystemMessage = true;
        }

        @Setter
        @Getter
        public static class ReportConfig {
            private boolean contextReportEnabled = true;

        }
    }

    /** 会话配置。 */
    @Setter
    @Getter
    public static class SessionConfig {
        private int timeoutMinutes = 30;
        private int maxRecentTurns = 10;
        private long cleanupIntervalMs = 300000;

    }

    /** 检查点配置。 */
    @Setter
    @Getter
    public static class CheckpointConfig {
        private boolean enabled = true;
        private Duration maxAge = Duration.ofDays(7);
        private long cleanupIntervalMs = Duration.ofHours(1).toMillis();

    }

    /** 主执行链路自动重试配置。 */
    @Setter
    @Getter
    public static class ExecutionRetryConfig {
        /** 是否启用主执行链路安全自动重试。 */
        private boolean enabled = true;
        /** 单次请求的最大尝试次数，包含首次执行。 */
        private int maxAttempts = 2;
        /** 首次重试前的退避时间（毫秒）。 */
        private long initialDelayMs = 500;
        /** 指数退避倍率。 */
        private double multiplier = 2.0;
        /** 最大退避时间（毫秒）。 */
        private long maxDelayMs = 5000;
    }

    /**
     * 调试配置。
     *
     * <p>注意：开启完整提示词日志可能泄漏隐私/密钥，仅建议在本地或受控环境使用。</p>
     */
    @Setter
    @Getter
    public static class DebugConfig {
        /** 是否打印每次调用 LLM 时发送的完整 system/user 提示词。默认关闭。 */
        private boolean logLlmPrompts = false;

    }

    /** 自主任务配置（Cron + 主动提醒）。 */
    @Setter
    @Getter
    public static class TaskConfig {
        /** 任务系统总开关。 */
        private boolean enabled = true;
        /** 心跳唤醒单独开关。 */
        private boolean heartbeatEnabled = true;
        /** 是否启用主动提醒引擎。 */
        private boolean proactiveReminderEnabled = false;
        /** 心跳间隔（秒）。 */
        private int heartbeatIntervalSeconds = 1800;
        /** 主动提醒每日上限。 */
        private int proactiveReminderDailyMaxReminders = 3;
        /** 主动提醒默认冷却时长（小时）。 */
        private int proactiveReminderCooldownHours = 24;
        /** 主动提醒静默开始时间（HH:mm）。 */
        private String proactiveReminderQuietHoursStart = "23:00";
        /** 主动提醒静默结束时间（HH:mm）。 */
        private String proactiveReminderQuietHoursEnd = "08:00";
        /** 延后提醒扫描间隔（秒）。 */
        private int proactiveReminderWakeupScanIntervalSeconds = 60;
        /** 单次延后提醒扫描的最大主题数。 */
        private int proactiveReminderWakeupBatchSize = 20;
        /** 隐式完成推断回看窗口（天）。 */
        private int proactiveReminderOutcomeInferenceLookbackDays = 30;
        /** 单次隐式完成推断批量大小。 */
        private int proactiveReminderOutcomeInferenceBatchSize = 60;
        /** 是否启用基于上下文反馈的动作 bandit。 */
        private boolean proactiveReminderBanditEnabled = true;
        /** bandit 探索系数。 */
        private float proactiveReminderBanditExplorationAlpha = 0.18f;
        /** 启动 bandit 所需的最小训练样本数。 */
        private int proactiveReminderBanditMinExamples = 16;
        /** 每个动作至少需要的训练样本数。 */
        private int proactiveReminderBanditMinActionSamples = 3;
        /** bandit 回放历史窗口（天）。 */
        private int proactiveReminderBanditLookbackDays = 30;
        /** 单次加载的 bandit 训练样本上限。 */
        private int proactiveReminderBanditMaxExamples = 400;
        /** 是否启用基于离线回放的策略调优。 */
        private boolean proactiveReminderReplayTuningEnabled = true;
        /** 启用回放调优所需的最小样本数。 */
        private int proactiveReminderReplayMinSamples = 12;
        /** 是否启用定时离线回放评估。 */
        private boolean proactiveReminderReplayEvaluationEnabled = true;
        /** 定时离线回放评估间隔（秒）。 */
        private int proactiveReminderReplayEvaluationIntervalSeconds = 21600;
        /** 单次离线回放评估的最大用户数。 */
        private int proactiveReminderReplayEvaluationUserBatchSize = 8;
        /** 主动提醒运行数据保留天数。 */
        private int proactiveReminderRetentionDays = 180;
        /** 主动提醒样本治理清理间隔（秒）。 */
        private int proactiveReminderCleanupIntervalSeconds = 21600;
        /** 是否启用主动提醒安全调参护栏。 */
        private boolean proactiveReminderSafeTuningEnabled = true;
        /** 单次阈值类参数允许调整的最大浮点步长。 */
        private float proactiveReminderTuningMaxScoreDelta = 0.04f;
        /** 单次时间阈值允许调整的最大小时步长。 */
        private int proactiveReminderTuningMaxHourDelta = 6;
        /** 单次 lookahead 允许调整的最大分钟步长。 */
        private int proactiveReminderTuningMaxLookaheadMinutesDelta = 45;
        /** 单次每日提醒上限允许调整的最大步长。 */
        private int proactiveReminderTuningMaxDailyReminderDelta = 1;
        /** 回放收益恶化达到该阈值时回退到上一版策略。 */
        private float proactiveReminderTuningReplayRollbackDelta = 0.05f;
        /** 是否要求连续两轮信号方向一致后才放松策略。 */
        private boolean proactiveReminderTuningRequireConsistentReplayDirection = true;
        /** 是否启用机会判断学习。 */
        private boolean proactiveReminderOpportunityLearningEnabled = true;
        /** 机会学习的补发阈值。 */
        private float proactiveReminderOpportunityPromoteThreshold = 0.72f;
        /** 机会学习的抑制阈值。 */
        private float proactiveReminderOpportunitySuppressThreshold = 0.34f;
        /** 允许从分数不足补发提醒的安全边距。 */
        private float proactiveReminderOpportunityPromotionMargin = 0.08f;
        /** 主动提醒文案生成场景。 */
        private String proactiveReminderLlmScene = "chat";
        /** 主动提醒文案生成超时（秒）。 */
        private int proactiveReminderLlmTimeoutSeconds = 15;
        /** 和风天气 API 基础 URL。 */
        private String weatherApiBaseUrl = "https://devapi.qweather.com/v7/weather/3d";
        /** 和风天气 API key。 */
        private String weatherApiKey;
        /** 天气查询城市 ID 或经纬度。 */
        private String weatherLocation;
        /** 天气缓存 TTL（小时）。 */
        private long weatherCacheTtlHours = 6;
        /** 天气温差提醒阈值（°C）。 */
        private int weatherTempDiffThreshold = 10;
        /** 天气降水提醒阈值（mm，有外出事件时生效）。 */
        private double weatherPrecipitationThreshold = 5.0;
        /** 天气强降水提醒阈值（mm，无论是否外出）。 */
        private double weatherHeavyPrecipitationThreshold = 20.0;
        /** 单次执行超时（秒）。 */
        private int executionTimeoutSeconds = 300;
        /** 活跃时段开始（HH:mm，null 表示全天）。 */
        private String activeHoursStart;
        /** 活跃时段结束（HH:mm）。 */
        private String activeHoursEnd;
        /** 每个 cron 任务保留的最大执行日志数。 */
        private int maxLogsPerTask = 50;

        // ── 主动引擎（ProactiveEngine）参数 ──

        /** Gate 2 阈值 — 所有候选最高分低于此值时不进入 FULL 推理。 */
        private float proactiveEngineGate2Threshold = 0.4f;
        /** 主动引擎行为插件 LLM 调用超时（秒）。 */
        private int proactiveEngineLlmTimeoutSeconds = 15;
        /** 信任升级连续正反馈阈值。 */
        private int proactiveEngineTrustUpgradeThreshold = 5;
        /** 信任降级冷却天数。 */
        private int proactiveEngineTrustDowngradeCooldownDays = 7;
        /** 日报触发小时（24h 制）。 */
        private int proactiveEngineDailyReportHour = 20;
        /** 追问行为最小意图年龄（小时） — 创建时间不足此值的意图不追问。 */
        private int proactiveEngineFollowUpMinAgeHours = 24;
        /** 追问行为最大检查次数 — 超过此值的意图不再追问。 */
        private int proactiveEngineFollowUpMaxCheckCount = 5;
        /** 剪贴板意图缓冲区最大容量。 */
        private int proactiveEngineClipboardBufferMaxSize = 20;

        // ── 主动引擎 Boundary / Focus / Training 参数（proactive-boundary-training spec） ──

        /** 是否启用边界信号采集（订阅对话/工作流/A2A 完成事件）。 */
        private boolean boundarySignalEnabled = true;
        /** 边界窗口分钟数 — 事件发生后多少分钟内视为"处于边界"。 */
        private int boundaryWindowMinutes = 10;

        /** 是否启用焦点状态检测。 */
        private boolean focusDetectionEnabled = true;
        /** 触发 FOCUS_MODE 的最小近期消息数（5 分钟窗口）。 */
        private int focusMessageDensityThreshold = 5;
        /** 触发 FOCUS_MODE 的最大平均消息间隔（秒）。 */
        private int focusMessageIntervalSeconds = 40;

        /** Boundary 内 NOTIFY 阈值偏移（默认 -0.15，更易 NOTIFY）。 */
        private float proactiveEngineBoundaryNotifyDelta = -0.15f;
        /** Boundary 内 INTERRUPT 阈值偏移（默认 -0.15）。 */
        private float proactiveEngineBoundaryInterruptDelta = -0.15f;
        /** Boundary 外两级阈值偏移（默认 +0.25，更难投递）。 */
        private float proactiveEngineOutOfBoundaryDelta = 0.25f;

        /** 是否启用主动训练回放（周级生成 few-shot 样例库）。 */
        private boolean proactiveTrainingEnabled = false;
        /** 主动训练回放间隔（天）。 */
        private int proactiveTrainingReplayIntervalDays = 7;
        /** 正例 reward 阈值（>= 此值视为正例）。 */
        private float proactiveTrainingPositiveRewardThreshold = 0.6f;
        /** 负例 reward 阈值（<= 此值视为负例）。 */
        private float proactiveTrainingNegativeRewardThreshold = 0.2f;
        /** 正例 top-K 截断。 */
        private int proactiveTrainingTopKPositive = 10;
        /** 负例 top-K 截断。 */
        private int proactiveTrainingTopKNegative = 10;

        // ── Timing / CoT / 分层激活（proactive-timing-cot spec） ──

        /** 是否启用 Goldilocks 时效窗口检查。 */
        private boolean proactiveTimingWindowEnabled = true;
        /** 历史样本不足时的默认用户响应延迟（分钟）。 */
        private int proactiveTimingDefaultResponseLatencyMinutes = 30;
        /** 估算 p80 延迟所需的最少样本数。 */
        private int proactiveTimingResponseLatencyMinSamples = 5;
        /** p80 分位安全系数（0-1，越小越早判定 windowClosed）。 */
        private float proactiveTimingResponseLatencyP80Percentile = 0.8f;

        /** 是否启用 Gate 3 CoT 结构化推理。 */
        private boolean proactiveCotEnabled = false;
        /** 启用 CoT 的最低候选分数。 */
        private float proactiveCotMinScore = 0.6f;
        /** CoT prompt 中拼接的 few-shot 样本数。 */
        private int proactiveCotFewShotCount = 4;

    }
}
