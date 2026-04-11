package com.lifepilot.config.migration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置迁移相关属性。
 *
 * @author zsg
 * @since 2026-03-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot")
public class ConfigMigrationProperties {

    /**
     * 当前配置版本号，默认 1。
     */
    private int configVersion = 1;

}

