package com.lifepilot.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知模块配置属性。
 *
 * <p>通过 {@code lifepilot.notification.*} 配置键外部化通知相关的业务可调参数。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ConfigurationProperties(prefix = "lifepilot.notification")
public class NotificationProperties {

    /** 通知模块总开关。 */
    private boolean enabled = true;

    /** 被动通知队列 drain 间隔（秒）。 */
    private long passiveDrainInterval = 60;

    /** 通知历史默认分页大小。 */
    private int historyPageSize = 20;

    /** 通知历史最大分页大小。 */
    private int maxHistoryPageSize = 100;

    /** 默认目标用户 ID，所有内部通知（cron、heartbeat、agent.notify）统一使用此值。 */
    private String defaultUserId = "default";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPassiveDrainInterval() {
        return passiveDrainInterval;
    }

    public void setPassiveDrainInterval(long passiveDrainInterval) {
        this.passiveDrainInterval = passiveDrainInterval;
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public void setHistoryPageSize(int historyPageSize) {
        this.historyPageSize = historyPageSize;
    }

    public int getMaxHistoryPageSize() {
        return maxHistoryPageSize;
    }

    public void setMaxHistoryPageSize(int maxHistoryPageSize) {
        this.maxHistoryPageSize = maxHistoryPageSize;
    }

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }
}
