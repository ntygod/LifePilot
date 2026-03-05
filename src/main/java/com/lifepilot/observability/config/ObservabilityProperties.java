package com.lifepilot.observability.config;

import lombok.Getter;
import lombok.Setter;
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
@Setter
@Getter
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

    /**
     * 追踪配置 — 控制 Trace 记录行为。
     */
    @Setter
    @Getter
    public static class Trace {

        /** 追踪总开关，默认 true。 */
        private boolean enabled = true;

        /** 是否记录完整 prompt（调试模式），默认 false。 */
        private boolean recordPrompts = false;

        /** Trace 数据保留天数，默认 30。 */
        private int retentionDays = 30;

        /** 是否使用 ScopedValue 传播上下文（否则使用 ThreadLocal），默认 true。 */
        private boolean useScopedValue = true;

    }

    /**
     * 护栏配置 — 控制护栏引擎行为。
     */
    @Setter
    @Getter
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

        /**
         * 工具风险配置。
         */
        @Setter
        @Getter
        public static class ToolRisk {

            /** 默认风险等级，默认 LOW。 */
            private String defaultRiskLevel = "LOW";

            /** 工具 ID → 风险等级映射。 */
            private Map<String, String> toolRiskMapping = Map.of();

        }

        /**
         * 内容安全配置。
         */
        @Setter
        @Getter
        public static class ContentSafety {

            /** 阻断正则模式列表。 */
            private List<String> blockedPatterns = List.of();

            /** 敏感话题列表。 */
            private List<String> sensitiveTopics = List.of();

        }

        /**
         * 预算限制配置。
         */
        @Setter
        @Getter
        public static class BudgetLimit {

            /** 单次请求最大 Token 数，默认 10000。 */
            private int maxTokensPerRequest = 10000;

            /** 单次请求最大步骤数，默认 20。 */
            private int maxStepsPerRequest = 20;

            /** 单次请求最大时长（秒），默认 120。 */
            private int maxDurationSeconds = 120;

            /** 每日 Token 上限，默认 1000000。 */
            private int dailyTokenLimit = 1000000;

        }

        /**
         * 速率限制配置。
         */
        @Setter
        @Getter
        public static class RateLimit {

            /** 每分钟最大调用次数，默认 60。 */
            private int maxCallsPerMinute = 60;

            /** 每小时最大调用次数，默认 1000。 */
            private int maxCallsPerHour = 1000;

        }
    }

    /**
     * 脱敏配置 — 控制 DataRedactor 行为。
     */
    @Setter
    @Getter
    public static class Redaction {

        /** 脱敏总开关，默认 true。 */
        private boolean enabled = true;

        /** 是否在 LLM 调用前脱敏，默认 true。 */
        private boolean redactBeforeLlm = true;

        /** 是否在 Trace 记录中脱敏，默认 true。 */
        private boolean redactInTrace = true;

        /** 是否在日志中脱敏，默认 true。 */
        private boolean redactInLog = true;

    }

    /**
     * 评估配置 — 控制 TrajectoryEvaluator 行为。
     */
    @Setter
    @Getter
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

    }

    /**
     * 指标配置 — 控制 MetricsCollector 行为。
     */
    @Setter
    @Getter
    public static class Metrics {

        /** 指标收集总开关，默认 true。 */
        private boolean enabled = true;

        /** 快照生成间隔（秒），默认 300（5 分钟）。 */
        private int snapshotIntervalSeconds = 300;

        /** 延迟采样最大数量，默认 1000。 */
        private int maxLatencySamples = 1000;

    }
}
