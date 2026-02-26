package com.lifepilot.sandbox.booter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
import com.lifepilot.sandbox.model.SandboxState;

/**
 * 基于 ProcessBuilder 的轻量级进程沙箱。
 *
 * <p>零外部依赖，默认方案。通过 ProcessBuilder 在隔离临时目录中执行代码，
 * 清洗环境变量，Virtual Thread 异步读取输出，超时强制终止。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class ProcessBooter implements SandboxBooter {

    private static final Logger log = LoggerFactory.getLogger(ProcessBooter.class);

    private final SandboxConfigProperties config;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile SandboxState state = SandboxState.SHUTDOWN;
    private volatile Path workingDirectory;

    public ProcessBooter(SandboxConfigProperties config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.state = SandboxState.READY;
        log.info("ProcessBooter 启动完成: workingDirectory={}", workingDirectory);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        state = SandboxState.RUNNING;
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

            // 3. 构建 ProcessBuilder
            var pb = new ProcessBuilder(runtimeCommand, scriptFile.toString());
            pb.directory(request.workingDirectory().toFile());

            // 4. 清洗环境变量：仅保留 PATH
            String pathValue = pb.environment().get("PATH");
            pb.environment().clear();
            if (pathValue != null) {
                pb.environment().put("PATH", pathValue);
            }

            // 5. 启动进程
            Process process = pb.start();
            log.debug("进程已启动: command={}, pid={}", runtimeCommand, process.pid());

            // 6. Virtual Thread 异步读取 stdout / stderr
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()), virtualThreadExecutor);
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()), virtualThreadExecutor);

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
            String stdout = truncateOutput(stdoutBytes);
            String stderr = truncateOutput(stderrBytes);

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
            state = SandboxState.READY;
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
        state = SandboxState.SHUTDOWN;
        virtualThreadExecutor.close();
        if (workingDirectory != null) {
            try {
                deleteDirectoryRecursively(workingDirectory);
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
     * 读取输入流的全部内容为字节数组。
     */
    private static byte[] readStream(InputStream inputStream) {
        try (inputStream) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            log.warn("读取进程输出流失败: error={}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * 截断输出到配置的最大字节数。
     */
    private String truncateOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int maxBytes = config.getMaxOutputBytes();
        if (bytes.length <= maxBytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    /**
     * 递归删除目录及其内容。
     */
    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: path={}, error={}", path, e.getMessage());
                        }
                    });
        }
    }
}
