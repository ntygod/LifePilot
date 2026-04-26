package com.lifepilot.sandbox.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置属性。
 *
 * <p>绑定 {@code lifepilot.sandbox} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
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

    /** 运行时配置（Python 自带运行时 + Shell 命令护栏）。 */
    private Runtime runtime = new Runtime();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBooter() { return booter; }
    public void setBooter(String booter) { this.booter = booter; }

    public List<String> getSupportedLanguages() { return supportedLanguages; }
    public void setSupportedLanguages(List<String> supportedLanguages) { this.supportedLanguages = supportedLanguages; }

    public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(int executionTimeoutSeconds) { this.executionTimeoutSeconds = executionTimeoutSeconds; }

    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }

    public Map<String, String> getRuntimePaths() { return runtimePaths; }
    public void setRuntimePaths(Map<String, String> runtimePaths) { this.runtimePaths = runtimePaths; }

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }

    public Validator getValidator() { return validator; }
    public void setValidator(Validator validator) { this.validator = validator; }

    public Docker getDocker() { return docker; }
    public void setDocker(Docker docker) { this.docker = docker; }

    public Runtime getRuntime() { return runtime; }
    public void setRuntime(Runtime runtime) { this.runtime = runtime; }

    /**
     * 会话配置 — 控制沙箱会话的 TTL、最大数量和清理间隔。
     *
     * @author zsg
     * @since 2026-03-01
     */
    public static class Session {

        /** 会话 TTL（秒），默认 600。 */
        private int ttlSeconds = 600;

        /** 最大活跃会话数，默认 5。 */
        private int maxActiveSessions = 5;

        /** 清理间隔（秒），默认 60。 */
        private int cleanupIntervalSeconds = 60;

        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }

        public int getMaxActiveSessions() { return maxActiveSessions; }
        public void setMaxActiveSessions(int maxActiveSessions) { this.maxActiveSessions = maxActiveSessions; }

        public int getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }
        public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) { this.cleanupIntervalSeconds = cleanupIntervalSeconds; }
    }

    /**
     * 预检配置 — 控制 CodeValidator 的启用状态和拒绝策略。
     *
     * @author zsg
     * @since 2026-03-01
     */
    public static class Validator {

        /** 是否启用预检，默认 true。 */
        private boolean enabled = true;

        /** 是否拒绝 CRITICAL 级别违规，默认 true。 */
        private boolean rejectCritical = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isRejectCritical() { return rejectCritical; }
        public void setRejectCritical(boolean rejectCritical) { this.rejectCritical = rejectCritical; }
    }

    /**
     * Docker 配置 — 控制 DockerBooter 的资源限制和网络策略。
     *
     * @author zsg
     * @since 2026-03-01
     */
    public static class Docker {

        /** 内存限制（MB），默认 256。 */
        private int memoryLimitMb = 256;

        /** CPU 限制（核数），默认 1.0。 */
        private double cpuLimit = 1.0;

        /** 是否启用网络，默认 false。 */
        private boolean networkEnabled = false;

        /** Docker 镜像前缀，默认 zhiwei/sandbox-。 */
        private String imagePrefix = "zhiwei/sandbox-";

        public int getMemoryLimitMb() { return memoryLimitMb; }
        public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }

        public double getCpuLimit() { return cpuLimit; }
        public void setCpuLimit(double cpuLimit) { this.cpuLimit = cpuLimit; }

        public boolean isNetworkEnabled() { return networkEnabled; }
        public void setNetworkEnabled(boolean networkEnabled) { this.networkEnabled = networkEnabled; }

        public String getImagePrefix() { return imagePrefix; }
        public void setImagePrefix(String imagePrefix) { this.imagePrefix = imagePrefix; }
    }

    /**
     * 运行时配置 — 控制自带 Python 运行时下载安装 + Shell 命令护栏行为。
     *
     * @author zsg
     * @since 2026-04-26
     */
    public static class Runtime {

        /** Python 自带运行时配置。 */
        private Python python = new Python();

        /** Shell 命令护栏配置。 */
        private CommandGuardConfig commandGuard = new CommandGuardConfig();

        public Python getPython() { return python; }
        public void setPython(Python python) { this.python = python; }

        public CommandGuardConfig getCommandGuard() { return commandGuard; }
        public void setCommandGuard(CommandGuardConfig commandGuard) { this.commandGuard = commandGuard; }

        /**
         * Python 自带运行时配置 — 离线包版本、下载地址、安装路径与预期库清单。
         *
         * @author zsg
         * @since 2026-04-26
         */
        public static class Python {

            /** 自带 Python 运行时版本，默认 3.12.4。 */
            private String bundledVersion = "3.12.4";

            /** 下载地址模板（含 {version}/{platform}/{arch} 占位符）。 */
            private String downloadUrlTemplate = "";

            /** SHA256 校验文件地址模板（含 {version}/{platform}/{arch} 占位符）。 */
            private String sha256UrlTemplate = "";

            /** 安装路径，默认 ${user.home}/.zhiwei/python。 */
            private String installPath = "${user.home}/.zhiwei/python";

            /** 预期内置数据科学/办公库清单，用于安装后自检。 */
            private List<String> expectedLibraries = List.of(
                "pandas", "numpy", "scipy", "scikit-learn", "matplotlib", "seaborn",
                "openpyxl", "pillow", "python-pptx", "python-docx", "pypdf", "pdfplumber",
                "sympy", "requests", "httpx", "beautifulsoup4");

            /** 是否禁用自带 Python 运行时（true 时回退到系统 PATH 中的 python）。 */
            private boolean disabled = false;

            public String getBundledVersion() { return bundledVersion; }
            public void setBundledVersion(String bundledVersion) { this.bundledVersion = bundledVersion; }

            public String getDownloadUrlTemplate() { return downloadUrlTemplate; }
            public void setDownloadUrlTemplate(String downloadUrlTemplate) { this.downloadUrlTemplate = downloadUrlTemplate; }

            public String getSha256UrlTemplate() { return sha256UrlTemplate; }
            public void setSha256UrlTemplate(String sha256UrlTemplate) { this.sha256UrlTemplate = sha256UrlTemplate; }

            public String getInstallPath() { return installPath; }
            public void setInstallPath(String installPath) { this.installPath = installPath; }

            public List<String> getExpectedLibraries() { return expectedLibraries; }
            public void setExpectedLibraries(List<String> expectedLibraries) { this.expectedLibraries = expectedLibraries; }

            public boolean isDisabled() { return disabled; }
            public void setDisabled(boolean disabled) { this.disabled = disabled; }
        }

        /**
         * Shell 命令护栏配置 — 控制 CommandGuard 的启用状态与 yolo 兜底开关。
         *
         * @author zsg
         * @since 2026-04-26
         */
        public static class CommandGuardConfig {

            /** 是否启用命令护栏，默认 true。 */
            private boolean enabled = true;

            /** 是否启用 yolo 模式（绕过命令护栏，仅用于本地调试），默认 false。 */
            private boolean yoloMode = false;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public boolean isYoloMode() { return yoloMode; }
            public void setYoloMode(boolean yoloMode) { this.yoloMode = yoloMode; }
        }
    }
}
