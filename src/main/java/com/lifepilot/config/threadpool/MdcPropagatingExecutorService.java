package com.lifepilot.config.threadpool;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ExecutorService 装饰器，自动将提交线程的 MDC 上下文传播到工作线程。
 *
 * <p>Virtual Thread 不继承父线程的 ThreadLocal（MDC 基于 ThreadLocal），
 * 导致异步执行的 Agent 日志丢失请求关联信息（如 messageId）。
 * 本装饰器在任务提交时捕获 MDC 快照，在工作线程执行前恢复，执行后清理。</p>
 *
 * @author zsg
 * @since 2026-04-04
 */
public class MdcPropagatingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    public MdcPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                command.run();
            } finally {
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        });
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
