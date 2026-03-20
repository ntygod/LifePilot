package com.lifepilot.tool.config;

import lombok.Getter;
import lombok.Setter;
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
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.tool")
public class ToolConfigProperties {

    private boolean enabled = true;
    private PipelineConfig pipeline = new PipelineConfig();
    private Yaml yaml = new Yaml();
    private TrustedWorkspace trustedWorkspace = new TrustedWorkspace();

    /** 信任工作区配置 — 在信任目录下降低 shell/code 执行的风险等级。 */
    @Setter
    @Getter
    public static class TrustedWorkspace {
        /** 信任目录路径列表，默认空（不信任任何目录）。 */
        private List<String> paths = List.of();

        /** 降级后的风险等级，默认 MEDIUM。 */
        private String downgradeLevel = "MEDIUM";

    }

    /** YAML 工具配置。 */
    @Setter
    @Getter
    public static class Yaml {
        private String baseDir = "tools";

    }

    /** 管线配置。 */
    @Setter
    @Getter
    public static class PipelineConfig {
        private int defaultTimeoutSeconds = 30;
        private int defaultMaxRetries = 2;
        private long retryInitialDelayMs = 500;
        private double retryMultiplier = 2.0;
        private long retryMaxDelayMs = 5000;

    }
}
