package com.lifepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQLite 数据源外部化配置，绑定 {@code lifepilot.datasource} 前缀。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.datasource")
public class DataSourceProperties {

    /** SQLite busy_timeout（毫秒），默认 5000。 */
    private int busyTimeout = 5000;

    public int getBusyTimeout() {
        return busyTimeout;
    }

    public void setBusyTimeout(int busyTimeout) {
        this.busyTimeout = busyTimeout;
    }
}
