package com.lifepilot.a2a.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * A2A 模块配置属性。
 *
 * @author zsg
 * @since 2026-02-28
 */
@ConfigurationProperties(prefix = "lifepilot.a2a")
public class A2aProperties {

    private boolean enabled = true;
    private Server server = new Server();
    private Client client = new Client();
    private Task task = new Task();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }

    /** A2A Server 配置。 */
    public static class Server {
        private boolean enabled = true;
        private String apiKey = "";
        private String agentName = "ZhiWei";
        private String agentDescription = "个人生活助手";
        private String agentVersion = "1.0.0";
        private String protocolVersion = "0.2.5";
        private boolean streamingEnabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }
        public String getAgentDescription() { return agentDescription; }
        public void setAgentDescription(String agentDescription) { this.agentDescription = agentDescription; }
        public String getAgentVersion() { return agentVersion; }
        public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public boolean isStreamingEnabled() { return streamingEnabled; }
        public void setStreamingEnabled(boolean streamingEnabled) { this.streamingEnabled = streamingEnabled; }
    }

    /** A2A Client 配置。 */
    public static class Client {
        private boolean enabled = true;
        private List<String> remoteAgents = List.of();
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 60;
        private int cardCacheTtlMinutes = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getRemoteAgents() { return remoteAgents; }
        public void setRemoteAgents(List<String> remoteAgents) { this.remoteAgents = remoteAgents; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
        public int getCardCacheTtlMinutes() { return cardCacheTtlMinutes; }
        public void setCardCacheTtlMinutes(int cardCacheTtlMinutes) { this.cardCacheTtlMinutes = cardCacheTtlMinutes; }
    }

    /** A2A Task 配置。 */
    public static class Task {
        private int ttlMinutes = 60;
        private int maxHistoryLength = 50;

        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }
        public int getMaxHistoryLength() { return maxHistoryLength; }
        public void setMaxHistoryLength(int maxHistoryLength) { this.maxHistoryLength = maxHistoryLength; }
    }
}
