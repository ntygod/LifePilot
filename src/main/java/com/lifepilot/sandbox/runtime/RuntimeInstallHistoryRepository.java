package com.lifepilot.sandbox.runtime;

import java.time.Instant;
import java.util.UUID;

import jakarta.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运行时安装历史持久化仓库 — 记录 install / uninstall / enable / disable 行为审计。
 *
 * <p><b>注：</b>本类不带 {@code @Repository} 注解，因为 ComponentScan 与 SandboxAutoConfiguration
 * 的 {@code @Bean} 方法会产生双注册冲突（Spring Boot 默认禁止 bean override）。
 * 改由 {@link com.lifepilot.sandbox.config.SandboxAutoConfiguration} 通过 {@code @Bean} +
 * {@code @ConditionalOnBean(JdbcTemplate.class)} 集中注册，保留无数据库测试场景下的优雅降级能力。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class RuntimeInstallHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInstallHistoryRepository.class);

    private final JdbcTemplate jdbc;

    public RuntimeInstallHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条历史记录。
     *
     * @param runtimeKind 运行时类型，如 "python"
     * @param version     版本号
     * @param action      行为，如 "install" / "uninstall" / "enable" / "disable"
     * @param status      结果，如 "success" / "failed"
     * @param errorMsg    失败原因，可选
     * @param durationMs  耗时，毫秒
     */
    public void insert(String runtimeKind, String version, String action, String status,
                       @Nullable String errorMsg, long durationMs) {
        try {
            jdbc.update("""
                INSERT INTO runtime_install_history(id, runtime_kind, version, action, status, error_msg, duration_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), runtimeKind, version, action, status, errorMsg, durationMs,
                    Instant.now().toString());
        } catch (Exception e) {
            // 历史记录写入失败不应影响主流程，但属于需要人工介入的故障，按 ERROR 级别记录完整上下文
            log.error("写入 runtime_install_history 失败（忽略），runtimeKind={}, action={}, version={}",
                    runtimeKind, action, version, e);
        }
    }
}
