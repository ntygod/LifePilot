package com.lifepilot.config.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置迁移相关属性。
 */
@ConfigurationProperties(prefix = "lifepilot")
public class ConfigMigrationProperties {

    /**
     * 当前配置版本号，默认 1。
     */
    private int configVersion = 1;

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }
}

