package com.lifepilot.config.migration;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动时配置迁移执行器。
 */
@Slf4j
@RequiredArgsConstructor
public class ConfigMigrationRunner implements ApplicationRunner {

    private final ConfigMigrationProperties properties;

    private final List<ConfigMigration> migrations;

    @Override
    public void run(ApplicationArguments args) {
        int currentVersion = properties.getConfigVersion();

        // 找出需要执行的迁移器：fromVersion >= currentVersion，并按 fromVersion 升序执行
        List<ConfigMigration> pending = migrations.stream()
                .filter(m -> m.fromVersion() >= currentVersion)
                .sorted(Comparator.comparingInt(ConfigMigration::fromVersion))
                .toList();

        if (pending.isEmpty()) {
            log.info("配置版本已是最新: version={}", currentVersion);
            return;
        }

        Map<String, Object> config = new HashMap<>();
        int latestVersion = currentVersion;
        for (ConfigMigration migration : pending) {
            try {
                log.info("执行配置迁移: {} → {}", migration.fromVersion(), migration.toVersion());
                migration.migrate(config);
                latestVersion = migration.toVersion();
            } catch (Exception ex) {
                log.error("配置迁移失败: {} → {}", migration.fromVersion(), migration.toVersion(), ex);
                throw new RuntimeException("配置迁移失败，终止启动", ex);
            }
        }

        // 更新当前配置版本号（仅内存中，用于后续逻辑判断）
        properties.setConfigVersion(latestVersion);
        log.info("配置迁移完成: {} → {}", currentVersion, latestVersion);
    }
}

