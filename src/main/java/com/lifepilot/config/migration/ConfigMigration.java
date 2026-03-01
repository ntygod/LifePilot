package com.lifepilot.config.migration;

import java.util.Map;

/**
 * 配置迁移器接口，定义从一个配置版本到下一个版本的迁移逻辑。
 */
public interface ConfigMigration {

    /**
     * 源版本号。
     */
    int fromVersion();

    /**
     * 目标版本号。
     */
    int toVersion();

    /**
     * 执行配置迁移。
     *
     * @param config 配置上下文，可读写的键值对
     */
    void migrate(Map<String, Object> config);
}

