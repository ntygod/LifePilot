package com.lifepilot.interaction.middleware;

import java.util.List;

import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 索引式中间件责任链，按顺序执行启用的中间件。
 *
 * <p>持有有序的中间件列表和共享的 {@link MiddlewareContext}，
 * 通过 {@link #next(GatewayMessage)} 方法推进索引，跳过禁用的中间件，
 * 执行下一个启用的中间件。当所有中间件耗尽时返回 500 错误响应。
 *
 * <p>每次请求由 {@code MiddlewarePipeline} 创建新的 {@code MiddlewareChain} 实例，
 * 保证请求间隔离。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class MiddlewareChain {

    private final List<GatewayMiddleware> middlewares;
    private final MiddlewareContext context;
    private int currentIndex;

    /**
     * 构造中间件链。
     *
     * @param middlewares 按 order 排序的中间件列表
     * @param context     共享上下文
     */
    public MiddlewareChain(List<GatewayMiddleware> middlewares, MiddlewareContext context) {
        this.middlewares = List.copyOf(middlewares);
        this.context = context;
        this.currentIndex = 0;
    }

    /**
     * 执行下一个启用的中间件。
     *
     * <p>推进索引，跳过 {@code enabled() == false} 的中间件，
     * 找到下一个启用的中间件后调用其 {@code process} 方法。
     * 当所有中间件耗尽时返回 statusCode=500 的默认错误响应。
     *
     * @param message 入站消息
     * @return 网关响应
     */
    public GatewayResponse next(GatewayMessage message) {
        // 跳过禁用的中间件，找到下一个启用的
        while (currentIndex < middlewares.size()) {
            var middleware = middlewares.get(currentIndex);
            currentIndex++;
            if (middleware.enabled()) {
                return middleware.process(message, this);
            }
        }
        // 所有中间件已耗尽，返回默认错误响应
        return GatewayResponse.error(message.channelType(), "中间件链已耗尽，未产生响应", 500);
    }

    /**
     * 暴露共享上下文，允许中间件访问和修改跨中间件数据。
     *
     * @return 中间件共享上下文
     */
    public MiddlewareContext context() {
        return context;
    }
}
