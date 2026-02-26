package com.lifepilot.sandbox.booter;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;

/**
 * 基于 Docker 容器的强隔离沙箱。
 *
 * <p>通过 Docker Engine CLI 创建容器执行代码，提供完整的资源隔离
 * （内存/CPU/磁盘/网络限制）、只读根文件系统和非 root 用户运行。</p>
 *
 * <p>当前为占位实现，完整逻辑将在后续任务中实现。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class DockerBooter implements SandboxBooter {

    private final SandboxConfigProperties config;

    public DockerBooter(SandboxConfigProperties config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        throw new UnsupportedOperationException("DockerBooter.boot() 尚未实现");
    }

    @Override
    public boolean available() {
        throw new UnsupportedOperationException("DockerBooter.available() 尚未实现");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        throw new UnsupportedOperationException("DockerBooter.execute() 尚未实现");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("DockerBooter.shutdown() 尚未实现");
    }

    @Override
    public String type() {
        return "docker";
    }
}
