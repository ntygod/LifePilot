package com.lifepilot.interaction.middleware.router;

import java.util.List;

/**
 * 路由决策 sealed interface，穷举所有路由类型。
 *
 * <p>RouterMiddleware 根据消息类型生成对应的路由决策：
 * <ul>
 *   <li>{@link FastRoute} — 快速路径（命令消息），直接返回响应，不经过 Agent</li>
 *   <li>{@link AgentRoute} — 慢速路径（自然语言消息），交由 ExecutionMiddleware 处理</li>
 *   <li>{@link ErrorRoute} — 错误路由（未知命令等异常情况）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface RouteDecision
        permits RouteDecision.FastRoute, RouteDecision.AgentRoute, RouteDecision.ErrorRoute {

    /**
     * 快速路径路由（命令消息）。
     *
     * @param command      命令名
     * @param args         命令参数列表
     * @param responseText 响应文本
     */
    record FastRoute(String command, List<String> args, String responseText) implements RouteDecision {
        /** 防御性拷贝，确保参数列表不可变。 */
        public FastRoute {
            args = List.copyOf(args);
        }
    }

    /**
     * Agent 路由（自然语言消息）。
     *
     * @param content 消息内容
     */
    record AgentRoute(String content) implements RouteDecision {}

    /**
     * 错误路由。
     *
     * @param message    错误消息
     * @param statusCode 状态码
     */
    record ErrorRoute(String message, int statusCode) implements RouteDecision {}
}
