package com.lifepilot.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 验证 {@link RuntimeStatusJson#toMap} 输出符合前端契约。
 *
 * <p>关键回归点：sealed record 默认 Jackson 序列化不会加 status 判别字段，
 * 也不会序列化 Installing.percent() 非 component 方法。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
class RuntimeStatusJson_序列化测试 {

    @Test
    void NotInstalled_序列化只含_status_字段() {
        Map<String, Object> map = RuntimeStatusJson.toMap(new RuntimeStatus.NotInstalled());
        assertThat(map).containsExactly(Map.entry("status", "NOT_INSTALLED"));
    }

    @Test
    void Disabled_序列化只含_status_字段() {
        Map<String, Object> map = RuntimeStatusJson.toMap(new RuntimeStatus.Disabled());
        assertThat(map).containsExactly(Map.entry("status", "DISABLED"));
    }

    @Test
    void Installing_序列化包含_status_judiscriminator_phase_percent() {
        Map<String, Object> map = RuntimeStatusJson.toMap(
                new RuntimeStatus.Installing("download", 100L, 200L));

        assertThat(map).containsEntry("status", "INSTALLING");
        assertThat(map).containsEntry("phase", "download");
        assertThat(map).containsEntry("bytesDownloaded", 100L);
        assertThat(map).containsEntry("totalBytes", 200L);
        // percent() 是非 component 方法，必须显式序列化
        assertThat(map).containsEntry("percent", 50);
    }

    @Test
    void Installing_零字节_总字节时_percent_为0_不抛除零异常() {
        Map<String, Object> map = RuntimeStatusJson.toMap(
                new RuntimeStatus.Installing("init", 0L, 0L));
        assertThat(map).containsEntry("percent", 0);
    }

    @Test
    void Ready_序列化包含_version_diskBytes() {
        Map<String, Object> map = RuntimeStatusJson.toMap(
                new RuntimeStatus.Ready("3.12.13", 250_000_000L));

        assertThat(map).containsEntry("status", "READY");
        assertThat(map).containsEntry("version", "3.12.13");
        assertThat(map).containsEntry("diskBytes", 250_000_000L);
    }

    @Test
    void InstallFailed_序列化包含_reason() {
        Map<String, Object> map = RuntimeStatusJson.toMap(
                new RuntimeStatus.InstallFailed("HTTP 404 for tarball"));

        assertThat(map).containsEntry("status", "INSTALL_FAILED");
        assertThat(map).containsEntry("reason", "HTTP 404 for tarball");
    }
}
