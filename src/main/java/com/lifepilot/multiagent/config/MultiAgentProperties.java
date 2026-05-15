package com.lifepilot.multiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多 Agent 协作配置属性。
 *
 * @author zsg
 * @since 2026-02-27
 */
@ConfigurationProperties(prefix = "lifepilot.agent.multi-agent")
public class MultiAgentProperties {

    /** 是否启用多 Agent 协作。 */
    private boolean enabled = true;

    /** 最大委托深度。 */
    private int maxDelegationDepth = 2;

    /** 热加载配置。 */
    private HotReload hotReload = new HotReload();

    /** 预算默认值配置。 */
    private BudgetDefaults budget = new BudgetDefaults();

    /** 并行 Worker 配置。 */
    private ParallelWorker parallelWorker = new ParallelWorker();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxDelegationDepth() { return maxDelegationDepth; }
    public void setMaxDelegationDepth(int maxDelegationDepth) { this.maxDelegationDepth = maxDelegationDepth; }

    public HotReload getHotReload() { return hotReload; }
    public void setHotReload(HotReload hotReload) { this.hotReload = hotReload; }

    public BudgetDefaults getBudget() { return budget; }
    public void setBudget(BudgetDefaults budget) { this.budget = budget; }

    public ParallelWorker getParallelWorker() { return parallelWorker; }
    public void setParallelWorker(ParallelWorker parallelWorker) { this.parallelWorker = parallelWorker; }

    /**
     * 热加载配置。
     */
    public static class HotReload {
        private boolean enabled = true;
        private int scanIntervalSeconds = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getScanIntervalSeconds() { return scanIntervalSeconds; }
        public void setScanIntervalSeconds(int scanIntervalSeconds) { this.scanIntervalSeconds = scanIntervalSeconds; }
    }

    /**
     * Agent 预算默认值配置。
     */
    public static class BudgetDefaults {
        private int defaultMaxTokens = 16000;
        private int defaultMaxSteps = 15;
        private int defaultTimeoutSeconds = 180;

        public int getDefaultMaxTokens() { return defaultMaxTokens; }
        public void setDefaultMaxTokens(int defaultMaxTokens) { this.defaultMaxTokens = defaultMaxTokens; }

        public int getDefaultMaxSteps() { return defaultMaxSteps; }
        public void setDefaultMaxSteps(int defaultMaxSteps) { this.defaultMaxSteps = defaultMaxSteps; }

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }
    }

    /**
     * 并行 Worker 配置。
     */
    public static class ParallelWorker {
        /** 是否启用 spawn_workers 工具暴露。 */
        private boolean enabled = false;
        /** 最大并行 Worker 数量。 */
        private int maxParallelWorkers = 5;
        /** Worker 预算占比（剩余预算中分配给 Worker 池的比例）。 */
        private double workerBudgetRatio = 0.7;
        /** Worker System Prompt 覆盖（null 时使用默认通用 Worker 提示词）。 */
        private String workerSystemPrompt;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxParallelWorkers() { return maxParallelWorkers; }
        public void setMaxParallelWorkers(int maxParallelWorkers) { this.maxParallelWorkers = maxParallelWorkers; }

        public double getWorkerBudgetRatio() { return workerBudgetRatio; }
        public void setWorkerBudgetRatio(double workerBudgetRatio) { this.workerBudgetRatio = workerBudgetRatio; }

        public String getWorkerSystemPrompt() { return workerSystemPrompt; }
        public void setWorkerSystemPrompt(String workerSystemPrompt) { this.workerSystemPrompt = workerSystemPrompt; }
    }
}
