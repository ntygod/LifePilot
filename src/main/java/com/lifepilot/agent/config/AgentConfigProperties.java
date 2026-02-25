package com.lifepilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

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

    /** 循环配置。 */
    public static class LoopConfig {
        private int maxIterations = 50;
        private int maxConsecutiveBlocks = 3;
        private Map<String, String> sceneMapping = Map.of(
                "understanding", "agent-reasoning",
                "planning", "agent-reasoning",
                "executing", "agent-tool-calling",
                "reflecting", "agent-reasoning",
                "responding", "agent-generation"
        );

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getMaxConsecutiveBlocks() { return maxConsecutiveBlocks; }
        public void setMaxConsecutiveBlocks(int maxConsecutiveBlocks) { this.maxConsecutiveBlocks = maxConsecutiveBlocks; }
        public Map<String, String> getSceneMapping() { return sceneMapping; }
        public void setSceneMapping(Map<String, String> sceneMapping) { this.sceneMapping = sceneMapping; }
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
    public static class ContextConfig {
        private int maxContextTokens = 32000;
        private int outputReservedTokens = 4000;

        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public int getOutputReservedTokens() { return outputReservedTokens; }
        public void setOutputReservedTokens(int outputReservedTokens) { this.outputReservedTokens = outputReservedTokens; }
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
}
