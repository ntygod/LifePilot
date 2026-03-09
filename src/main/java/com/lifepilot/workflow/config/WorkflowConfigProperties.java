package com.lifepilot.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流引擎配置属性。
 *
 * <p>绑定 {@code lifepilot.workflow} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ConfigurationProperties(prefix = "lifepilot.workflow")
public class WorkflowConfigProperties {

    /** 工作流引擎总开关，默认 true。 */
    private boolean enabled = true;

    /** 步骤默认超时时间（秒），默认 300。 */
    private int defaultStepTimeoutSeconds = 300;

    /** 最大并行分支数，默认 10。 */
    private int maxParallelBranches = 10;

    /** 最大嵌套深度（SubWorkflow），默认 3。 */
    private int maxNestingDepth = 3;

    /** 最大循环迭代次数（LoopStep），默认 100。 */
    private int maxLoopIterations = 100;

    /** 工作流 YAML 定义文件目录，默认 ~/.zhiwei/workflows。 */
    private String definitionsDir = "~/.zhiwei/workflows";

    /** 崩溃恢复开关，默认 true。 */
    private boolean crashRecoveryEnabled = true;

    /** YAML 文件扫描间隔（秒），默认 30。 */
    private int scanIntervalSeconds = 30;

    /** 重试策略配置。 */
    private Retry retry = new Retry();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultStepTimeoutSeconds() { return defaultStepTimeoutSeconds; }
    public void setDefaultStepTimeoutSeconds(int defaultStepTimeoutSeconds) { this.defaultStepTimeoutSeconds = defaultStepTimeoutSeconds; }

    public int getMaxParallelBranches() { return maxParallelBranches; }
    public void setMaxParallelBranches(int maxParallelBranches) { this.maxParallelBranches = maxParallelBranches; }

    public int getMaxNestingDepth() { return maxNestingDepth; }
    public void setMaxNestingDepth(int maxNestingDepth) { this.maxNestingDepth = maxNestingDepth; }

    public int getMaxLoopIterations() { return maxLoopIterations; }
    public void setMaxLoopIterations(int maxLoopIterations) { this.maxLoopIterations = maxLoopIterations; }

    public String getDefinitionsDir() { return definitionsDir; }
    public void setDefinitionsDir(String definitionsDir) { this.definitionsDir = definitionsDir; }

    public boolean isCrashRecoveryEnabled() { return crashRecoveryEnabled; }
    public void setCrashRecoveryEnabled(boolean crashRecoveryEnabled) { this.crashRecoveryEnabled = crashRecoveryEnabled; }

    public int getScanIntervalSeconds() { return scanIntervalSeconds; }
    public void setScanIntervalSeconds(int scanIntervalSeconds) { this.scanIntervalSeconds = scanIntervalSeconds; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    /**
     * 重试策略配置 — 控制步骤失败时的指数退避重试参数。
     *
     * @author zsg
     * @since 2026-02-26
     */
    public static class Retry {

        /** 初始延迟（毫秒），默认 500。 */
        private long initialDelayMs = 500;

        /** 最大延迟（毫秒），默认 5000。 */
        private long maxDelayMs = 5000;

        /** 最大重试次数，默认 3。 */
        private int maxAttempts = 3;

        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }

        public long getMaxDelayMs() { return maxDelayMs; }
        public void setMaxDelayMs(long maxDelayMs) { this.maxDelayMs = maxDelayMs; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }
}
