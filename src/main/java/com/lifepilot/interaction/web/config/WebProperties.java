package com.lifepilot.interaction.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Web 模块配置属性。
 *
 * <p>绑定 {@code lifepilot.web} 配置前缀。包含 SSE 流式传输和 CORS 跨域相关配置。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ConfigurationProperties(prefix = "lifepilot.web")
public record WebProperties(
        @DefaultValue SseProperties sse,
        @DefaultValue CorsProperties cors
) {

    /**
     * SSE 流式传输配置。
     *
     * @param timeout           SSE 连接超时时间（毫秒），默认 300000（5 分钟）
     * @param heartbeatInterval SSE 心跳间隔（毫秒），默认 30000（30 秒）
     * @param mcpStatusTimeout  MCP Server 状态 SSE 连接超时时间（毫秒），默认 1800000（30 分钟）
     * @param buffer            事件缓冲区配置，用于平滑流式输出速率
     */
    public record SseProperties(
            @DefaultValue("300000") long timeout,
            @DefaultValue("30000") long heartbeatInterval,
            @DefaultValue("1800000") long mcpStatusTimeout,
            @DefaultValue SseBufferProperties buffer
    ) {}

    /**
     * SSE 事件缓冲区配置 — 在生产者和 SSE 派发器之间插入有界异步队列，吸收 LLM 生成速率方差。
     *
     * @param enabled                 是否启用事件缓冲区，默认 true
     * @param queueCapacity           有界队列容量，默认 1024
     * @param highWaterMark           队列深度高水位线，超过则加速排空，默认 100
     * @param lowWaterMark            队列深度低水位线，低于则减速排空以拉伸内容，默认 50
     * @param fastDrainIntervalMs     高水位排空间隔（毫秒），~83 events/sec，默认 12
     * @param normalDrainIntervalMs   正常排空间隔（毫秒），~50 events/sec，默认 20
     * @param slowDrainIntervalMs     低水位排空间隔（毫秒），~25 events/sec，默认 40
     * @param preLookaheadIntervalMs  前瞻到工具调用时的排空间隔（毫秒），~10 events/sec，默认 100
     * @param offerTimeoutMs          队列满时 offer 等待超时（毫秒），默认 100
     * @param gapHeartbeatIntervalMs  队列为空时注入心跳的间隔（毫秒），默认 5000
     */
    public record SseBufferProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1024") int queueCapacity,
            @DefaultValue("100") int highWaterMark,
            @DefaultValue("50") int lowWaterMark,
            @DefaultValue("12") long fastDrainIntervalMs,
            @DefaultValue("20") long normalDrainIntervalMs,
            @DefaultValue("40") long slowDrainIntervalMs,
            @DefaultValue("100") long preLookaheadIntervalMs,
            @DefaultValue("100") long offerTimeoutMs,
            @DefaultValue("5000") long gapHeartbeatIntervalMs
    ) {}

    /**
     * CORS 跨域配置。
     *
     * @param allowedOrigins   允许的跨域源列表，默认 {@code http://localhost:5173}（Vite 开发端口）
     * @param allowCredentials 是否允许携带凭证（Cookie），默认 true
     */
    public record CorsProperties(
            @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
            @DefaultValue("true") boolean allowCredentials
    ) {}
}
