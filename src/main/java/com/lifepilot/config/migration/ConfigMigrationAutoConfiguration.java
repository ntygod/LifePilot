package com.lifepilot.config.migration;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 配置迁移自动装配。
 *
 * @author zsg
 * @since 2026-03-01
 */
@AutoConfiguration
@EnableConfigurationProperties(ConfigMigrationProperties.class)
public class ConfigMigrationAutoConfiguration {

    @Bean
    public ConfigMigrationRunner configMigrationRunner(ConfigMigrationProperties properties,
                                                       List<ConfigMigration> migrations) {
        return new ConfigMigrationRunner(properties, migrations);
    }
}

