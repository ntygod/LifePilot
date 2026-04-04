package com.lifepilot.meta.infra.shell;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Shell 进程构建工厂 — 统一 Windows/Unix 下的 ProcessBuilder 创建逻辑。
 *
 * <p>消除 {@link ShellExecToolExecutor} 和 {@link BackgroundProcessManager} 中
 * 重复的 PowerShell EncodedCommand 构建代码，保证一致的进程启动行为。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public final class ShellProcessFactory {

    private static final Logger log = LoggerFactory.getLogger(ShellProcessFactory.class);

    /** 通过环境变量传递命令，避免 PowerShell 参数转义问题。 */
    static final String WINDOWS_COMMAND_ENV = "LIFEPILOT_SHELL_COMMAND";

    /**
     * 环境变量 key 黑名单 — 防止通过 env 参数注入危险环境变量。
     * <p>覆盖这些 key 可绕过命令黑名单或注入恶意库。</p>
     */
    private static final Set<String> BLOCKED_ENV_KEYS = Set.of(
            "PATH", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
            WINDOWS_COMMAND_ENV
    );

    /** 预编译的 PowerShell UTF-16LE EncodedCommand，设置 UTF-8 编码并执行环境变量中的命令。 */
    static final String WINDOWS_POWERSHELL_ENCODED_COMMAND = Base64.getEncoder()
            .encodeToString("""
                    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
                    $OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                    $ErrorActionPreference = 'Stop'
                    $command = $env:LIFEPILOT_SHELL_COMMAND
                    try {
                        Invoke-Expression $command
                        if ($null -ne $LASTEXITCODE) {
                            exit $LASTEXITCODE
                        }
                        exit 0
                    } catch {
                        [Console]::Error.WriteLine($_.Exception.Message)
                        exit 1
                    }
                    """.getBytes(StandardCharsets.UTF_16LE));

    private ShellProcessFactory() {
        // 工具类禁止实例化
    }

    /**
     * 判断当前操作系统是否为 Windows。
     *
     * @return true 表示 Windows 平台
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 创建用于执行 Shell 命令的 ProcessBuilder。
     *
     * <p>Windows 下使用 PowerShell + EncodedCommand，Unix 下默认使用 sh -c。
     * 可通过 {@code shellOverride} 指定其他解释器（bash/zsh 等）。</p>
     *
     * @param command       Shell 命令
     * @param workDir       工作目录
     * @param shellOverride Unix 下的 Shell 覆盖（如 "bash"、"zsh"），null 时使用默认 sh
     * @param pty           是否分配伪终端（仅 Unix 生效）
     * @param env           额外环境变量，null 时不注入
     * @return 配置好的 ProcessBuilder
     */
    public static ProcessBuilder createShellProcess(String command,
                                                     Path workDir,
                                                     @Nullable String shellOverride,
                                                     boolean pty,
                                                     @Nullable Map<String, String> env) {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = createWindowsProcess(command);
        } else {
            pb = createUnixProcess(command, shellOverride, pty);
        }
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        // 注入额外环境变量（过滤危险 key）
        if (env != null && !env.isEmpty()) {
            env.forEach((key, value) -> {
                if (BLOCKED_ENV_KEYS.contains(key.toUpperCase())) {
                    log.warn("环境变量被安全策略拦截: key={}", key);
                } else {
                    pb.environment().put(key, value);
                }
            });
        }
        return pb;
    }

    /**
     * 创建用于执行 Shell 命令的 ProcessBuilder（无额外选项的简洁版）。
     *
     * @param command Shell 命令
     * @param workDir 工作目录
     * @return 配置好的 ProcessBuilder
     */
    public static ProcessBuilder createShellProcess(String command, Path workDir) {
        return createShellProcess(command, workDir, null, false, null);
    }

    private static ProcessBuilder createWindowsProcess(String command) {
        var pb = new ProcessBuilder(
                "powershell",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                WINDOWS_POWERSHELL_ENCODED_COMMAND
        );
        pb.environment().put(WINDOWS_COMMAND_ENV, command);
        return pb;
    }

    private static ProcessBuilder createUnixProcess(String command, @Nullable String shellOverride, boolean pty) {
        String shell = shellOverride != null ? shellOverride : "sh";

        if (pty) {
            // Unix 下通过 script 命令分配伪终端，纳入指定的 shell 解释器
            String wrappedCommand = shell + " -c " + escapeForShell(command);
            return new ProcessBuilder("script", "-qec", wrappedCommand, "/dev/null");
        }
        return new ProcessBuilder(shell, "-c", command);
    }

    /**
     * 将命令字符串用单引号包裹并转义内部单引号，用于嵌套到外层 shell -c 调用。
     */
    private static String escapeForShell(String command) {
        // 单引号包裹：将内部的 ' 替换为 '\''（关闭引号、转义引号、重开引号）
        return "'" + command.replace("'", "'\\''") + "'";
    }
}
