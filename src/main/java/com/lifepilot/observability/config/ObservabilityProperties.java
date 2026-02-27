package com.lifepilot.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 可观测性模块配置属性。
 *
 * <p>绑定 {@code lifepilot.observability} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@ConfigurationProperties(prefix = "lifepilot.observability")
public class ObservabilityProperties {

    /** 追踪配置。 */
    private Trace trace = new Trace();

    /** 护栏配置。 */
    private Guardrail guardrail = new Guardrail();

    /** 脱敏配置。 */
    private Redaction redaction = new Redaction();

    /** 评估配置。 */
    private Evaluation evaluation = new Evaluation();

    /** 指标配置。 */
    private Metrics metrics = new Metrics();

    public Trace getTrace() { return trace; }
    public void setTrace(Trace trace) { this.trace = trace; }

    public Guardrail getGuardrail() { return guardrail; }
    public void setGuardrail(Guardrail guardrail) { this.guardrail = guardrail; }

    public Redaction getRedaction() { return redaction; }
    public void setRedaction(Redaction redaction) { this.redaction = redaction; }

    public Evaluation getEvaluation() { return evaluation; }
    public void setEvaluation(Evaluation evaluation) { this.evaluation = evaluation; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    /**
     * 追踪配置 — 控制 Trace 记录行为。
     */
    public static class Trace {

        /** 追踪总开关，默认 true。 */
        private boolean enabled = true;

        /** 是否记录完整 prompt（调试模式），默认 false。 */
        private boolean recordPrompts = false;

        /** Trace 数据保留天数，默认 30。 */
        private int retentionDays = 30;

        /** 是否使用 ScopedValue 传播上下文（否则使用 ThreadLocal），默认 true。 */
        private boolean useScopedValue = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isRecordPrompts() { return recordPrompts; }
        public void setRecordPrompts(boolean recordPrompts) { this.recordPrompts = recordPrompts; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

        public boolean isUseScopedValue() { return useScopedValue; }
        public void setUseScopedValue(boolean useScopedValue) { this.useScopedValue = useScopedValue; }
    }

    /**
     * 护栏配置 — 控制护栏引擎行为。
     */
    public static class Guardrail {

        /** 护栏总开关，默认 true。 */
        private boolean enabled = true;

        /** 工具风险配置。 */
        private ToolRisk toolRisk = new ToolRisk();

        /** 内容安全配置。 */
        private ContentSafety contentSafety = new ContentSafety();

        /** 预算限制配置。 */
        private BudgetLimit budgetLimit = new BudgetLimit();

        /** 速率限制配置。 */
        private RateLimit rateLimit = new RateLimit();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public ToolRisk getToolRisk() { return toolRisk; }
        public void setToolRisk(ToolRisk toolRisk) { this.toolRisk = toolRisk; }

        public ContentSafety getContentSafety() { return contentSafety; }
        public void setContentSafety(ContentSafety contentSafety) { this.contentSafety = contentSafety; }

        public BudgetLimit getBudgetLimit() { return budgetLimit; }
        public void setBudgetLimit(BudgetLimit budgetLimit) { this.budgetLimit = budgetLimit; }

        public RateLimit getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

        /**
         * 工具风险配置。
         */
        public static class ToolRisk {

            /** 默认风险等级，默认 LOW。 */
            private String defaultRiskLevel = "LOW";

            /** 工具 ID → 风险等级映射。 */
            private Map<String, String> toolRiskMapping = Map.of();

            public String getDefaultRiskLevel() { return defaultRiskLevel; }
            public void setDefaultRiskLevel(String defaultRiskLevel) { this.defaultRiskLevel = defaultRiskLevel; }

            public Map<String, String> getToolRiskMapping() { return toolRiskMapping; }
            public void setToolRiskMapping(Map<String, String> toolRiskMapping) { this.toolRiskMapping = toolRiskMapping; }
        }

        /**
         * 内容安全配置。
         */
        public static class ContentSafety {

            /** 阻断正则模式列表。 */
            private List<String> blockedPatterns = List.of();

            /** 敏感话题列表。 */
            private List<String> sensitiveTopics = List.of();

            public List<String> getBlockedPatterns() { return blockedPatterns; }
            public void setBlockedPatterns(List<String> blockedPatterns) { this.blockedPatterns = blockedPatterns; }

            public List<String> getSensitiveTopics() { return sensitiveTopics; }
            public void setSensitiveTopics(List<String> sensitiveTopics) { this.sensitiveTopics = sensitiveTopics; }
        }

        /**
         * 预算限制配置。
         */
        public static class BudgetLimit {

            /** 单次请求最大 Token 数，默认 10000。 */
            private int maxTokensPerRequest = 10000;

            /** 单次请求最大步骤数，默认 20。 */
            private int maxStepsPerRequest = 20;

            /** 单次请求最大时长（秒），默认 120。 */
            private int maxDurationSeconds = 120;

            /** 每日 Token 上限，默认 1000000。 */
            private int dailyTokenLimit = 1000000;

            public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
            public void setMaxTokensPerRequest(int maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }

            public int getMaxStepsPerRequest() { return maxStepsPerRequest; }
            public void setMaxStepsPerRequest(int maxStepsPerRequest) { this.maxStepsPerRequest = maxStepsPerRequest; }

            public int getMaxDurationSeconds() { return maxDurationSeconds; }
            public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }

            public int getDailyTokenLimit() { return dailyTokenLimit; }
            public void setDailyTokenLimit(int dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }
        }

        /**
         * 速率限制配置。
         */
        public static class RateLimit {

            /** 每分钟最大调用次数，默认 60。 */
            private int maxCallsPerMinute = 60;

            /** 每小时最大调用次数，默认 1000。 */
            private int maxCallsPerHour = 1000;

            public int getMaxCallsPerMinute() { return maxCallsPerMinute; }
            public void setMaxCallsPerMinute(int maxCallsPerMinute) { this.maxCallsPerMinute = maxCallsPerMinute; }

            public int getMaxCallsPerHour() { return maxCallsPerHour; }
            public void setMaxCallsPerHour(int maxCallsPerHour) { this.maxCallsPerHour = maxCallsPerHour; }
        }
    }

    /**
     * 脱敏配置 — 控制 DataRedactor 行为。
     */
    public static class Redaction {

        /** 脱敏总开关，默认 true。 */
        private boolean enabled = true;

        /** 是否在 LLM 调用前脱敏，默认 true。 */
        private boolean redactBeforeLlm = true;

        /** 是否在 Trace 记录中脱敏，默认 true。 */
        private boolean redactInTrace = true;

        /** 是否在日志中脱敏，默认 true。 */
        private boolean redactInLog = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isRedactBeforeLlm() { return redactBeforeLlm; }
        public void setRedactBeforeLlm(boolean redactBeforeLlm) { this.redactBeforeLlm = redactBeforeLlm; }

        public boolean isRedactInTrace() { return redactInTrace; }
        public void setRedactInTrace(boolean redactInTrace) { this.redactInTrace = redactInTrace; }

        public boolean isRedactInLog() { return redactInLog; }
        public void setRedactInLog(boolean redactInLog) { this.redactInLog = redactInLog; }
    }

    /**
     * 评估配置 — 控制 TrajectoryEvaluator 行为。
     */
    public static class Evaluation {

        /** 评估总开关，默认 true。 */
        private boolean enabled = true;

        /** 是否启用在线评估（Agent 执行完成后自动评估），默认 true。 */
        private boolean onlineEvaluation = true;

        /** 工具选择正确性权重，默认 0.30。 */
        private double toolSelectionWeight = 0.30;

        /** 参数合法性权重，默认 0.20。 */
        private double parameterValidityWeight = 0.20;

        /** 步骤效率权重，默认 0.20。 */
        private double stepEfficiencyWeight = 0.20;

        /** 策略合规性权重，默认 0.20。 */
        private double policyComplianceWeight = 0.20;

        /** Token 效率权重，默认 0.10。 */
        private double tokenEfficiencyWeight = 0.10;

        /** 通过阈值 [0.0, 1.0]，默认 0.7。 */
        private double passThreshold = 0.7;

        /** 需关注阈值 [0.0, 1.0]，默认 0.5。 */
        private double attentionThreshold = 0.5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isOnlineEvaluation() { return onlineEvaluation; }
        public void setOnlineEvaluation(boolean onlineEvaluation) { this.onlineEvaluation = onlineEvaluation; }

        public double getToolSelectionWeight() { return toolSelectionWeight; }
        public void setToolSelectionWeight(double toolSelectionWeight) { this.toolSelectionWeight = toolSelectionWeight; }

        public double getParameterValidityWeight() { return parameterValidityWeight; }
        public void setParameterValidityWeight(double parameterValidityWeight) { this.parameterValidityWeight = parameterValidityWeight; }

        public double getStepEfficiencyWeight() { return stepEfficiencyWeight; }
        public void setStepEfficiencyWeight(double stepEfficiencyWeight) { this.stepEfficiencyWeight = stepEfficiencyWeight; }

        public double getPolicyComplianceWeight() { return policyComplianceWeight; }
        public void setPolicyComplianceWeight(double policyComplianceWeight) { this.policyComplianceWeight = policyComplianceWeight; }

        public double getTokenEfficiencyWeight() { return tokenEfficiencyWeight; }
        public void setTokenEfficiencyWeight(double tokenEfficiencyWeight) { this.tokenEfficiencyWeight = tokenEfficiencyWeight; }

        public double getPassThreshold() { return passThreshold; }
        public void setPassThreshold(double passThreshold) { this.passThreshold = passThreshold; }

        public double getAttentionThreshold() { return attentionThreshold; }
        public void setAttentionThreshold(double attentionThreshold) { this.attentionThreshold = attentionThreshold; }
    }

    /**
     * 指标配置 — 控制 MetricsCollector 行为。
     */
    public static class Metrics {

        /** 指标收集总开关，默认 true。 */
        private boolean enabled = true;

        /** 快照生成间隔（秒），默认 300（5 分钟）。 */
        private int snapshotIntervalSeconds = 300;

        /** 延迟采样最大数量，默认 1000。 */
        private int maxLatencySamples = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getSnapshotIntervalSeconds() { return snapshotIntervalSeconds; }
        public void setSnapshotIntervalSeconds(int snapshotIntervalSeconds) { this.snapshotIntervalSeconds = snapshotIntervalSeconds; }

        public int getMaxLatencySamples() { return maxLatencySamples; }
        public void setMaxLatencySamples(int maxLatencySamples) { this.maxLatencySamples = maxLatencySamples; }
    }
}
