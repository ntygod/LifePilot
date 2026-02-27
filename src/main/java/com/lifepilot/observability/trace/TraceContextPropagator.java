package com.lifepilot.observability.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 追踪上下文传播器 — 管理 TraceContext 在线程/虚拟线程间的传播。
 *
 * <p>当前实现基于 {@link ThreadLocal}，支持传统线程池和虚拟线程场景。
 * 配置项 {@code lifepilot.observability.trace.use-scoped-value} 预留给
 * 未来 ScopedValue 正式发布后的切换（Java 22 中 ScopedValue 仍为 Preview）。</p>
 *
 * <p>虚拟线程天然继承父线程的 ThreadLocal（除非显式使用
 * {@code Thread.ofVirtual().allowSetThreadLocals(false)}），
 * 因此 ThreadLocal 方案在虚拟线程场景下同样有效。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceContextPropagator {

    private static final Logger log = LoggerFactory.getLogger(TraceContextPropagator.class);

    /**
     * ThreadLocal 存储当前线程绑定的 TraceContext。
     * 虚拟线程默认继承父线程的 ThreadLocal 值。
     */
    private static final ThreadLocal<TraceContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private final boolean useScopedValue;

    /**
     * 创建传播器实例。
     *
     * @param useScopedValue 是否使用 ScopedValue 模式（当前版本预留，实际使用 ThreadLocal）
     */
    public TraceContextPropagator(boolean useScopedValue) {
        this.useScopedValue = useScopedValue;
        if (useScopedValue) {
            log.info("TraceContextPropagator 初始化: ScopedValue 模式已配置但当前使用 ThreadLocal 兼容实现");
        } else {
            log.info("TraceContextPropagator 初始化: ThreadLocal 模式");
        }
    }

    /**
     * 将 TraceContext 绑定到当前线程。
     *
     * @param context 追踪上下文
     */
    public void bind(TraceContext context) {
        CONTEXT_HOLDER.set(context);
        log.debug("TraceContext 已绑定: traceId={}", context.traceId());
    }

    /**
     * 获取当前线程绑定的 TraceContext。
     *
     * @return 追踪上下文（如果存在）
     */
    public Optional<TraceContext> current() {
        return Optional.ofNullable(CONTEXT_HOLDER.get());
    }

    /**
     * 解除当前线程的 TraceContext 绑定。
     */
    public void unbind() {
        var ctx = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.remove();
        if (ctx != null) {
            log.debug("TraceContext 已解绑: traceId={}", ctx.traceId());
        }
    }

    /**
     * 是否配置为 ScopedValue 模式。
     *
     * @return true 表示配置了 ScopedValue 模式（当前版本仍使用 ThreadLocal 兼容实现）
     */
    public boolean isUseScopedValue() {
        return useScopedValue;
    }
}
