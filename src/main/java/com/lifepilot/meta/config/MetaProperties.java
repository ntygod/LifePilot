package com.lifepilot.meta.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 元能力系统配置属性。
 *
 * <p>绑定 {@code lifepilot.meta} 配置前缀，包含基础工具、系统自省、
 * Skill 发现、MCP 安装器和引导 Agent 的全部可调参数。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@Data
@ConfigurationProperties(prefix = "lifepilot.meta")
public class MetaProperties {

    /** 基础工具配置。 */
    private Infra infra = new Infra();

    /** 系统自省配置。 */
    private Introspection introspection = new Introspection();

    /** Skill 发现配置。 */
    private SkillDiscovery skillDiscovery = new SkillDiscovery();

    /** 引导 Agent 配置。 */
    private Onboarding onboarding = new Onboarding();

    /**
     * 基础工具配置 — 包含 Web 搜索、Web 抓取、用户画像、Shell、浏览器、代码执行、文件访问和交互控制。
     *
     * @author zsg
     * @since 2026-03-10
     */
    @Data
    public static class Infra {

        /** 全局工具输出最大字符数，默认 30000。 */
        private int maxToolOutputChars = 30000;

        /** Web 搜索配置。 */
        private WebSearch webSearch = new WebSearch();

        /** Web 抓取配置。 */
        private WebFetch webFetch = new WebFetch();

        /** 用户画像配置。 */
        private UserProfile userProfile = new UserProfile();

        /** Shell 执行配置。 */
        private Shell shell = new Shell();

        /** 浏览器自动化配置。 */
        private Browser browser = new Browser();

        /** 代码执行配置。 */
        private CodeExecute codeExecute = new CodeExecute();

        /** 文件访问配置。 */
        private FileAccess file = new FileAccess();

        /** 交互控制配置。 */
        private Interaction interaction = new Interaction();

        /** 后台进程管理配置。 */
        private Process process = new Process();

        /**
         * Web 搜索配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class WebSearch {

            /** 搜索引擎提供商（google / bing / duckduckgo），默认 duckduckgo。 */
            private String provider = "duckduckgo";

            /** 搜索引擎 API Key，默认空。 */
            private String apiKey = "";

            /** 最大返回结果数，默认 5。 */
            private int maxResults = 5;
        }

        /**
         * Web 抓取配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class WebFetch {

            /** 最大内容长度（字符），默认 50000。 */
            private int maxContentLength = 50000;

            /** HTTP 请求超时（秒），默认 10。 */
            private int timeoutSeconds = 10;
        }

        /**
         * 用户画像配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class UserProfile {

            /** 用户时区，空字符串表示使用系统时区。 */
            private String timezone = "";

            /** 缓存 TTL（秒），默认 300。 */
            private int cacheTtlSeconds = 300;
        }

        /**
         * Shell 执行配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class Shell {

            /** 命令黑名单正则模式列表。 */
            private List<String> commandBlacklist = List.of(
                    "rm\\s+-rf\\s+/", "format\\s+[a-zA-Z]:",
                    "shutdown", "reboot", "mkfs", "dd\\s+if="
            );

            /** 命令执行超时（秒），默认 30。 */
            private int timeoutSeconds = 30;

            /** 输出最大长度（字符），默认 50000。 */
            private int maxOutputLength = 50000;
        }

        /**
         * 浏览器自动化配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class Browser {

            /** 浏览器功能开关，默认 true。 */
            private boolean enabled = true;

            /** 无头模式，默认 true。 */
            private boolean headless = true;

            /** 空闲超时（秒），默认 300（5 分钟）。 */
            private int idleTimeoutSeconds = 300;

            /** 工具执行超时（秒），默认 30。 */
            private int toolTimeoutSeconds = 30;

            /** 浏览器安装超时（秒），默认 600（10 分钟）。 */
            private int installTimeoutSeconds = 600;

            /** 文本快照清洗后最大长度（字符），默认 10000。 */
            private int textSnapshotMaxLength = 10000;

            /** 默认滚动像素数，默认 500。 */
            private int defaultScrollPixels = 500;

            /** 等待元素超时（秒），默认 10。 */
            private int waitTimeoutSeconds = 10;

            /** 无障碍树最大深度，默认 5。 */
            private int accessibilityMaxDepth = 5;

            /** JavaScript 执行超时（秒），默认 10。 */
            private int jsExecutionTimeoutSeconds = 10;
        }

        /**
         * 代码执行配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class CodeExecute {

            /** 代码执行功能开关，默认 true。 */
            private boolean enabled = true;

            /** 默认执行语言，默认 python。 */
            private String defaultLanguage = "python";
        }

        /**
         * 文件访问配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class FileAccess {

            /** 最大读取大小（字节），默认 1048576（1MB）。 */
            private int maxReadSize = 1048576;

            /** file-read 默认最大返回字符数，默认 30000。 */
            private int defaultMaxChars = 30000;

            /** file-list 默认最大返回条目数，默认 200。 */
            private int defaultMaxEntries = 200;

            /** 允许访问的目录白名单，空列表表示用户 home 下所有目录。 */
            private List<String> allowedDirectories = List.of();

            /** 拒绝访问的目录黑名单。 */
            private List<String> deniedDirectories = List.of("/etc", "/var", "C:\\Windows");
        }

        /**
         * 交互控制配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class Interaction {

            /** 用户响应超时（秒），默认 120。 */
            private int responseTimeoutSeconds = 120;
        }

        /**
         * 后台进程管理配置。
         *
         * @author zsg
         * @since 2026-03-20
         */
        @Data
        public static class Process {

            /** 最大并发后台进程数，默认 5。 */
            private int maxConcurrent = 5;

            /** 输出环形缓冲区最大大小（字符），默认 100000。 */
            private int maxOutputBufferSize = 100000;

            /** 空闲超时（分钟），超时后自动清理进程，默认 30。 */
            private int idleTimeoutMinutes = 30;
        }
    }

    /**
     * 系统自省配置。
     *
     * @author zsg
     * @since 2026-03-10
     */
    @Data
    public static class Introspection {

        /** 能力聚合缓存 TTL（秒），默认 60。 */
        private int cacheTtlSeconds = 60;

        /** 事件防抖窗口（毫秒），默认 500。 */
        private int debounceMillis = 500;
    }

    /**
     * Skill 发现配置。
     *
     * @author zsg
     * @since 2026-03-10
     */
    @Data
    public static class SkillDiscovery {

        /** 功能开关，默认 true。 */
        private boolean enabled = true;

        /** 种子 Skill 资源路径列表，启动时提取到用户目录。 */
        private List<String> skillPaths = List.of(
                "skills/find-skills",
                "skills/workflow-creator",
                "skills/memory",
                "skills/infrastructure",
                "skills/introspection",
                "skills/datastore"
        );
    }

    /**
     * 引导 Agent 配置。
     *
     * @author zsg
     * @since 2026-03-10
     */
    @Data
    public static class Onboarding {

        /** 自动触发开关，默认 true。 */
        private boolean autoTrigger = true;

        /** Agent 定义文件路径，默认 preset-agents/onboarding-guide.md。 */
        private String agentDefinition = "preset-agents/onboarding-guide.md";
    }
}
