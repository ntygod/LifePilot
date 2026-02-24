package com.lifepilot.mcp.config;

import com.lifepilot.mcp.transport.TransportType;
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
@ConfigurationProperties(prefix = "lifepilot.mcp")
public class McpConfigProperties {

    /** MCP 支持总开关，默认 true。 */
    private boolean enabled = true;

    /** MCP Server 模式配置。 */
    private ServerProperties server = new ServerProperties();

    /** MCP Server 连接列表。 */
    private List<ServerEntry> servers = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ServerProperties getServer() { return server; }
    public void setServer(ServerProperties server) { this.server = server; }

    public List<ServerEntry> getServers() { return servers; }
    public void setServers(List<ServerEntry> servers) { this.servers = servers; }

    /**
     * 将配置条目转换为 McpServerConfig 列表。
     */
    public List<McpServerConfig> toServerConfigs() {
        return servers.stream()
                .map(ServerEntry::toServerConfig)
                .toList();
    }

    /** MCP Server 模式配置。 */
    public static class ServerProperties {
        /** 是否启用 MCP Server 模式（反向桥接），默认 false。 */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** 单个 MCP Server 配置条目。 */
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

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public TransportType getTransport() { return transport; }
        public void setTransport(TransportType transport) { this.transport = transport; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }

        public boolean isAutoConnect() { return autoConnect; }
        public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

        public boolean isReconnect() { return reconnect; }
        public void setReconnect(boolean reconnect) { this.reconnect = reconnect; }

        public Duration getReconnectDelay() { return reconnectDelay; }
        public void setReconnectDelay(Duration reconnectDelay) { this.reconnectDelay = reconnectDelay; }

        public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
        public void setMaxReconnectAttempts(int maxReconnectAttempts) { this.maxReconnectAttempts = maxReconnectAttempts; }

        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public void setHealthCheckInterval(Duration healthCheckInterval) { this.healthCheckInterval = healthCheckInterval; }

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
}
