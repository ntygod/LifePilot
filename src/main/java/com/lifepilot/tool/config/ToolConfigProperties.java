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

    /**
     * Category hint 文案，注入 system prompt 末尾。
     *
     * <p>核心意图：**强制** LLM 在声称"没有某个能力"或退到 shell.exec/code.execute
     * 兜底之前先调 tools.search 核实。避免 Agent 凭记忆断言工具不存在。</p>
     */
    private String categoryHint = """
            ## Tool discovery — IMPORTANT

            The tools you see above are NOT the complete set. They are only the
            Tier 1 "always-loaded" high-frequency tools plus 3 discovery meta tools.
            The full registry has many more specialized tools (git, browser,
            document editing, datastore queries, cron, workflow, etc.) accessible
            on demand.

            MUST-FOLLOW rules:

            1. BEFORE claiming a capability does not exist, OR BEFORE falling back
               to a generic tool (shell.exec / code.execute / file.write / ...),
               you MUST call tools.search(<english keyword>) FIRST to verify no
               dedicated tool exists. Never answer "I don't have that tool" based
               on memory alone.

            2. Discovery workflow:
                 tools.search("keyword")        -> returns top-k {id, description}
                 tools.describe(["tool.id"])    -> get full JSON schema
                 invoke the tool with that id
               Use English keywords for search ("git log commits", "edit docx",
               "send feishu message", "query datastore documents" ...).

            3. tools.list(category) browses all tools in one category when the
               search keyword is unclear. Available categories:
               PERCEPTION, ACTION, COGNITION, STORAGE, INTERACTION,
               INTROSPECTION, EXTENSION.

            Example:
              User: "查看 git 最近 3 次提交"
              You: tools.search("git log commits") -> finds git.query
                   -> tools.describe(["git.query"]) -> call git.query(action=log, limit=3)
              Do NOT say "I have no git tool, let me use shell.exec".""";

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

    /** Tier 1 分层注入配置。 */
    @Setter
    @Getter
    public static class Tier1 {
        /** 人工固定的 Tier 1 工具 ID 列表（pinned），永不自动降级。 */
        private List<String> pinned = List.of();

        private Promotion promotion = new Promotion();
        private Demotion demotion = new Demotion();

        /** Tier 1 晋升策略 — 基于使用数据生成 advisory 建议，不自动改配置。 */
        @Setter
        @Getter
        public static class Promotion {
            private boolean enabled = true;
            private int windowDays = 30;
            private double sessionThreshold = 0.3;
            private int maxPromoted = 3;
        }

        /** Tier 1 降级策略 — 长期未使用的候选可被降回 Tier 2。 */
        @Setter
        @Getter
        public static class Demotion {
            private boolean enabled = true;
            private int idleDays = 60;
            private boolean respectPinned = true;
        }
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
