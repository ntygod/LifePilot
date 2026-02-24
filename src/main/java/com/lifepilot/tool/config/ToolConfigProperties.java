package com.lifepilot.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具系统配置属性。
 *
 * <p>使用 JavaBean 风格（嵌套 static class + getter/setter），
 * 满足 Spring Boot {@code @ConfigurationProperties} 绑定要求。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@ConfigurationProperties(prefix = "lifepilot.tool")
public class ToolConfigProperties {

    private boolean enabled = true;
    private PipelineConfig pipeline = new PipelineConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public PipelineConfig getPipeline() { return pipeline; }
    public void setPipeline(PipelineConfig pipeline) { this.pipeline = pipeline; }

    /** 管线配置。 */
    public static class PipelineConfig {
        private int defaultTimeoutSeconds = 30;
        private int defaultMaxRetries = 2;
        private long retryInitialDelayMs = 500;
        private double retryMultiplier = 2.0;
        private long retryMaxDelayMs = 5000;

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int v) { this.defaultTimeoutSeconds = v; }
        public int getDefaultMaxRetries() { return defaultMaxRetries; }
        public void setDefaultMaxRetries(int v) { this.defaultMaxRetries = v; }
        public long getRetryInitialDelayMs() { return retryInitialDelayMs; }
        public void setRetryInitialDelayMs(long v) { this.retryInitialDelayMs = v; }
        public double getRetryMultiplier() { return retryMultiplier; }
        public void setRetryMultiplier(double v) { this.retryMultiplier = v; }
        public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
        public void setRetryMaxDelayMs(long v) { this.retryMaxDelayMs = v; }
    }
}
