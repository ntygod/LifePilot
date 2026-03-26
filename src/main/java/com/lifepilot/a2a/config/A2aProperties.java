package com.lifepilot.a2a.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * A2A 模块配置属性。
 *
 * @author zsg
 * @since 2026-02-28
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.a2a")
public class A2aProperties {

    private boolean enabled = true;
    private Server server = new Server();
    private Client client = new Client();
    private Task task = new Task();

    /** A2A Server 配置。 */
    @Setter
    @Getter
    public static class Server {
        private boolean enabled = true;
        private String apiKey = "";
        private String agentName = "ZhiWei";
        private String agentDescription = "个人助手";
        private String agentVersion = "1.0.0";
        private String protocolVersion = "0.2.5";
        private boolean streamingEnabled = true;

    }

    /** A2A Client 配置。 */
    @Setter
    @Getter
    public static class Client {
        private boolean enabled = true;
        private List<String> remoteAgents = List.of();
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 60;
        private int cardCacheTtlMinutes = 30;

    }

    /** A2A Task 配置。 */
    @Setter
    @Getter
    public static class Task {
        private int ttlMinutes = 60;
        private int maxHistoryLength = 50;

    }
}
