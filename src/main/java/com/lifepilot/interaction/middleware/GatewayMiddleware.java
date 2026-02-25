package com.lifepilot.interaction.middleware;

import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 网关中间件接口，责任链模式的处理节点。
 *
 * <p>每个中间件通过 {@link #process} 方法处理消息，可以选择：
 * <ul>
 *   <li>调用 {@code chain.next(message)} 将消息传递给下一个中间件</li>
 *   <li>直接返回 {@link GatewayResponse} 实现短路</li>
 * </ul>
 *
 * <p>中间件按 {@link #order()} 值从小到大排序执行。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface GatewayMiddleware {

    /**
     * 处理网关消息。
     *
     * @param message 入站消息
     * @param chain   中间件链，调用 {@code chain.next(message)} 传递给下一个中间件
     * @return 网关响应
     */
    GatewayResponse process(GatewayMessage message, MiddlewareChain chain);

    /**
     * 返回执行顺序，数值越小越先执行。
     *
     * @return 执行顺序值
     */
    int order();

    /**
     * 返回中间件名称，用于日志和配置。
     *
     * @return 中间件名称
     */
    String name();

    /**
     * 是否启用此中间件，默认返回 {@code true}。
     *
     * <p>可通过覆盖此方法结合配置动态控制中间件的启用/禁用。
     *
     * @return 如果启用则返回 true
     */
    default boolean enabled() {
        return true;
    }
}
