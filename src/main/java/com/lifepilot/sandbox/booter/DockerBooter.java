package com.lifepilot.sandbox.booter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
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
import com.lifepilot.sandbox.util.SandboxUtils;

/**
 * 基于 Docker 容器的强隔离沙箱。
 *
 * <p>通过 Docker Engine CLI 创建容器执行代码，提供完整的资源隔离
 * （内存/CPU/磁盘/网络限制）、只读根文件系统和非 root 用户运行。</p>
 *
 * <p>容器配置：</p>
 * <ul>
 *   <li>{@code --rm} — 容器退出后自动删除</li>
 *   <li>{@code --network none} — 默认禁用网络（可配置开启）</li>
 *   <li>{@code --read-only} — 只读根文件系统</li>
 *   <li>{@code --user 1000:1000} — 非 root 用户运行</li>
 *   <li>{@code --memory / --cpus} — 资源限制</li>
 *   <li>{@code --pids-limit 64} — 进程数限制（防 fork bomb）</li>
 *   <li>{@code --tmpfs /tmp:rw,noexec,size=64m} — 可写临时目录</li>
 *   <li>{@code -v workDir:/workspace:rw} — 仅工作目录可写</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class DockerBooter implements SandboxBooter {

    private static final Logger log = LoggerFactory.getLogger(DockerBooter.class);

    private final SandboxConfigProperties config;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Path workingDirectory;

    public DockerBooter(SandboxConfigProperties config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        if (!available()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Docker Engine 不可用，请确认 Docker 已安装并正在运行"));
        }
        log.info("DockerBooter 启动完成: workingDirectory={}", workingDirectory);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean available() {
        try {
            var pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 消费输出避免进程阻塞
            try (InputStream is = process.getInputStream()) {
                is.readAllBytes();
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            log.debug("Docker 可用性检测失败: error={}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Docker 可用性检测被中断: error={}", e.getMessage());
            return false;
        }
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        long startTime = System.currentTimeMillis();
        Path scriptFile = null;
        String containerName = "lifepilot-sandbox-" + UUID.randomUUID();

        try {
            // 1. 将代码写入工作目录中的脚本文件
            String extension = request.language().fileExtension();
            String scriptFileName = "script" + extension;
            scriptFile = request.workingDirectory().resolve(scriptFileName);
            Files.writeString(scriptFile, request.code(), StandardCharsets.UTF_8);
            log.debug("脚本文件已创建: path={}", scriptFile);

            // 2. 查找运行时命令
            String runtimeCommand = config.getRuntimePaths()
                    .getOrDefault(request.language().name().toLowerCase(), request.language().runtimeCommand());

            // 3. 构建 docker run 命令
            var command = buildDockerCommand(containerName, request, runtimeCommand, scriptFileName);
            log.debug("Docker 命令: {}", String.join(" ", command));

            // 4. 通过 ProcessBuilder 执行 docker run
            var pb = new ProcessBuilder(command);
            pb.directory(request.workingDirectory().toFile());
            Process process = pb.start();

            // 5. Virtual Thread 异步读取 stdout / stderr
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getInputStream()), virtualThreadExecutor);
            CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(
                    () -> SandboxUtils.readStream(process.getErrorStream()), virtualThreadExecutor);

            // 6. 等待完成或超时
            boolean finished = process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                // 超时：通过 docker kill 终止容器
                killContainer(containerName);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("Docker 容器执行超时，已强制终止: container={}, timeout={}s",
                        containerName, request.timeoutSeconds());
            }

            // 7. 获取输出
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
            log.debug("Docker 容器执行完成: container={}, exitCode={}, durationMs={}",
                    containerName, exitCode, durationMs);
            return new ExecutionResult(stdout, stderr, exitCode, durationMs, ExecutionState.COMPLETED);

        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Docker 执行 IO 异常: container={}, message={}", containerName, e.getMessage(), e);
            return new ExecutionResult("", e.getMessage(), -1, durationMs, ExecutionState.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Docker 执行被中断: container={}, message={}", containerName, e.getMessage());
            return new ExecutionResult("", e.getMessage(), -1, durationMs, ExecutionState.FAILED);
        } finally {
            // 清理脚本文件
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                    log.debug("脚本文件已清理: path={}", scriptFile);
                } catch (IOException e) {
                    log.warn("清理脚本文件失败: path={}, error={}", scriptFile, e.getMessage());
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
                log.info("DockerBooter 已关闭，工作目录已清理: path={}", workingDirectory);
            } catch (IOException e) {
                log.warn("清理工作目录失败: path={}, error={}", workingDirectory, e.getMessage());
            }
        }
    }

    @Override
    public String type() {
        return TYPE_DOCKER;
    }

    @Override
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 构建 docker run 命令参数列表。
     *
     * @param containerName  容器名称（用于超时 kill）
     * @param request        执行请求
     * @param runtimeCommand 运行时命令（如 python3、node、bash）
     * @param scriptFileName 脚本文件名（如 script.py）
     * @return 完整的命令参数列表
     */
    ArrayList<String> buildDockerCommand(String containerName, ExecutionRequest request,
                                         String runtimeCommand, String scriptFileName) {
        var dockerConfig = config.getDocker();
        var command = new ArrayList<String>();

        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--name");
        command.add(containerName);

        // 网络隔离：默认禁用，可配置开启
        if (!dockerConfig.isNetworkEnabled()) {
            command.add("--network");
            command.add("none");
        }

        // 只读根文件系统
        command.add("--read-only");

        // 非 root 用户
        command.add("--user");
        command.add("1000:1000");

        // 内存限制
        command.add("--memory");
        command.add(dockerConfig.getMemoryLimitMb() + "m");

        // CPU 限制
        command.add("--cpus");
        command.add(String.valueOf(dockerConfig.getCpuLimit()));

        // 进程数限制（防 fork bomb）
        command.add("--pids-limit");
        command.add("64");

        // 可写临时目录（--read-only 下运行时需要 /tmp）
        command.add("--tmpfs");
        command.add("/tmp:rw,noexec,size=64m");

        // 挂载工作目录
        command.add("-v");
        command.add(request.workingDirectory().toAbsolutePath() + ":/workspace:rw");

        // 镜像名称：imagePrefix + 语言名
        String imageName = dockerConfig.getImagePrefix() + request.language().name().toLowerCase();
        command.add(imageName);

        // 运行时命令 + 脚本路径
        command.add(runtimeCommand);
        command.add("/workspace/" + scriptFileName);

        return command;
    }

    /**
     * 通过 docker kill 终止指定容器。
     *
     * @param containerName 容器名称
     */
    private void killContainer(String containerName) {
        try {
            var pb = new ProcessBuilder("docker", "kill", containerName);
            pb.redirectErrorStream(true);
            Process killProcess = pb.start();
            // 消费输出避免阻塞
            try (InputStream is = killProcess.getInputStream()) {
                is.readAllBytes();
            }
            boolean killed = killProcess.waitFor(10, TimeUnit.SECONDS);
            if (!killed) {
                killProcess.destroyForcibly();
                log.warn("docker kill 命令超时: container={}", containerName);
            } else if (killProcess.exitValue() != 0) {
                log.warn("docker kill 命令失败: container={}, exitCode={}", containerName, killProcess.exitValue());
            } else {
                log.debug("容器已终止: container={}", containerName);
            }
        } catch (IOException e) {
            log.warn("终止容器失败: container={}, error={}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("终止容器被中断: container={}, error={}", containerName, e.getMessage());
        }
    }
}
