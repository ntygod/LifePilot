package com.lifepilot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQLite 数据源外部化配置，绑定 {@code lifepilot.datasource} 前缀。
 *
 * @author zsg
 * @since 2026-02-25
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.datasource")
public class DataSourceProperties {

    /** SQLite busy_timeout（毫秒），默认 5000。 */
    private int busyTimeout = 5000;

}
