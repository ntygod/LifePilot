package com.lifepilot.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
    private Yaml yaml = new Yaml();
    private TrustedWorkspace trustedWorkspace = new TrustedWorkspace();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public PipelineConfig getPipeline() { return pipeline; }
    public void setPipeline(PipelineConfig pipeline) { this.pipeline = pipeline; }
    public Yaml getYaml() { return yaml; }
    public void setYaml(Yaml yaml) { this.yaml = yaml; }
    public TrustedWorkspace getTrustedWorkspace() { return trustedWorkspace; }
    public void setTrustedWorkspace(TrustedWorkspace trustedWorkspace) { this.trustedWorkspace = trustedWorkspace; }

    /** 信任工作区配置 — 在信任目录下降低 shell/code 执行的风险等级。 */
    public static class TrustedWorkspace {
        /** 信任目录路径列表，默认空（不信任任何目录）。 */
        private List<String> paths = List.of();

        /** 降级后的风险等级，默认 MEDIUM。 */
        private String downgradeLevel = "MEDIUM";

        public List<String> getPaths() { return paths; }
        public void setPaths(List<String> paths) { this.paths = paths; }
        public String getDowngradeLevel() { return downgradeLevel; }
        public void setDowngradeLevel(String downgradeLevel) { this.downgradeLevel = downgradeLevel; }
    }

    /** YAML 工具配置。 */
    public static class Yaml {
        private String baseDir = "tools";

        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }

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
