package com.lifepilot.mcp.config;

import com.lifepilot.mcp.transport.TransportType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置属性。
 *
 * <p>绑定 lifepilot.mcp 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.mcp")
public class McpConfigProperties {

    /** MCP 支持总开关，默认 true。 */
    private boolean enabled = true;

    /** MCP Server 模式配置。 */
    private ServerProperties server = new ServerProperties();

    /** MCP Server 连接列表。 */
    private List<ServerEntry> servers = new ArrayList<>();

    /** MCP 自动发现配置。 */
    private Discovery discovery = new Discovery();

    /**
     * 将配置条目转换为 McpServerConfig 列表。
     */
    public List<McpServerConfig> toServerConfigs() {
        return servers.stream()
                .map(ServerEntry::toServerConfig)
                .toList();
    }

    /** MCP Server 模式配置。 */
    @Setter
    @Getter
    public static class ServerProperties {
        /** 是否启用 MCP Server 模式（反向桥接），默认 false。 */
        private boolean enabled = false;
        /** API Key 认证密钥，为空时不启用认证。 */
        private String apiKey;
    }

    /** 单个 MCP Server 配置条目。 */
    @Setter
    @Getter
    public static class ServerEntry {
        private String name;
        private TransportType transport = TransportType.STDIO;
        private String command;
        private List<String> args = new ArrayList<>();
        private String url;
        private Map<String, String> env = new HashMap<>();
        private Duration timeout;
        private boolean autoConnect = true;
        private boolean reconnect = true;
        private Duration reconnectDelay;
        private int maxReconnectAttempts = 5;
        private Duration healthCheckInterval;

        /** 转换为 McpServerConfig record。 */
        public McpServerConfig toServerConfig() {
            return McpServerConfig.builder()
                    .name(name)
                    .transport(transport)
                    .command(command)
                    .args(args)
                    .url(url)
                    .env(env)
                    .timeout(timeout)
                    .autoConnect(autoConnect)
                    .reconnect(reconnect)
                    .reconnectDelay(reconnectDelay)
                    .maxReconnectAttempts(maxReconnectAttempts)
                    .healthCheckInterval(healthCheckInterval)
                    .build();
        }
    }

    /**
     * MCP 自动发现配置。
     *
     * @author zsg
     * @since 2026-03-16
     */
    @Setter
    @Getter
    public static class Discovery {
        /** 自动发现开关，默认 true。 */
        private boolean enabled = true;
        /** 额外发现路径列表。 */
        private List<String> paths = new ArrayList<>();
        /** 是否在启动时释放内置 MCP 服务器配置到用户目录，默认 true。 */
        private boolean seedBuiltinServers = true;
    }
}
