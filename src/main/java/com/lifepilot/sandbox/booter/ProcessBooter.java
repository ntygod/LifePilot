package com.lifepilot.sandbox.booter;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;

/**
 * 基于 ProcessBuilder 的轻量级进程沙箱。
 *
 * <p>零外部依赖，默认方案。通过 ProcessBuilder 在隔离临时目录中执行代码，
 * 清洗环境变量，Virtual Thread 异步读取输出，超时强制终止。</p>
 *
 * <p>当前为占位实现，完整逻辑将在后续任务中实现。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class ProcessBooter implements SandboxBooter {

    private final SandboxConfigProperties config;

    public ProcessBooter(SandboxConfigProperties config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> boot(Path workingDirectory) {
        throw new UnsupportedOperationException("ProcessBooter.boot() 尚未实现");
    }

    @Override
    public boolean available() {
        // ProcessBuilder 在 JVM 中始终可用
        return true;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        throw new UnsupportedOperationException("ProcessBooter.execute() 尚未实现");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("ProcessBooter.shutdown() 尚未实现");
    }

    @Override
    public String type() {
        return "process";
    }
}
