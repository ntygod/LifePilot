package com.lifepilot.meta.config;

import com.lifepilot.meta.infra.browser.BrowserAcquisitionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


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
     * 基础工具配置 — 包含 Web 搜索、Web 抓取、Shell、浏览器、代码执行、文件访问和交互控制。
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

        /** Git 工具配置。 */
        private Git git = new Git();

        /** 文件编辑历史配置。 */
        private FileEdit fileEdit = new FileEdit();

        /** Shell 持久会话配置。 */
        private ShellSession shellSession = new ShellSession();

        /** 持久代码内核配置。 */
        private Kernel kernel = new Kernel();

        /**
         * Web 搜索配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class WebSearch {

            /** Tavily Search API 地址。 */
            private String apiUrl = "https://api.tavily.com/search";

            /** 搜索引擎提供商，当前固定为 tavily。 */
            private String provider = "tavily";

            /** 搜索引擎 API Key，默认空。 */
            private String apiKey = "";

            /** 最大返回结果数，默认 5。 */
            private int maxResults = 5;

            /** 搜索深度（basic / advanced），默认 basic。 */
            private String searchDepth = "basic";

            /** 搜索主题（general / news / finance），默认 general。 */
            private String topic = "general";

            /** 是否返回 Tavily answer 摘要，默认 true。 */
            private boolean includeAnswer = true;

            /** HTTP 连接超时（秒），默认 10。 */
            private int connectTimeoutSeconds = 10;

            /** HTTP 读取超时（秒），默认 30。 */
            private int readTimeoutSeconds = 30;
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

            /** 静态抓取内容低于此长度时触发浏览器渲染回退（字符），默认 100。 */
            private int minStaticContentLength = 100;

            /** 浏览器渲染超时（秒），默认 15。 */
            private int renderTimeoutSeconds = 15;

            /** SSRF 防护配置。 */
            private Ssrf ssrf = new Ssrf();

            /**
             * SSRF 防护配置 — 拦截内网地址、云 metadata 和非 http(s) 协议。
             *
             * @author zsg
             * @since 2026-04-24
             */
            @Data
            public static class Ssrf {

                /** 是否启用 SSRF 拦截，默认 true。 */
                private boolean enabled = true;

                /**
                 * 放行的 host / IP 字面量列表，企业内网场景补充受信任目标。
                 * <p>匹配发生在 DNS 解析前（host 文本）和 IP 校验后（IP 文本），命中任一即放行。</p>
                 */
                private List<String> allowlist = new ArrayList<>();
            }
        }

        /**
         * Shell 执行配置。
         *
         * @author zsg
         * @since 2026-03-10
         */
        @Data
        public static class Shell {

            /**
             * 命令黑名单正则模式列表。
             * <p>使用单词边界匹配避免误杀包含关键词的合法命令。</p>
             */
            private List<String> commandBlacklist = List.of(
                    "rm\\s+-rf\\s+/(?!\\S)",       // rm -rf / 但不匹配 rm -rf /tmp/xxx
                    "\\bformat\\s+[a-zA-Z]:",       // format C: 等磁盘格式化
                    "(?:^|[;&|])\\s*shutdown\\b",   // shutdown 命令（排除子串匹配）
                    "(?:^|[;&|])\\s*reboot\\b",     // reboot 命令
                    "\\bmkfs\\b",                   // mkfs / mkfs.ext4 等文件系统格式化
                    "\\bdd\\s+if=",                 // dd 磁盘写入
                    ":\\(\\)\\{\\s*:|:&\\s*\\};:",   // fork bomb
                    "\\bchmod\\s+-R\\s+777\\s+/"    // 递归 777 根目录
            );

            /** 命令执行超时（秒），默认 120。 */
            private int timeoutSeconds = 120;

            /** 输出最大长度（字符），默认 50000。 */
            private int maxOutputLength = 50000;

            /**
             * yieldMs — 同步模式下进程结束后等待输出刷新的毫秒数，默认 200。
             * <p>参考 OpenClaw exec 工具的 yieldMs 机制，解决进程退出后输出流延迟刷新的问题。</p>
             */
            private int yieldMs = 200;

            /**
             * 输出读取超时（秒），默认为 timeoutSeconds + 5。
             * <p>防止进程被 destroyForcibly() 后输出流未关闭导致 readAllBytes() 永久阻塞。</p>
             */
            private int outputReadTimeoutSeconds = 0; // 0 表示自动计算为 timeoutSeconds + 5

            /** 瞬时故障最大重试次数，默认 1。仅对进程启动失败等瞬时故障重试。 */
            private int transientRetries = 1;
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

            /**
             * 自定义 User-Agent。
             *
             * <p>默认 {@code "auto"}：运行时读取 Chromium 版本号动态拼 UA，
             * 避免硬编码漂移导致的反爬指纹识别。配置任意其它字符串 → 原样使用。
             * 拼接细节见 {@link com.lifepilot.meta.infra.browser.UserAgentBuilder}。</p>
             */
            private String userAgent = "auto";

            /** 视口宽度（像素），默认 1920。 */
            private int viewportWidth = 1920;

            /** 视口高度（像素），默认 1080。 */
            private int viewportHeight = 1080;

            /** 浏览器语言区域，默认 zh-CN。 */
            private String locale = "zh-CN";

            /** 时区 ID，默认 Asia/Shanghai。 */
            private String timezoneId = "Asia/Shanghai";

            /** 是否启用反检测隐身模式，默认 true。 */
            private boolean stealthMode = true;

            /** 额外 Chromium 启动参数。 */
            private List<String> extraLaunchArgs = List.of();

            /** storageState 持久化目录，空字符串关闭持久化。 */
            private String storageStateDir = "";

            /** 是否在会话关闭时自动保存 storageState，默认 false。 */
            private boolean persistStorageState = false;

            /** 操作前最小随机延迟（毫秒），默认 100。设为 0 关闭。 */
            private int humanDelayMinMs = 100;

            /** 操作前最大随机延迟（毫秒），默认 500。 */
            private int humanDelayMaxMs = 500;

            /** 浏览器获取模式，默认 LAUNCH。可选 CDP（连接已有 Chrome）、PERSISTENT（持久化 profile）。 */
            private BrowserAcquisitionMode acquisitionMode = BrowserAcquisitionMode.LAUNCH;

            /** CDP 端点 URL，仅 CDP 模式使用（如 http://localhost:9222）。 */
            private String cdpUrl = "";

            /** Chrome 用户数据目录，仅 PERSISTENT 模式使用。 */
            private String userDataDir = "";

            /** 页面标号快照配置。 */
            private Snapshot snapshot = new Snapshot();

            /** 人工接管挂起配置。 */
            private Takeover takeover = new Takeover();

            /**
             * 页面标号快照配置 — 控制 browser.snapshot 返回的元素数量、范围和视觉标签。
             *
             * @author zsg
             * @since 2026-04-24
             */
            @Data
            public static class Snapshot {

                /** 单次 snapshot 最大返回元素数，默认 200。 */
                private int maxElements = 200;

                /** true 则默认只截 viewport，false 截全页，默认 true。 */
                private boolean viewportOnly = true;

                /** 是否叠加视觉编号标签（桌面 headless=false 场景建议 true），默认 false。 */
                private boolean injectLabels = false;
            }

            /**
             * 人工接管挂起配置 — 控制 browser.requestHumanTakeover 相关行为。
             *
             * @author zsg
             * @since 2026-04-24
             */
            @Data
            public static class Takeover {

                /** 挂起等待超时（秒），默认 300（5 分钟）。超时后可由上层强制恢复或失败。 */
                private int timeoutSeconds = 300;
            }
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

            /** 允许访问的目录白名单，空列表表示不额外限制目录范围。 */
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

        /**
         * Git 工具配置。
         *
         * @author zsg
         * @since 2026-03-31
         */
        @Data
        public static class Git {

            /** 功能开关，默认 true。 */
            private boolean enabled = true;

            /** Git 命令执行超时（秒），默认 30。 */
            private int timeoutSeconds = 30;

            /** diff 输出最大行数，默认 500。 */
            private int maxDiffLines = 500;

            /** log 最大条目数，默认 50。 */
            private int maxLogEntries = 50;

            /** blame 最大行数，默认 200。 */
            private int maxBlameLines = 200;

            /** 输出最大字符数，默认 50000。 */
            private int maxOutputChars = 50000;
        }

        /**
         * 文件编辑历史配置。
         *
         * @author zsg
         * @since 2026-03-31
         */
        @Data
        public static class FileEdit {

            /** undo 栈最大深度，默认 50。 */
            private int undoMaxDepth = 50;

            /** 是否在文件写入后自动执行 lint 检查，默认 false。 */
            private boolean autoLint = false;

            /** 文件扩展名 → lint 命令映射，命令中 {file} 占位符会被替换为实际路径。 */
            private Map<String, String> lintCommands = new LinkedHashMap<>();

            /** lint 命令执行超时（秒），默认 10。 */
            private int lintTimeoutSeconds = 10;

            /** 单文件快照最大字节数，超过则跳过快照，默认 5MB。 */
            private long maxSnapshotSizeBytes = 5 * 1024 * 1024;
        }

        /**
         * Shell 持久会话配置。
         *
         * @author zsg
         * @since 2026-03-31
         */
        @Data
        public static class ShellSession {

            /** 功能开关，默认 true。 */
            private boolean enabled = true;

            /** 最大并发会话数，默认 5。 */
            private int maxConcurrentSessions = 5;

            /** 空闲超时（分钟），超时后自动清理会话，默认 30。 */
            private int ttlMinutes = 30;

            /** 默认终端列数，默认 120。 */
            private int defaultCols = 120;

            /** 默认终端行数，默认 40。 */
            private int defaultRows = 40;

            /** 历史行数，capture-pane 回溯行数，默认 2000。 */
            private int historyLines = 2000;

            /** 命令执行超时（秒），默认 120。 */
            private int execTimeoutSeconds = 120;

            /** 输出最大字符数，默认 50000。 */
            private int outputMaxChars = 50000;

            /** 空闲清理调度间隔（秒），默认 60。 */
            private int cleanupIntervalSeconds = 60;
        }

        /**
         * 持久代码内核配置。
         *
         * @author zsg
         * @since 2026-03-31
         */
        @Data
        public static class Kernel {

            /** 功能开关，默认 true。 */
            private boolean enabled = true;

            /** 最大并发内核数，默认 3。 */
            private int maxConcurrentKernels = 3;

            /** 空闲超时（分钟），超时后自动清理内核，默认 30。 */
            private int ttlMinutes = 30;

            /** 空闲清理调度间隔（秒），默认 60。 */
            private int cleanupIntervalSeconds = 60;

            /** 默认执行超时（秒），默认 60。 */
            private int executionTimeoutSeconds = 60;

            /** Python 运行时路径，默认 python3。 */
            private String pythonRuntime = "python3";

            /** Node.js 运行时路径，默认 node。 */
            private String nodeRuntime = "node";

            /** 输出最大字符数，默认 50000。 */
            private int maxOutputChars = 50000;
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
