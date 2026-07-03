package com.lifepilot.sandbox.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱配置属性。
 *
 * <p>绑定 {@code lifepilot.sandbox} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.sandbox")
public class SandboxConfigProperties {

    /** 是否启用沙箱，默认 true。 */
    private boolean enabled = true;

    /** 沙箱类型：process / docker，默认 process。 */
    private String booter = "process";

    /** 支持的语言列表，默认 [python, javascript, shell]。 */
    private List<String> supportedLanguages = List.of("python", "javascript", "shell");

    /** 执行超时（秒），默认 30。 */
    private int executionTimeoutSeconds = 30;

    /** 输出最大字节数，默认 65536。 */
    private int maxOutputBytes = 65536;

    /** 语言运行时路径，默认 python3 / node / bash。 */
    private Map<String, String> runtimePaths = new HashMap<>(Map.of(
        "python", "python",
        "javascript", "node",
        "shell", "bash"
    ));

    /** 会话配置。 */
    private Session session = new Session();

    /** 预检配置。 */
    private Validator validator = new Validator();

    /** Docker 配置。 */
    private Docker docker = new Docker();

    /** 运行时配置（捆绑 Python 运行时 + Shell 命令护栏）。 */
    private Runtime runtime = new Runtime();

    /**
     * 会话配置 — 控制沙箱会话的 TTL、最大数量和清理间隔。
     *
     * @author zsg
     * @since 2026-03-01
     */
    @Setter
    @Getter
    public static class Session {

        /** 会话 TTL（秒），默认 600。 */
        private int ttlSeconds = 600;

        /** 最大活跃会话数，默认 5。 */
        private int maxActiveSessions = 5;

        /** 清理间隔（秒），默认 60。 */
        private int cleanupIntervalSeconds = 60;

        /** 会话工作目录物理保留天数，超期后由 SessionDirectoryCleanupJob 清理。默认 30 天。 */
        private int directoryRetentionDays = 30;

    }

    /**
     * 预检配置 — 控制 CodeValidator 的启用状态和拒绝策略。
     *
     * @author zsg
     * @since 2026-03-01
     */
    @Setter
    @Getter
    public static class Validator {

        /** 是否启用预检，默认 true。 */
        private boolean enabled = true;

        /** 是否拒绝 CRITICAL 级别违规，默认 true。 */
        private boolean rejectCritical = true;

    }

    /**
     * Docker 配置 — 控制 DockerBooter 的资源限制和网络策略。
     *
     * @author zsg
     * @since 2026-03-01
     */
    @Setter
    @Getter
    public static class Docker {

        /** 内存限制（MB），默认 256。 */
        private int memoryLimitMb = 256;

        /** CPU 限制（核数），默认 1.0。 */
        private double cpuLimit = 1.0;

        /** 是否启用网络，默认 false。 */
        private boolean networkEnabled = false;

        /** Docker 镜像前缀，默认 zhiwei/sandbox-。 */
        private String imagePrefix = "zhiwei/sandbox-";

    }

    /**
     * 运行时配置 — 控制捆绑 Python 运行时的下载安装与命令护栏行为。
     *
     * @author zsg
     * @since 2026-04-26
     */
    @Setter
    @Getter
    public static class Runtime {

        /** Python 捆绑运行时配置。 */
        private Python python = new Python();

        /** Shell 命令护栏配置。 */
        private CommandGuardConfig commandGuard = new CommandGuardConfig();

        /**
         * Python 捆绑运行时配置 — 离线包版本、下载地址、安装路径与预期库清单。
         *
         * @author zsg
         * @since 2026-04-26
         */
        @Setter
        @Getter
        public static class Python {

            /** 捆绑 Python 运行时版本，默认 3.12.13。 */
            private String bundledVersion = "3.12.13";

            /** 下载地址模板（含 {version}/{platform}/{arch} 占位符）。 */
            private String downloadUrlTemplate = "";

            /** SHA256 校验文件地址模板（含 {version}/{platform}/{arch} 占位符）。 */
            private String sha256UrlTemplate = "";

            /** 预期内置数据科学/办公库清单，用于安装后自检。 */
            private List<String> expectedLibraries = List.of(
                "pandas", "numpy", "scipy", "scikit-learn", "matplotlib", "seaborn",
                "openpyxl", "pillow", "python-pptx", "python-docx", "pypdf", "pdfplumber",
                "sympy", "requests", "httpx", "beautifulsoup4");

            /** 是否禁用捆绑 Python 运行时（true 时回退到系统 PATH 中的 python）。 */
            private boolean disabled = false;

        }

        /**
         * 命令护栏配置 — 控制 HARDLINE / DANGEROUS 两层阻断的开关。
         *
         * <p><b>命名说明</b>：故意保留 {@code Config} 后缀，区别于独立的命令审查主类
         * {@code com.lifepilot.sandbox.guard.CommandGuard}（后续 Task 实现），避免短名歧义。</p>
         *
         * @author zsg
         * @since 2026-04-26
         */
        @Setter
        @Getter
        public static class CommandGuardConfig {

            /** 是否启用命令护栏，默认 true。 */
            private boolean enabled = true;

            /** 是否启用 yolo 模式（DANGEROUS 自动放行，HARDLINE 仍拦截；仅用于本地调试），默认 false。 */
            private boolean yoloMode = false;

        }
    }
}
