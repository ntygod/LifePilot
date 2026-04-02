package com.lifepilot.interaction.middleware;

import java.util.Comparator;
import java.util.List;

import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 中间件管道，收集中间件并按 {@code order()} 排序组装链路。
 *
 * <p>构造时收集所有 {@link GatewayMiddleware} 实例并按 {@link GatewayMiddleware#order()} 排序。
 * 每次 {@link #execute(GatewayMessage)} 调用创建新的 {@link MiddlewareContext} 和
 * {@link MiddlewareChain} 实例，保证请求间隔离。
 *
 * <p>使用 volatile 不可变列表引用保证读端无锁线程安全，
 * 支持通过 {@link #register(GatewayMiddleware)} 和 {@link #unregister(String)} 动态管理中间件。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class MiddlewarePipeline {

    private static final Comparator<GatewayMiddleware> ORDER_COMPARATOR =
            Comparator.comparingInt(GatewayMiddleware::order);

    /** volatile 不可变列表引用 — 写端 synchronized 保证互斥，读端通过引用赋值天然原子。 */
    private volatile List<GatewayMiddleware> middlewares;

    /**
     * 构造中间件管道，按 {@code order()} 排序存储。
     *
     * @param middlewares 中间件列表
     */
    public MiddlewarePipeline(List<GatewayMiddleware> middlewares) {
        var sorted = new java.util.ArrayList<>(middlewares);
        sorted.sort(ORDER_COMPARATOR);
        this.middlewares = List.copyOf(sorted);
    }

    /**
     * 执行中间件管道处理消息。
     *
     * <p>每次调用创建新的 {@link MiddlewareContext} 和 {@link MiddlewareChain}，
     * 保证请求间上下文隔离。读取 volatile 引用获取一致快照，无需加锁。
     *
     * @param message 入站消息
     * @return 网关响应
     */
    public GatewayResponse execute(GatewayMessage message) {
        var context = new MiddlewareContext();
        var chain = new MiddlewareChain(middlewares, context);
        return chain.next(message);
    }

    /**
     * 动态注册中间件，注册后自动按 {@code order()} 重新排序。
     *
     * <p>synchronized 保证并发写互斥，volatile 引用赋值保证读端立即可见。
     *
     * @param middleware 要注册的中间件
     */
    public synchronized void register(GatewayMiddleware middleware) {
        var snapshot = new java.util.ArrayList<>(middlewares);
        snapshot.add(middleware);
        snapshot.sort(ORDER_COMPARATOR);
        middlewares = List.copyOf(snapshot);
    }

    /**
     * 按名称动态注销中间件。
     *
     * @param name 中间件名称
     * @return 如果找到并移除则返回 true
     */
    public synchronized boolean unregister(String name) {
        var snapshot = new java.util.ArrayList<>(middlewares);
        boolean removed = snapshot.removeIf(mw -> mw.name().equals(name));
        if (removed) {
            middlewares = List.copyOf(snapshot);
        }
        return removed;
    }

    /**
     * 返回当前中间件列表的不可变快照。
     *
     * @return 不可变的中间件列表
     */
    public List<GatewayMiddleware> getMiddlewares() {
        return middlewares;
    }
}
