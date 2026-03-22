package com.lifepilot.sandbox.booter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import com.lifepilot.sandbox.util.SandboxUtils;

/**
 * 基于 ProcessBuilder 的轻量级进程沙箱。
 *
 * <p>零外部依赖，默认方案。通过 ProcessBuilder 在隔离临时目录中执行代码，
 * 清洗环境变量，Virtual Thread 异步读取输出，超时强制终止。</p>
 *
 * <p>安全措施：</p>
 * <ul>
 *   <li>环境变量清洗：仅保留 PATH</li>
 *   <li>Linux 下通过 ulimit 限制虚拟内存（256MB）、CPU 时间、文件大小（64MB）、进程数（64）</li>
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
    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase().contains("linux");

    private final SandboxConfigProperties config;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Path workingDirectory;

    public ProcessBooter(SandboxConfigProperties config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
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

            // 2. 查找运行时命令
            String runtimeCommand = config.getRuntimePaths()
                    .getOrDefault(request.language().name().toLowerCase(), request.language().runtimeCommand());

            // 3. 构建命令（Linux 下包裹 ulimit 限制）
            List<String> command = buildCommand(runtimeCommand, scriptFile, request);

            // 4. 构建 ProcessBuilder
            var pb = new ProcessBuilder(command);
            pb.directory(request.workingDirectory().toFile());

            // 5. 清洗环境变量：仅保留 PATH
            String pathValue = pb.environment().get("PATH");
            pb.environment().clear();
            if (pathValue != null) {
                pb.environment().put("PATH", pathValue);
            }

            // 6. 启动进程
            Process process = pb.start();
            log.debug("进程已启动: command={}, pid={}", command, process.pid());

            // 7. Virtual Thread 异步读取 stdout / stderr
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getInputStream()), virtualThreadExecutor);
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getErrorStream()), virtualThreadExecutor);

            // 8. 等待进程完成或超时
            boolean finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("进程执行超时，已强制终止: pid={}, timeout={}s", process.pid(), request.timeoutSeconds());
            }

            // 9. 获取输出（超时后也尝试获取已有输出）
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
     * 构建执行命令。Linux 下通过 bash + ulimit 包裹资源限制。
     *
     * <p>ulimit 限制项：</p>
     * <ul>
     *   <li>{@code -v 262144} — 虚拟内存 256MB</li>
     *   <li>{@code -t <timeout>} — CPU 时间等于执行超时</li>
     *   <li>{@code -f 65536} — 单文件最大 64MB</li>
     *   <li>{@code -u 64} — 最大进程数 64（防 fork bomb）</li>
     * </ul>
     *
     * @param runtimeCommand 运行时命令
     * @param scriptFile     脚本文件路径
     * @param request        执行请求
     * @return 完整命令列表
     */
    List<String> buildCommand(String runtimeCommand, Path scriptFile, ExecutionRequest request) {
        if (!IS_LINUX) {
            // 非 Linux 平台不支持 ulimit，直接执行
            return List.of(runtimeCommand, scriptFile.toString());
        }

        // Linux: bash -c 'ulimit -v 262144 -t <timeout> -f 65536 -u 64; <runtime> <script>'
        String ulimitCmd = "ulimit -v 262144 -t %d -f 65536 -u 64; %s %s".formatted(
                request.timeoutSeconds(), runtimeCommand, scriptFile.toString());

        return List.of("bash", "-c", ulimitCmd);
    }
}
