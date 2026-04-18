package com.lifepilot.meta.infra.shell;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.config.workspace.WorkspaceResolver.NormalizedPath;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultMeta;
import com.lifepilot.tool.model.ToolResultStatus;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Shell 命令执行工具 — 通过 {@link ShellProcessFactory} 启动子进程执行命令。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel HIGH — GuardrailEngine 触发用户确认</li>
 *   <li>命令黑名单 — 正则模式匹配，匹配时直接拒绝</li>
 *   <li>超时强制 — {@code Process.waitFor(timeout)} + {@code destroyForcibly()}</li>
 *   <li>输出截断 — stdout/stderr 超过 maxOutputLength 时截断</li>
 * </ul>
 *
 * <p>支持 {@code background=true} 参数，委托 {@link BackgroundProcessManager}
 * 启动后台进程并立即返回 sessionId。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ShellExecToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellExecToolExecutor.class);
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual().start(command);

    private final MetaProperties.Infra.Shell shellConfig;
    private final List<Pattern> compiledBlacklist;
    @Nullable
    private final BackgroundProcessManager backgroundProcessManager;
    private final WorkspaceResolver workspaceResolver;

    public ShellExecToolExecutor(MetaProperties properties,
                                  @Nullable BackgroundProcessManager backgroundProcessManager,
                                  WorkspaceResolver workspaceResolver) {
        this.shellConfig = properties.getInfra().getShell();
        this.backgroundProcessManager = backgroundProcessManager;
        this.workspaceResolver = workspaceResolver;
        // 构造时编译正则模式，避免每次执行重复编译
        this.compiledBlacklist = shellConfig.getCommandBlacklist().stream()
                .map(Pattern::compile)
                .toList();
    }

    /**
     * 执行 Shell 命令。
     *
     * @param input 工具输入，必需参数 command，可选 workingDirectory、timeoutSeconds、env、shell 等
     * @return 包含 stdout、stderr、exitCode 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        // 提取参数
        String command;
        try {
            command = input.getParam("command", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: command");
        }

        // 工作目录：委托 WorkspaceResolver 统一规范化（空/盘根/相对路径都会回退默认）
        NormalizedPath workDirInfo = workspaceResolver.normalizeWithInfo(
                input.getOptionalParam("workingDirectory", String.class).orElse(null));
        Path workDirResolved = workDirInfo.path();
        String workingDirectory = workDirResolved.toString();

        int timeoutSeconds = input.getOptionalParam("timeoutSeconds", Number.class)
                .map(Number::intValue)
                .orElse(shellConfig.getTimeoutSeconds());

        boolean pty = input.getOptionalParam("pty", Boolean.class).orElse(false);

        // 新增参数：Shell 解释器覆盖
        String shellOverride = input.getOptionalParam("shell", String.class).orElse(null);

        // 新增参数：环境变量注入
        @SuppressWarnings("unchecked")
        Map<String, String> userEnv = input.getOptionalParam("env", Map.class).orElse(null);
        // 自动补齐 CLAUDE_CODE_GIT_BASH_PATH（当命令是 claude/codex 且用户已在设置里配置时）
        Map<String, String> env = mergeExternalCliEnv(command, userEnv);

        // 黑名单检查
        var rejection = checkBlacklist(command);
        if (rejection != null) {
            return rejection;
        }

        // 验证工作目录存在（normalizeWorkingDirectory 已保证非空且为绝对路径）
        Path workDir = workDirResolved;
        if (!Files.isDirectory(workDir)) {
            return ToolResult.error("工作目录不存在: " + workingDirectory);
        }

        // Windows 下 PTY 不支持，降级警告
        if (pty && ShellProcessFactory.isWindows()) {
            log.warn("Windows 平台暂不支持 PTY 模式，降级为普通执行: command={}", command);
            pty = false;
        }

        // 后台执行模式（background=true 立即后台化）
        boolean background = input.getOptionalParam("background", Boolean.class).orElse(false);
        if (background) {
            return executeBackground(command, workDirInfo, env);
        }

        // yieldMs 模式：同步等待 yieldMs 毫秒，如果进程未结束则自动转后台
        int yieldMs = input.getOptionalParam("yieldMs", Number.class)
                .map(Number::intValue)
                .orElse(-1); // -1 表示不使用 yieldMs，走纯同步模式
        if (yieldMs >= 0) {
            return executeWithYield(command, workDirInfo, yieldMs, env);
        }

        // 纯同步执行命令
        try {
            return executeCommand(command, workDirInfo, timeoutSeconds, pty, shellOverride, env);
        } catch (IOException e) {
            log.error("Shell 命令执行失败: command={}, error={}", command, e.getMessage(), e);
            return ToolResult.error("命令执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.transientError("命令执行被中断");
        }
    }

    /**
     * 检测命令是否需要 Unix bash（Claude Code / Codex 等外部 CLI 在 Windows 上依赖）。
     *
     * <p>判定：命令开头或空格分隔 token 是否为 {@code claude} / {@code codex}。
     * 匹配到时，后续 {@link #mergeExternalCliEnv} 会注入 CLAUDE_CODE_GIT_BASH_PATH env。</p>
     */
    private static boolean commandNeedsBash(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase().trim();
        return lower.startsWith("claude") || lower.startsWith("codex")
                || lower.contains(" claude ") || lower.contains(" codex ")
                || lower.contains("/claude ") || lower.contains("\\claude ")
                || lower.contains("/codex ") || lower.contains("\\codex ");
    }

    /**
     * 为需要 bash 的 CLI 命令自动注入 CLAUDE_CODE_GIT_BASH_PATH 环境变量。
     *
     * <p>策略：</p>
     * <ul>
     *   <li>命令不是 claude/codex → 原样返回</li>
     *   <li>用户在设置里没配 bash 路径 → 原样返回（让 CLI 报错暴露问题）</li>
     *   <li>用户 env 里已显式设了 CLAUDE_CODE_GIT_BASH_PATH → 不覆盖（显式优先）</li>
     *   <li>否则 → 合并注入</li>
     * </ul>
     */
    private Map<String, String> mergeExternalCliEnv(String command, @Nullable Map<String, String> userEnv) {
        if (!commandNeedsBash(command)) {
            return userEnv;
        }
        String bashPath = workspaceResolver.getExternalCliBashPath();
        if (bashPath == null) {
            return userEnv;
        }
        if (userEnv != null && userEnv.containsKey("CLAUDE_CODE_GIT_BASH_PATH")) {
            return userEnv;
        }
        var merged = new LinkedHashMap<String, String>();
        if (userEnv != null) {
            merged.putAll(userEnv);
        }
        merged.put("CLAUDE_CODE_GIT_BASH_PATH", bashPath);
        log.debug("已为命令自动注入 CLAUDE_CODE_GIT_BASH_PATH: command={}", command);
        return Map.copyOf(merged);
    }

    /**
     * 构建工作目录回退警告字符串 — 当 NormalizedPath.replaced=true 时使用。
     *
     * <p>向 LLM 透明告知"你传的值被回退了、原因是什么、下次建议怎么做"，
     * 避免 LLM 基于"我传了 C:\\ 所以进程在 C:\\ 跑"的错误前提继续推理。</p>
     */
    private static String buildWorkDirWarning(NormalizedPath info) {
        return "你传入的 workingDirectory='" + info.originalInput()
                + "' 无效（" + info.reason()
                + "），已回退到默认工作目录 '" + info.path()
                + "'。下次建议不传此参数使用默认值";
    }

    /**
     * 检查命令是否匹配黑名单。
     *
     * @param command 待检查的命令
     * @return 匹配时返回拒绝结果，不匹配返回 null
     */
    ToolResult checkBlacklist(String command) {
        for (Pattern pattern : compiledBlacklist) {
            if (pattern.matcher(command).find()) {
                log.warn("Shell 命令被黑名单拒绝: command={}, pattern={}", command, pattern.pattern());
                return ToolResult.error("命令被安全策略拒绝: 匹配黑名单规则 [" + pattern.pattern() + "]");
            }
        }
        return null;
    }

    /**
     * 截断超长输出。
     *
     * @param output 原始输出
     * @param maxLength 最大长度
     * @return 截断后的输出（如有截断则附加提示）
     */
    String truncateOutput(String output, int maxLength) {
        if (output.length() <= maxLength) {
            return output;
        }
        int originalLength = output.length();
        return output.substring(0, maxLength) + "...[输出已截断，原始长度: " + originalLength + " 字符]";
    }

    private ToolResult executeBackground(String command, NormalizedPath workDirInfo,
                                          @Nullable Map<String, String> env) {
        if (backgroundProcessManager == null) {
            return ToolResult.error("后台进程管理器不可用，改用同步模式（不传 background）");
        }
        try {
            Path workDir = workDirInfo.path();
            String sessionId = backgroundProcessManager.startProcess(command, workDir, env);
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("effectiveWorkingDirectory", workDir.toAbsolutePath().normalize().toString());
            data.put("message", "后台进程已启动，使用 process.output 读取输出，process.kill 终止进程");
            if (workDirInfo.replaced()) {
                data.put("workingDirectoryWarning", buildWorkDirWarning(workDirInfo));
            }
            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("后台进程启动失败: command={}, error={}", command, e.getMessage(), e);
            return ToolResult.error("后台进程启动失败: " + e.getMessage());
        }
    }

    /**
     * yieldMs 模式 — 同步等待指定毫秒数，如果进程未结束则自动转后台。
     *
     * <p>参考 OpenClaw exec 工具的 yieldMs 机制：
     * <ul>
     *   <li>yieldMs=0 等同于 background=true（立即后台化）</li>
     *   <li>yieldMs=N 先同步等待 N 毫秒，进程在此期间完成则返回同步结果</li>
     *   <li>N 毫秒后进程仍在运行，则将其转为后台进程并返回 sessionId</li>
     * </ul></p>
     */
    private ToolResult executeWithYield(String command, NormalizedPath workDirInfo, int yieldMs,
                                         @Nullable Map<String, String> env) {
        if (backgroundProcessManager == null) {
            return ToolResult.error("后台进程管理器不可用，改用同步模式（不传 yieldMs）");
        }

        // yieldMs=0 等同于 background=true
        if (yieldMs == 0) {
            return executeBackground(command, workDirInfo, env);
        }

        Path workDir = workDirInfo.path();
        String effectiveWorkDir = workDir.toAbsolutePath().normalize().toString();

        try {
            // 先启动为后台进程
            String sessionId = backgroundProcessManager.startProcess(command, workDir, env);

            // 等待进程完成或 yieldMs 超时（进程提前退出时立即返回，不浪费等待时间）
            long cappedYieldMs = Math.min(yieldMs, 120_000L); // 上限 120 秒
            boolean finished = backgroundProcessManager.awaitCompletion(
                    sessionId, cappedYieldMs, TimeUnit.MILLISECONDS);

            if (finished) {
                // 进程已完成，获取最新状态
                var processInfo = backgroundProcessManager.getProcessInfo(sessionId);
                // 进程已完成，收集输出并返回同步结果
                ProcessOutputChunk outputChunk = backgroundProcessManager.readOutputChunk(sessionId);
                int exitCode = processInfo.exitCode() != null
                        ? processInfo.exitCode()
                        : processInfo.state() == ProcessState.COMPLETED ? 0 : 1;
                String stdout = truncateOutput(outputChunk.stdout(), shellConfig.getMaxOutputLength());
                String stderr = truncateOutput(outputChunk.stderr(), shellConfig.getMaxOutputLength());
                String output = truncateOutput(outputChunk.output(), shellConfig.getMaxOutputLength());
                var data = new LinkedHashMap<String, Object>();
                data.put("exitCode", exitCode);
                data.put("stdout", stdout);
                data.put("stderr", stderr);
                data.put("output", output);
                data.put("effectiveWorkingDirectory", effectiveWorkDir);
                if (workDirInfo.replaced()) {
                    data.put("workingDirectoryWarning", buildWorkDirWarning(workDirInfo));
                }

                log.debug("yieldMs 模式: 进程在等待期间完成, sessionId={}, state={}", sessionId, processInfo.state());

                if (processInfo.state() != ProcessState.COMPLETED) {
                    String errorMessage = !stderr.isBlank() ? stderr : stdout;
                    return new ToolResult(
                            ToolResultStatus.ERROR,
                            Map.copyOf(data),
                            "命令执行失败 (exitCode=" + exitCode + "): " + errorMessage,
                            ToolResultMeta.empty()
                    );
                }
                return ToolResult.success(Map.copyOf(data));
            }

            // 进程仍在运行，返回后台 sessionId
            log.info("yieldMs 模式: 进程在 {}ms 后仍在运行，自动转后台, sessionId={}, command={}",
                    yieldMs, sessionId, command);
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("backgrounded", true);
            data.put("effectiveWorkingDirectory", effectiveWorkDir);
            data.put("message", "命令在 " + yieldMs + "ms 内未完成，已自动转为后台执行。使用 process.output 读取输出");
            if (workDirInfo.replaced()) {
                data.put("workingDirectoryWarning", buildWorkDirWarning(workDirInfo));
            }
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("yieldMs 模式启动失败: command={}, error={}", command, e.getMessage(), e);
            return ToolResult.error("命令启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.transientError("命令执行被中断");
        }
    }

    private ToolResult executeCommand(String command, NormalizedPath workDirInfo, int timeoutSeconds,
                                       boolean pty, @Nullable String shellOverride,
                                       @Nullable Map<String, String> env)
            throws IOException, InterruptedException {

        int maxRetries = shellConfig.getTransientRetries();
        IOException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return doExecuteCommand(command, workDirInfo, timeoutSeconds, pty, shellOverride, env);
            } catch (IOException e) {
                lastException = e;
                if (attempt < maxRetries && isTransientFailure(e)) {
                    log.warn("Shell 命令瞬时故障，准备重试: command={}, attempt={}, error={}",
                            command, attempt + 1, e.getMessage());
                    Thread.sleep(500L * (attempt + 1)); // 简单退避
                } else {
                    throw e;
                }
            }
        }
        throw lastException; // 不可达，但编译器需要
    }

    /**
     * 判断是否为可重试的瞬时故障。
     * <p>仅对进程启动失败等瞬时问题重试，命令本身执行失败（exitCode!=0）不重试。</p>
     */
    private boolean isTransientFailure(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        // 进程启动失败、资源不足等瞬时问题
        return msg.contains("Cannot run program")
                || msg.contains("Too many open files")
                || msg.contains("Resource temporarily unavailable")
                || msg.contains("No such file or directory"); // shell 可执行文件临时不可用
    }

    private ToolResult doExecuteCommand(String command, NormalizedPath workDirInfo, int timeoutSeconds,
                                         boolean pty, @Nullable String shellOverride,
                                         @Nullable Map<String, String> env)
            throws IOException, InterruptedException {

        Path workDir = workDirInfo.path();
        // 通过工厂创建进程（消除重复的 PowerShell/Unix 构建逻辑）
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                command, workDir, shellOverride, pty, env);

        log.debug("执行 Shell 命令: command={}, workDir={}, timeout={}s, shell={}",
                command, workDir, timeoutSeconds, shellOverride);

        Process process = pb.start();

        // 计算输出读取超时：配置值 > 0 时使用配置值，否则自动计算为 timeoutSeconds + 5
        int outputReadTimeout = shellConfig.getOutputReadTimeoutSeconds() > 0
                ? shellConfig.getOutputReadTimeoutSeconds()
                : timeoutSeconds + 5;

        // 在独立线程中读取 stdout/stderr，带超时保护防止永久阻塞
        var stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException e) { return ""; }
        }, VIRTUAL_EXECUTOR);
        var stderrFuture = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException e) { return ""; }
        }, VIRTUAL_EXECUTOR);

        // 等待进程完成，超时则强制终止
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            // 强制终止后也要带超时地收集已有输出
            String partialStdout = getOutputSafe(stdoutFuture, 3);
            String partialStderr = getOutputSafe(stderrFuture, 3);
            log.warn("Shell 命令超时被终止: command={}, timeout={}s", command, timeoutSeconds);
            var msg = "命令执行超时（" + timeoutSeconds + " 秒），已强制终止";
            if (!partialStdout.isBlank() || !partialStderr.isBlank()) {
                msg += "\n--- 超时前的部分输出 ---\n"
                        + truncateOutput(partialStdout + partialStderr, shellConfig.getMaxOutputLength() / 2);
            }
            return ToolResult.error(msg);
        }

        // 带超时保护地获取输出（进程已完成，readAllBytes 会等待 EOF 后返回，无需额外 sleep）
        String stdout = getOutputSafe(stdoutFuture, outputReadTimeout);
        String stderr = getOutputSafe(stderrFuture, outputReadTimeout);
        int exitCode = process.exitValue();

        // 截断输出
        int maxOutputLength = shellConfig.getMaxOutputLength();
        stdout = truncateOutput(stdout, maxOutputLength);
        stderr = truncateOutput(stderr, maxOutputLength);

        var data = new LinkedHashMap<String, Object>();
        data.put("exitCode", exitCode);
        data.put("stdout", stdout);
        data.put("stderr", stderr);
        data.put("effectiveWorkingDirectory", workDir.toAbsolutePath().normalize().toString());
        if (workDirInfo.replaced()) {
            data.put("workingDirectoryWarning", buildWorkDirWarning(workDirInfo));
        }

        log.debug("Shell 命令执行完成: command={}, exitCode={}, stdoutLen={}, stderrLen={}",
                command, exitCode, stdout.length(), stderr.length());

        // exitCode != 0 时返回错误结果，让 LLM 感知命令执行失败
        if (exitCode != 0) {
            return ToolResult.error("命令执行失败 (exitCode=" + exitCode + "): "
                    + (!stderr.isBlank() ? stderr : stdout));
        }

        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 带超时保护地获取 CompletableFuture 的输出结果。
     * <p>防止进程被 destroyForcibly() 后输出流未关闭导致永久阻塞。</p>
     *
     * @param future 输出读取 Future
     * @param timeoutSeconds 超时秒数
     * @return 输出内容，超时时返回空字符串
     */
    private String getOutputSafe(CompletableFuture<String> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("输出读取超时（{}秒），可能存在流未关闭问题", timeoutSeconds);
            future.cancel(true);
            return "[输出读取超时]";
        } catch (Exception e) {
            log.debug("输出读取异常: {}", e.getMessage());
            return "";
        }
    }

}
