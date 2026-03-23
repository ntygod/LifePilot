package com.lifepilot.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
    private LoopConfig loop = new LoopConfig();
    private BudgetConfig budget = new BudgetConfig();
    private ContextConfig context = new ContextConfig();
    private SessionConfig session = new SessionConfig();
    private CheckpointConfig checkpoint = new CheckpointConfig();
    private DebugConfig debug = new DebugConfig();
    private TaskConfig task = new TaskConfig();

    /** ReAct 循环配置（替代原 LoopConfig）。 */
    public static class LoopConfig {
        /** 单次循环最大迭代次数。 */
        private int maxIterations = 25;
        /** 连续工具调用失败最大次数。 */
        private int maxConsecutiveFailures = 3;
        /** LLM 调用场景标识。 */
        private String llmScene = "agent_react";

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
        public void setMaxConsecutiveFailures(int maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; }
        public String getLlmScene() { return llmScene; }
        public void setLlmScene(String llmScene) { this.llmScene = llmScene; }
    }

    /** 预算配置。 */
    @Setter
    @Getter
    public static class BudgetConfig {
        /** 对话总 Token 预算（整个对话允许消耗的总 Token）。 */
        private int defaultMaxTokens = 131072;
        private int defaultMaxSteps = 30;
        private int defaultMaxDurationSeconds = 300;
        private double subAgentBudgetRatio = 0.3;

    }

    /** 上下文配置。 */
    @Getter
    public static class ContextConfig {
        /**
         * 单次 LLM 调用的最大上下文 Token 数。
         * <p>实际使用时取 min(此值, 模型的 maxContextWindow)。</p>
         */
        @Setter
        private int maxContextTokens = 131072;
        @Setter
        private int outputReservedTokens = 8192;
        /** 成功步骤输出截断长度。 */
        @Setter
        private int successStepMaxLength = 200;
        /** 失败步骤输出截断长度。 */
        @Setter
        private int failedStepMaxLength = 80;
        /** Token 分配比例。 */
        private TokenAllocation tokenAllocation = new TokenAllocation();

        public void setTokenAllocation(TokenAllocation tokenAllocation) {
            this.tokenAllocation = tokenAllocation != null ? tokenAllocation : new TokenAllocation();
        }

        /** Token 分配比例。 */
        @Setter
        @Getter
        public static class TokenAllocation {
            private int systemPromptPercent = 15;
            private int historyPercent = 30;
            private int memoryPercent = 35;
            private int toolSchemaPercent = 10;
            private int toolResultPercent = 0;
            private int reservedBufferPercent = 10;

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

    /** 自主任务配置（Cron + Heartbeat 双轨）。 */
    @Setter
    @Getter
    public static class TaskConfig {
        /** 任务系统总开关。 */
        private boolean enabled = true;
        /** Heartbeat 单独开关。 */
        private boolean heartbeatEnabled = true;
        /** 心跳间隔（秒）。 */
        private int heartbeatIntervalSeconds = 1800;
        /** 心跳 checklist 文件路径。 */
        private String heartbeatFile = "~/.zhiwei/HEARTBEAT.md";
        /** 单次执行超时（秒）。 */
        private int executionTimeoutSeconds = 300;
        /** 活跃时段开始（HH:mm，null 表示全天）。 */
        private String activeHoursStart;
        /** 活跃时段结束（HH:mm）。 */
        private String activeHoursEnd;
        /** 每个 cron 任务保留的最大执行日志数。 */
        private int maxLogsPerTask = 50;

    }
}
