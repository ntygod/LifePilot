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

    /** Agent Markdown 定义文件目录。 */
    private String agentDefinitionsPath = "~/.zhiwei/agents/";

    /** 是否自动注册 HandoffTool。 */
    private boolean registerHandoffTools = true;

    /** 热加载配置。 */
    private HotReload hotReload = new HotReload();

    /** 预算默认值配置。 */
    private BudgetDefaults budget = new BudgetDefaults();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxDelegationDepth() { return maxDelegationDepth; }
    public void setMaxDelegationDepth(int maxDelegationDepth) { this.maxDelegationDepth = maxDelegationDepth; }

    public String getAgentDefinitionsPath() { return agentDefinitionsPath; }
    public void setAgentDefinitionsPath(String agentDefinitionsPath) { this.agentDefinitionsPath = agentDefinitionsPath; }

    public boolean isRegisterHandoffTools() { return registerHandoffTools; }
    public void setRegisterHandoffTools(boolean registerHandoffTools) { this.registerHandoffTools = registerHandoffTools; }

    public HotReload getHotReload() { return hotReload; }
    public void setHotReload(HotReload hotReload) { this.hotReload = hotReload; }

    public BudgetDefaults getBudget() { return budget; }
    public void setBudget(BudgetDefaults budget) { this.budget = budget; }

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
}
