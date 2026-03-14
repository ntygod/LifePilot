package com.lifepilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 引擎配置属性。
 *
 * <p>使用 JavaBean 风格（嵌套 static class + getter/setter），
 * 满足 Spring Boot {@code @ConfigurationProperties} 绑定要求。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@ConfigurationProperties(prefix = "lifepilot.agent")
public class AgentConfigProperties {

    private boolean enabled = true;
    private LoopConfig loop = new LoopConfig();
    private BudgetConfig budget = new BudgetConfig();
    private ContextConfig context = new ContextConfig();
    private SessionConfig session = new SessionConfig();
    private DebugConfig debug = new DebugConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LoopConfig getLoop() { return loop; }
    public void setLoop(LoopConfig loop) { this.loop = loop; }
    public BudgetConfig getBudget() { return budget; }
    public void setBudget(BudgetConfig budget) { this.budget = budget; }
    public ContextConfig getContext() { return context; }
    public void setContext(ContextConfig context) { this.context = context; }
    public SessionConfig getSession() { return session; }
    public void setSession(SessionConfig session) { this.session = session; }
    public DebugConfig getDebug() { return debug; }
    public void setDebug(DebugConfig debug) { this.debug = debug; }

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
    public static class BudgetConfig {
        private int defaultMaxTokens = 32000;
        private int defaultMaxSteps = 20;
        private int defaultMaxDurationSeconds = 120;
        private double subAgentBudgetRatio = 0.3;

        public int getDefaultMaxTokens() { return defaultMaxTokens; }
        public void setDefaultMaxTokens(int defaultMaxTokens) { this.defaultMaxTokens = defaultMaxTokens; }
        public int getDefaultMaxSteps() { return defaultMaxSteps; }
        public void setDefaultMaxSteps(int defaultMaxSteps) { this.defaultMaxSteps = defaultMaxSteps; }
        public int getDefaultMaxDurationSeconds() { return defaultMaxDurationSeconds; }
        public void setDefaultMaxDurationSeconds(int defaultMaxDurationSeconds) { this.defaultMaxDurationSeconds = defaultMaxDurationSeconds; }
        public double getSubAgentBudgetRatio() { return subAgentBudgetRatio; }
        public void setSubAgentBudgetRatio(double subAgentBudgetRatio) { this.subAgentBudgetRatio = subAgentBudgetRatio; }
    }

    /** 上下文配置。 */
/** 上下文配置。 */
    public static class ContextConfig {
        private int maxContextTokens = 32000;
        private int outputReservedTokens = 4000;
        /** 成功步骤输出截断长度。 */
        private int successStepMaxLength = 200;
        /** 失败步骤输出截断长度。 */
        private int failedStepMaxLength = 80;

        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public int getOutputReservedTokens() { return outputReservedTokens; }
        public void setOutputReservedTokens(int outputReservedTokens) { this.outputReservedTokens = outputReservedTokens; }
        public int getSuccessStepMaxLength() { return successStepMaxLength; }
        public void setSuccessStepMaxLength(int successStepMaxLength) { this.successStepMaxLength = successStepMaxLength; }
        public int getFailedStepMaxLength() { return failedStepMaxLength; }
        public void setFailedStepMaxLength(int failedStepMaxLength) { this.failedStepMaxLength = failedStepMaxLength; }
    }

    /** 会话配置。 */
    public static class SessionConfig {
        private int timeoutMinutes = 30;
        private int maxRecentTurns = 10;
        private long cleanupIntervalMs = 300000;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getMaxRecentTurns() { return maxRecentTurns; }
        public void setMaxRecentTurns(int maxRecentTurns) { this.maxRecentTurns = maxRecentTurns; }
        public long getCleanupIntervalMs() { return cleanupIntervalMs; }
        public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
    }

    /**
     * 调试配置。
     *
     * <p>注意：开启完整提示词日志可能泄漏隐私/密钥，仅建议在本地或受控环境使用。</p>
     */
    public static class DebugConfig {
        /** 是否打印每次调用 LLM 时发送的完整 system/user 提示词。默认关闭。 */
        private boolean logLlmPrompts = false;

        public boolean isLogLlmPrompts() { return logLlmPrompts; }
        public void setLogLlmPrompts(boolean logLlmPrompts) { this.logLlmPrompts = logLlmPrompts; }
    }
}
