package com.lifepilot.interaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

import java.time.Duration;

/**
 * 官方 connector 托管配置。
 *
 * <p>用于控制主服务是否自动拉起官方 connector、端口分配范围，以及
 * 已安装运行时产物 / 本地插件工作区的发现逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-30
 */
@ConfigurationProperties(prefix = "lifepilot.gateway.channels.connector-manager")
public class ConnectorManagerProperties {

    private boolean enabled = true;
    private boolean autoManageOfficial = true;
    private String host = "127.0.0.1";
    private int portRangeStart = 19091;
    private int portRangeEnd = 19120;
    private Duration startupTimeout = Duration.ofSeconds(45);
    @Nullable
    private String workspaceRoot;
    @Nullable
    private String zhiweiBaseUrl;
    /** 单个入站附件最大允许字节数，默认 10MB。 */
    private long maxAttachmentSizeBytes = 10L * 1024 * 1024;
    /** 事件去重缓存容量上限，用于防止平台重试导致重复处理。 */
    private int eventDeduplicationCacheSize = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoManageOfficial() {
        return autoManageOfficial;
    }

    public void setAutoManageOfficial(boolean autoManageOfficial) {
        this.autoManageOfficial = autoManageOfficial;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPortRangeStart() {
        return portRangeStart;
    }

    public void setPortRangeStart(int portRangeStart) {
        this.portRangeStart = portRangeStart;
    }

    public int getPortRangeEnd() {
        return portRangeEnd;
    }

    public void setPortRangeEnd(int portRangeEnd) {
        this.portRangeEnd = portRangeEnd;
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(Duration startupTimeout) {
        this.startupTimeout = startupTimeout;
    }

    @Nullable
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(@Nullable String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Nullable
    public String getZhiweiBaseUrl() {
        return zhiweiBaseUrl;
    }

    public void setZhiweiBaseUrl(@Nullable String zhiweiBaseUrl) {
        this.zhiweiBaseUrl = zhiweiBaseUrl;
    }

    public long getMaxAttachmentSizeBytes() {
        return maxAttachmentSizeBytes;
    }

    public void setMaxAttachmentSizeBytes(long maxAttachmentSizeBytes) {
        this.maxAttachmentSizeBytes = maxAttachmentSizeBytes;
    }

    public int getEventDeduplicationCacheSize() {
        return eventDeduplicationCacheSize;
    }

    public void setEventDeduplicationCacheSize(int eventDeduplicationCacheSize) {
        this.eventDeduplicationCacheSize = eventDeduplicationCacheSize;
    }
}
