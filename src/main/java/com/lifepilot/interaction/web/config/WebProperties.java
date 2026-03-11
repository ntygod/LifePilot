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
     */
    public record SseProperties(
            @DefaultValue("300000") long timeout,
            @DefaultValue("30000") long heartbeatInterval,
            @DefaultValue("1800000") long mcpStatusTimeout
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
