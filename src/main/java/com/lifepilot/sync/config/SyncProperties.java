package com.lifepilot.sync.config;

import com.lifepilot.sync.model.ConflictPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部数据源同步模块配置属性。
 *
 * <p>绑定 {@code lifepilot.sync} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@ConfigurationProperties(prefix = "lifepilot.sync")
public class SyncProperties {

    /** 同步模块总开关，默认 true。 */
    private boolean enabled = true;

    /** 默认 Cron 表达式（每 15 分钟）。 */
    private String defaultCron = "0 */15 * * * *";

    /** 默认冲突解决策略，默认 LAST_WRITE_WINS。 */
    private ConflictPolicy defaultConflictPolicy = ConflictPolicy.LAST_WRITE_WINS;

    /** 连接器请求超时（秒），默认 30。 */
    private int timeout = 30;

    /** 最大重试次数，默认 2。 */
    private int maxRetries = 2;

    /** 凭证加密密钥来源，默认 "system-key"。 */
    private String credentialKeySource = "system-key";

    /** Obsidian Vault 目录路径，默认空。 */
    private String obsidianVaultPath = "";

    /** 事件触发同步最小间隔（秒），默认 60。 */
    private int eventSyncMinInterval = 60;

    /** 嵌套连接器配置。 */
    private Connectors connectors = new Connectors();

    // --- getters / setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDefaultCron() { return defaultCron; }
    public void setDefaultCron(String defaultCron) { this.defaultCron = defaultCron; }

    public ConflictPolicy getDefaultConflictPolicy() { return defaultConflictPolicy; }
    public void setDefaultConflictPolicy(ConflictPolicy defaultConflictPolicy) { this.defaultConflictPolicy = defaultConflictPolicy; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getCredentialKeySource() { return credentialKeySource; }
    public void setCredentialKeySource(String credentialKeySource) { this.credentialKeySource = credentialKeySource; }

    public String getObsidianVaultPath() { return obsidianVaultPath; }
    public void setObsidianVaultPath(String obsidianVaultPath) { this.obsidianVaultPath = obsidianVaultPath; }

    public int getEventSyncMinInterval() { return eventSyncMinInterval; }
    public void setEventSyncMinInterval(int eventSyncMinInterval) { this.eventSyncMinInterval = eventSyncMinInterval; }

    public Connectors getConnectors() { return connectors; }
    public void setConnectors(Connectors connectors) { this.connectors = connectors; }

    // --- 嵌套配置类 ---

    /**
     * 连接器配置集合，包含各外部数据源的连接参数。
     *
     * @author zsg
     * @since 2026-02-26
     */
    public static class Connectors {

        /** CalDAV 连接器配置。 */
        private CalDav caldav = new CalDav();

        /** Todoist 连接器配置。 */
        private Todoist todoist = new Todoist();

        /** 滴答清单连接器配置。 */
        private Dida dida = new Dida();

        public CalDav getCaldav() { return caldav; }
        public void setCaldav(CalDav caldav) { this.caldav = caldav; }

        public Todoist getTodoist() { return todoist; }
        public void setTodoist(Todoist todoist) { this.todoist = todoist; }

        public Dida getDida() { return dida; }
        public void setDida(Dida dida) { this.dida = dida; }
    }

    /**
     * CalDAV 连接器配置。
     *
     * @author zsg
     * @since 2026-02-26
     */
    public static class CalDav {

        /** 是否启用 CalDAV 连接器，默认 false。 */
        private boolean enabled = false;

        /** CalDAV 服务器基础 URL。 */
        private String baseUrl = "";

        /** 认证方式：basic / oauth2，默认 basic。 */
        private String authType = "basic";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getAuthType() { return authType; }
        public void setAuthType(String authType) { this.authType = authType; }
    }

    /**
     * Todoist 连接器配置。
     *
     * @author zsg
     * @since 2026-02-26
     */
    public static class Todoist {

        /** 是否启用 Todoist 连接器，默认 false。 */
        private boolean enabled = false;

        /** Todoist API 基础 URL，默认 "https://api.todoist.com"。 */
        private String baseUrl = "https://api.todoist.com";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    /**
     * 滴答清单连接器配置。
     *
     * @author zsg
     * @since 2026-02-26
     */
    public static class Dida {

        /** 是否启用滴答清单连接器，默认 false。 */
        private boolean enabled = false;

        /** 滴答清单 Open API 基础 URL，默认 "https://api.dida365.com"。 */
        private String baseUrl = "https://api.dida365.com";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
