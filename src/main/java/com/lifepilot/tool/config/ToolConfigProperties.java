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

    /** Tier 1 分层注入配置 — 高频工具常驻 prompt schema，其余进 BM25 搜索池。 */
    private Tier1 tier1 = new Tier1();

    /** 搜索服务配置 — tools.search / FTS5 / 三层缓存。 */
    private Search search = new Search();

    /** describe 服务配置 — tools.describe 批量上限。 */
    private Describe describe = new Describe();


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

    /** Tier 1 分层注入配置 —— 只保留人工 pinned 列表，无自动晋升 / 降级。 */
    @Setter
    @Getter
    public static class Tier1 {
        /** 人工固定的 Tier 1 工具 ID 列表，由 application.yml 维护。 */
        private List<String> pinned = List.of();
    }

    /** 搜索服务配置。 */
    @Setter
    @Getter
    public static class Search {
        private int defaultLimit = 5;
        private int maxLimit = 20;
        private double bm25ConfidenceThreshold = 1.0;
        private Cache cache = new Cache();
        private Fallback fallback = new Fallback();

        /** 三层缓存容量配置。 */
        @Setter
        @Getter
        public static class Cache {
            private int layerAMaxSize = 500;
            private int layerBMaxSize = 1000;
            private int layerBTtlMinutes = 5;
        }

        /** 向量 fallback — BM25 不自信时可选补救，默认关闭。 */
        @Setter
        @Getter
        public static class Fallback {
            private boolean vectorEnabled = false;
        }
    }

    /** describe 服务配置。 */
    @Setter
    @Getter
    public static class Describe {
        private int maxBatchSize = 10;
    }
}
