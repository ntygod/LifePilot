package com.lifepilot.interaction.middleware;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 中间件管道，收集中间件并按 {@code order()} 排序组装链路。
 *
 * <p>构造时收集所有 {@link GatewayMiddleware} 实例并按 {@link GatewayMiddleware#order()} 排序。
 * 每次 {@link #execute(GatewayMessage)} 调用创建新的 {@link MiddlewareContext} 和
 * {@link MiddlewareChain} 实例，保证请求间隔离。
 *
 * <p>使用 {@link CopyOnWriteArrayList} 保证读多写少场景下的线程安全，
 * 支持通过 {@link #register(GatewayMiddleware)} 和 {@link #unregister(String)} 动态管理中间件。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class MiddlewarePipeline {

    private static final Comparator<GatewayMiddleware> ORDER_COMPARATOR =
            Comparator.comparingInt(GatewayMiddleware::order);

    private final CopyOnWriteArrayList<GatewayMiddleware> middlewares;

    /**
     * 构造中间件管道，按 {@code order()} 排序存储。
     *
     * @param middlewares 中间件列表
     */
    public MiddlewarePipeline(List<GatewayMiddleware> middlewares) {
        var sorted = new java.util.ArrayList<>(middlewares);
        sorted.sort(ORDER_COMPARATOR);
        this.middlewares = new CopyOnWriteArrayList<>(sorted);
    }

    /**
     * 执行中间件管道处理消息。
     *
     * <p>每次调用创建新的 {@link MiddlewareContext} 和 {@link MiddlewareChain}，
     * 保证请求间上下文隔离。
     *
     * @param message 入站消息
     * @return 网关响应
     */
    public GatewayResponse execute(GatewayMessage message) {
        var context = new MiddlewareContext();
        var chain = new MiddlewareChain(List.copyOf(middlewares), context);
        return chain.next(message);
    }

    /**
     * 动态注册中间件，注册后自动按 {@code order()} 重新排序。
     *
     * @param middleware 要注册的中间件
     */
    public void register(GatewayMiddleware middleware) {
        middlewares.add(middleware);
        // CopyOnWriteArrayList 不支持原地排序，需要替换整个列表
        var sorted = new java.util.ArrayList<>(middlewares);
        sorted.sort(ORDER_COMPARATOR);
        // 清空并重新添加排序后的列表
        middlewares.clear();
        middlewares.addAll(sorted);
    }

    /**
     * 按名称动态注销中间件。
     *
     * @param name 中间件名称
     * @return 如果找到并移除则返回 true
     */
    public boolean unregister(String name) {
        return middlewares.removeIf(mw -> mw.name().equals(name));
    }

    /**
     * 返回当前中间件列表的不可变快照。
     *
     * @return 不可变的中间件列表
     */
    public List<GatewayMiddleware> getMiddlewares() {
        return List.copyOf(middlewares);
    }
}
