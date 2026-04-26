package com.lifepilot.sandbox.booter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;
import com.lifepilot.sandbox.model.ExecutionState;
import com.lifepilot.sandbox.model.Language;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.lifepilot.sandbox.util.SandboxUtils;

/**
 * 基于 ProcessBuilder 的轻量级进程沙箱。
 *
 * <p>零外部依赖，默认方案。通过 ProcessBuilder 在隔离临时目录中执行代码，
 * 清洗环境变量，Virtual Thread 异步读取输出，超时强制终止。</p>
 *
 * <p>Python 路径强制使用 {@link PythonRuntimeManager#getPythonExecutable()}
 * 提供的捆绑运行时；{@link #boot(Path)} 会先校验运行时状态为 {@link RuntimeStatus.Ready}，
 * 否则启动失败。Node / Shell 仍走系统 PATH（前者读 {@code runtimePaths.javascript}，
 * 后者按 OS 选 cmd / bash）。</p>
 *
 * <p>安全措施：</p>
 * <ul>
 *   <li>环境变量清洗：仅保留 PATH</li>
 *   <li>超时强制终止：超过限制立即销毁子进程</li>
 *   <li>输出截断防止内存溢出</li>
 * </ul>
 *
 * <p>注意：ProcessBooter 不提供文件系统和网络隔离，不适合执行不可信代码。
 * 生产环境处理不可信代码请使用 {@link DockerBooter}。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class ProcessBooter implements SandboxBooter {

    private static final Logger log = LoggerFactory.getLogger(ProcessBooter.class);

    private final SandboxConfigProperties config;
    private final PythonRuntimeManager runtimeManager;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Path workingDirectory;

    public ProcessBooter(SandboxConfigProperties config, PythonRuntimeManager runtimeManager) {
        this.config = config;
        this.runtimeManager = runtimeManager;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        // 启动前先校验捆绑 Python 运行时状态：未就绪直接失败，避免后续 execute 用错误路径
        var status = runtimeManager.checkStatus();
        if (!(status instanceof RuntimeStatus.Ready)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Python 运行时未就绪: " + status));
        }
        log.info("ProcessBooter 启动完成: workingDirectory={}", workingDirectory);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        long startTime = System.currentTimeMillis();
        Path scriptFile = null;

        try {
            // 1. 将代码写入临时脚本文件
            String extension = request.language().fileExtension();
            scriptFile = Files.createTempFile(request.workingDirectory(), "sandbox-", extension);
            Files.writeString(scriptFile, request.code(), StandardCharsets.UTF_8);
            log.debug("临时脚本文件已创建: path={}", scriptFile);

            // 2. 按语言构建执行命令（Python 走捆绑运行时，Node / Shell 走系统 PATH）
            List<String> command = buildCommand(request.language(), scriptFile);

            // 3. 构建 ProcessBuilder
            var pb = new ProcessBuilder(command);
            pb.directory(request.workingDirectory().toFile());

            // 4. 清洗环境变量：仅保留 PATH
            String pathValue = pb.environment().get("PATH");
            pb.environment().clear();
            if (pathValue != null) {
                pb.environment().put("PATH", pathValue);
            }

            // 5. 启动进程
            Process process = pb.start();
            log.debug("进程已启动: command={}, pid={}", command, process.pid());

            // 6. Virtual Thread 异步读取 stdout / stderr
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getInputStream()), virtualThreadExecutor);
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getErrorStream()), virtualThreadExecutor);

            // 7. 等待进程完成或超时
            boolean finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("进程执行超时，已强制终止: pid={}, timeout={}s", process.pid(), request.timeoutSeconds());
            }

            // 8. 获取输出（超时后也尝试获取已有输出）
            byte[] stdoutBytes = stdoutFuture.getNow(new byte[0]);
            byte[] stderrBytes = stderrFuture.getNow(new byte[0]);

            long durationMs = System.currentTimeMillis() - startTime;
            int maxBytes = config.getMaxOutputBytes();
            String stdout = SandboxUtils.truncateOutput(stdoutBytes, maxBytes);
            String stderr = SandboxUtils.truncateOutput(stderrBytes, maxBytes);

            if (!finished) {
                return new ExecutionResult(stdout, stderr, -1, durationMs, ExecutionState.TIMEOUT);
            }

            int exitCode = process.exitValue();
            log.debug("进程执行完成: exitCode={}, durationMs={}", exitCode, durationMs);
            return new ExecutionResult(stdout, stderr, exitCode, durationMs, ExecutionState.COMPLETED);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("进程执行 IO 异常: message={}", e.getMessage(), e);
            return new ExecutionResult("", e.getMessage(), -1, durationMs, ExecutionState.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("进程执行被中断: message={}", e.getMessage());
            return new ExecutionResult("", e.getMessage(), -1, durationMs, ExecutionState.FAILED);
        } finally {
            // 清理临时脚本文件
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                    log.debug("临时脚本文件已清理: path={}", scriptFile);
                } catch (IOException e) {
                    log.warn("清理临时脚本文件失败: path={}, error={}", scriptFile, e.getMessage());
                }
            }
        }
    }

    @Override
    public void shutdown() {
        virtualThreadExecutor.close();
        if (workingDirectory != null) {
            try {
                SandboxUtils.deleteDirectoryRecursively(workingDirectory);
                log.info("ProcessBooter 已关闭，工作目录已清理: path={}", workingDirectory);
            } catch (IOException e) {
                log.warn("清理工作目录失败: path={}, error={}", workingDirectory, e.getMessage());
            }
        }
    }

    @Override
    public String type() {
        return "process";
    }

    @Override
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 返回当前 ProcessBooter 关联的 {@link PythonRuntimeManager}。
     *
     * <p>仅供 {@link com.lifepilot.sandbox.session.SandboxSessionManager} 在以模板方式
     * 派生新会话 booter 时复用同一个 runtimeManager 实例，避免重复 new 导致 installingState 等
     * 共享状态分裂。</p>
     */
    public PythonRuntimeManager runtimeManager() {
        return runtimeManager;
    }

    /**
     * 按语言构建执行命令。
     *
     * <p>Python 强制走 {@link PythonRuntimeManager#getPythonExecutable()} 提供的捆绑路径；
     * Shell 按 OS 选 {@code cmd}（Windows）或 {@code bash}（*nix）；
     * JavaScript 读取 {@code runtimePaths.javascript}，缺省 {@code node}。</p>
     *
     * <p>直接调用运行时，避免 Linux 共享环境下的 ulimit 差异导致 Node / Python 在启动阶段异常退出。
     * 超时仍由外层 {@link Process#waitFor(long, TimeUnit)} 和强制销毁保证。</p>
     *
     * @param language   编程语言
     * @param scriptFile 脚本文件路径
     * @return 完整命令列表
     */
    List<String> buildCommand(Language language, Path scriptFile) {
        return switch (language) {
            case PYTHON -> List.of(runtimeManager.getPythonExecutable().toString(), scriptFile.toString());
            case SHELL -> List.of(detectShell(), scriptFile.toString());
            case JAVASCRIPT -> {
                String node = config.getRuntimePaths().getOrDefault("javascript", "node");
                yield List.of(node, scriptFile.toString());
            }
        };
    }

    /** 按 OS 选 shell 解释器：Windows 走 {@code cmd}，其余走 {@code bash}。 */
    private static String detectShell() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "cmd" : "bash";
    }
}
