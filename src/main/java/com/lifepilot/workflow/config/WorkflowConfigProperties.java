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

    /** 崩溃恢复开关，默认 true。 */
    private boolean crashRecoveryEnabled = true;

    /** YAML 文件扫描间隔（秒），默认 30。 */
    private int scanIntervalSeconds = 30;

    /** 是否在启动时释放内置工作流模板到用户目录，默认 true。 */

    /** 重试策略配置。 */
    private Retry retry = new Retry();

    /** 审批步骤配置。 */
    private Approval approval = new Approval();

    /** 事件审计配置。 */
    private EventAudit eventAudit = new EventAudit();

    /** 唤醒调度器配置。 */
    private Wakeup wakeup = new Wakeup();

    /** 速率限制配置。 */
    private RateLimit rateLimit = new RateLimit();

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

    public boolean isCrashRecoveryEnabled() { return crashRecoveryEnabled; }
    public void setCrashRecoveryEnabled(boolean crashRecoveryEnabled) { this.crashRecoveryEnabled = crashRecoveryEnabled; }

    public int getScanIntervalSeconds() { return scanIntervalSeconds; }
    public void setScanIntervalSeconds(int scanIntervalSeconds) { this.scanIntervalSeconds = scanIntervalSeconds; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public Approval getApproval() { return approval; }
    public void setApproval(Approval approval) { this.approval = approval; }

    public EventAudit getEventAudit() { return eventAudit; }
    public void setEventAudit(EventAudit eventAudit) { this.eventAudit = eventAudit; }

    public Wakeup getWakeup() { return wakeup; }
    public void setWakeup(Wakeup wakeup) { this.wakeup = wakeup; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

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

    /**
     * 审批步骤配置 — 控制 ApprovalStep 的默认超时和自动审批行为。
     *
     * @author zsg
     * @since 2026-03-09
     */
    public static class Approval {

        /** 默认审批超时时间（秒），默认 86400（24 小时）。 */
        private int defaultTimeoutSeconds = 86400;

        /** 超时后是否自动批准，默认 false。 */
        private boolean autoApproveOnTimeout = false;

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }

        public boolean isAutoApproveOnTimeout() { return autoApproveOnTimeout; }
        public void setAutoApproveOnTimeout(boolean autoApproveOnTimeout) { this.autoApproveOnTimeout = autoApproveOnTimeout; }
    }

    /**
     * 事件审计配置 — 控制工作流审计事件的记录和清理策略。
     *
     * @author zsg
     * @since 2026-03-09
     */
    public static class EventAudit {

        /** 是否启用事件审计记录，默认 true。 */
        private boolean enabled = true;

        /** 事件保留天数，超过后自动清理，默认 90。 */
        private int retentionDays = 90;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    /**
     * 唤醒调度器配置 — 控制 WakeupScheduler 的扫描间隔。
     *
     * @author zsg
     * @since 2026-03-10
     */
    public static class Wakeup {

        /** 扫描间隔（秒），默认 10。 */
        private int scanIntervalSeconds = 10;

        public int getScanIntervalSeconds() { return scanIntervalSeconds; }
        public void setScanIntervalSeconds(int scanIntervalSeconds) { this.scanIntervalSeconds = scanIntervalSeconds; }
    }

    /**
     * 速率限制配置 — 控制工作流实例的并发执行数量，防止系统过载。
     *
     * @author zsg
     * @since 2026-03-13
     */
    public static class RateLimit {

        /** 全局最大并发实例数，默认 20。 */
        private int maxConcurrentInstances = 20;

        /** 单个工作流最大并发实例数，默认 3。 */
        private int maxInstancesPerWorkflow = 3;

        public int getMaxConcurrentInstances() { return maxConcurrentInstances; }
        public void setMaxConcurrentInstances(int maxConcurrentInstances) { this.maxConcurrentInstances = maxConcurrentInstances; }

        public int getMaxInstancesPerWorkflow() { return maxInstancesPerWorkflow; }
        public void setMaxInstancesPerWorkflow(int maxInstancesPerWorkflow) { this.maxInstancesPerWorkflow = maxInstancesPerWorkflow; }
    }
}
