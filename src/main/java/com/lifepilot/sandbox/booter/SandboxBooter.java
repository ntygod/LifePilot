package com.lifepilot.sandbox.booter;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;

/**
 * 沙箱启动器抽象。
 *
 * <p>sealed interface 确保编译期穷举匹配两种隔离策略：
 * {@link ProcessBooter}（基于 ProcessBuilder 的轻量级进程沙箱）和
 * {@link DockerBooter}（基于 Docker 容器的强隔离沙箱）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public sealed interface SandboxBooter permits ProcessBooter, DockerBooter {

    /** Booter 类型字面量：process。 */
    String TYPE_PROCESS = "process";

    /** Booter 类型字面量：docker。 */
    String TYPE_DOCKER = "docker";

    /**
     * 启动沙箱实例。
     *
     * @param workingDirectory 工作目录
     * @return 启动完成的 CompletableFuture
     */
    CompletableFuture<Void> boot(Path workingDirectory);

    /**
     * 检查沙箱运行时是否可用。
     *
     * @return 可用返回 true，否则 false
     */
    boolean available();

    /**
     * 在沙箱中执行代码。
     *
     * @param request 执行请求
     * @return 执行结果
     */
    ExecutionResult execute(ExecutionRequest request);

    /**
     * 关闭沙箱实例，释放资源。
     */
    void shutdown();

    /**
     * 获取沙箱类型标识。
     *
     * @return {@link #TYPE_PROCESS} 或 {@link #TYPE_DOCKER}
     */
    String type();

    /**
     * 获取沙箱工作目录。
     *
     * @return 工作目录路径，boot() 调用前返回 null
     */
    Path workingDirectory();
}
