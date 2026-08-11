package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.path.ZhiweiPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalBackupService} 单元测试。
 *
 * @author zsg
 * @since 2026-07-04
 */
class LocalBackupService_单元测试 {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 创建备份_打包核心数据并排除膨胀目录() throws Exception {
        Files.createDirectories(tempDir.resolve("db"));
        Files.createDirectories(tempDir.resolve("knowledge"));
        Files.createDirectories(tempDir.resolve("cache"));
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.createDirectories(tempDir.resolve("logs"));
        Files.createDirectories(tempDir.resolve("backups"));
        Files.writeString(tempDir.resolve("db/zhiwei.db"), "db");
        Files.writeString(tempDir.resolve("knowledge/doc.txt"), "doc");
        Files.writeString(tempDir.resolve("cache/tmp.bin"), "cache");
        Files.writeString(tempDir.resolve("runtime/java.bin"), "runtime");
        Files.writeString(tempDir.resolve("logs/app.log"), "log");
        Files.writeString(tempDir.resolve("backups/old.zip"), "old");

        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        var service = new LocalBackupService(paths, objectMapper);

        var backup = service.createBackup();

        assertThat(backup.fileName()).startsWith("zhiwei-backup-").endsWith(".zip");
        assertThat(backup.sizeBytes()).isGreaterThan(0);
        assertThat(backup.includedFileCount()).isEqualTo(2);
        assertThat(Path.of(backup.path())).startsWith(tempDir.resolve("backups"));
        try (var zip = new ZipFile(backup.path())) {
            assertThat(zip.getEntry("db/zhiwei.db")).isNotNull();
            assertThat(zip.getEntry("knowledge/doc.txt")).isNotNull();
            assertThat(zip.getEntry("cache/tmp.bin")).isNull();
            assertThat(zip.getEntry("runtime/java.bin")).isNull();
            assertThat(zip.getEntry("logs/app.log")).isNull();
            assertThat(zip.getEntry("backups/old.zip")).isNull();
            assertThat(zip.getEntry(".zhiwei-backup-manifest.json")).isNotNull();
        }
    }

    @Test
    void 查询备份列表_按时间倒序返回知微备份文件() throws Exception {
        var backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        var older = backupDir.resolve("zhiwei-backup-20260704-100000.zip");
        var newer = backupDir.resolve("zhiwei-backup-20260704-110000.zip");
        var ignored = backupDir.resolve("manual.zip");
        Files.writeString(older, "old");
        Files.writeString(newer, "newer");
        Files.writeString(ignored, "ignored");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-07-04T10:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-07-04T11:00:00Z")));

        var paths = mock(ZhiweiPaths.class);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(backupDir);
        var service = new LocalBackupService(paths, objectMapper);

        var backups = service.listBackups();

        assertThat(backups).hasSize(2);
        assertThat(backups).extracting("fileName")
                .containsExactly("zhiwei-backup-20260704-110000.zip", "zhiwei-backup-20260704-100000.zip");
        assertThat(backups.get(0).modifiedAt()).isEqualTo(Instant.parse("2026-07-04T11:00:00Z"));
        assertThat(backups.get(0).path()).endsWith("zhiwei-backup-20260704-110000.zip");
        assertThat(backups.get(0).sizeBytes()).isGreaterThan(0);
    }

    @Test
    void 查询备份列表_目录不存在时返回空列表() throws Exception {
        var paths = mock(ZhiweiPaths.class);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("missing-backups"));
        var service = new LocalBackupService(paths, objectMapper);

        assertThat(service.listBackups()).isEmpty();
    }

    @Test
    void 校验备份_新格式备份返回正常() throws Exception {
        Files.createDirectories(tempDir.resolve("db"));
        Files.createDirectories(tempDir.resolve("backups"));
        Files.writeString(tempDir.resolve("db/zhiwei.db"), "db");

        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        var service = new LocalBackupService(paths, objectMapper);
        var backup = service.createBackup();

        var validation = service.validateBackup(backup.fileName());

        assertThat(validation.status()).isEqualTo("OK");
        assertThat(validation.manifestPresent()).isTrue();
        assertThat(validation.manifest()).isNotNull();
        assertThat(validation.manifest().includedFileCount()).isEqualTo(1);
        assertThat(validation.problems()).isEmpty();
        assertThat(validation.entryCount()).isEqualTo(2);
        assertThat(validation.restorePlan()).isNotNull();
        assertThat(validation.restorePlan().restoreMode()).isEqualTo("manual-staging");
        assertThat(validation.restorePlan().manualRestoreOnly()).isTrue();
        assertThat(validation.restorePlan().includedTopLevelItems()).containsExactly("db");
        assertThat(validation.restorePlan().excludedTopLevelDirs())
                .containsExactlyInAnyOrder("backups", "cache", "logs", "runtime");
        assertThat(validation.restorePlan().targetHome()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(validation.restorePlan().currentHomeHasData()).isTrue();
        assertThat(validation.restorePlan().currentHomeFileCount()).isEqualTo(1);
        assertThat(validation.restorePlan().backupIncludedFileCount()).isEqualTo(1);
        assertThat(validation.restorePlan().restoreStagingDirectory())
                .endsWith(tempDir.resolve("runtime/restore-staging").toString());
        assertThat(validation.restorePlan().backupSizeBytes()).isEqualTo(validation.sizeBytes());
        assertThat(validation.restorePlan().estimatedRestoreBytes()).isGreaterThan(0);
        assertThat(validation.restorePlan().targetUsableBytes()).isGreaterThanOrEqualTo(0);
        assertThat(validation.restorePlan().restoreSpaceStatus()).isEqualTo("OK");
        assertThat(validation.restorePlan().warnings())
                .contains(
                        "当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。",
                        "恢复准备目录只应复制这些顶层数据项：db。");
        assertThat(validation.restorePlan().requiredSteps())
                .contains(
                        "将备份 zip 解压到临时目录，检查文件结构和清单。",
                        "确认无误后，只把恢复准备目录中的 db 复制到目标 HOME。");
    }

    @Test
    void 校验备份_老格式可读但提示重新备份() throws Exception {
        var backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        var legacyBackup = backupDir.resolve("zhiwei-backup-legacy.zip");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(legacyBackup))) {
            output.putNextEntry(new java.util.zip.ZipEntry("db/zhiwei.db"));
            output.write("db".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(backupDir);
        var service = new LocalBackupService(paths, objectMapper);

        var validation = service.validateBackup(legacyBackup.getFileName().toString());

        assertThat(validation.status()).isEqualTo("WARN");
        assertThat(validation.manifestPresent()).isFalse();
        assertThat(validation.problems()).contains("备份包缺少清单，建议重新创建备份");
        assertThat(validation.restorePlan().backupSourceHome()).contains("未知");
        assertThat(validation.restorePlan().backupIncludedFileCount()).isEqualTo(-1);
        assertThat(validation.restorePlan().includedTopLevelItems()).containsExactly("db");
        assertThat(validation.restorePlan().excludedTopLevelDirs()).isEmpty();
        assertThat(validation.restorePlan().warnings()).contains("备份缺少清单，无法确认来源 HOME 和数据文件数。");
    }

    @Test
    void 校验备份_拒绝路径越界文件名() throws Exception {
        var paths = mock(ZhiweiPaths.class);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        var service = new LocalBackupService(paths, objectMapper);

        var validation = service.validateBackup("../zhiwei-backup.zip");

        assertThat(validation.status()).isEqualTo("ERROR");
        assertThat(validation.problems()).contains("只能校验 backups 目录下的知微备份 zip 文件");
        assertThat(validation.restorePlan()).isNull();
    }

    @Test
    void 准备恢复_将备份安全解压到运行时暂存目录() throws Exception {
        Files.createDirectories(tempDir.resolve("db"));
        Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(tempDir.resolve("backups"));
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("db/zhiwei.db"), "db");
        Files.writeString(tempDir.resolve("skills/SKILL.md"), "skill");

        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        when(paths.home(ZhiweiPaths.DIR_RUNTIME)).thenReturn(tempDir.resolve("runtime"));
        var service = new LocalBackupService(paths, objectMapper);
        var backup = service.createBackup();

        var preparation = service.prepareRestore(backup.fileName());

        Path restoreDirectory = Path.of(preparation.restoreDirectory());
        assertThat(restoreDirectory).startsWith(tempDir.resolve("runtime/restore-staging"));
        assertThat(restoreDirectory.resolve("db/zhiwei.db")).hasContent("db");
        assertThat(restoreDirectory.resolve("skills/SKILL.md")).hasContent("skill");
        assertThat(restoreDirectory.resolve(".zhiwei-backup-manifest.json")).exists();
        assertThat(preparation.fileName()).isEqualTo(backup.fileName());
        assertThat(preparation.extractedFileCount()).isEqualTo(3);
        assertThat(preparation.extractedBytes()).isGreaterThan(0);
        assertThat(preparation.warnings()).contains("当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。");
        assertThat(preparation.nextSteps()).contains(
                "打开恢复准备目录，检查文件结构和备份清单。",
                "只把恢复准备目录中的 db、skills 复制到目标 HOME。");
    }

    @Test
    void 准备恢复_拒绝校验失败的备份() throws Exception {
        var paths = mock(ZhiweiPaths.class);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(tempDir.resolve("backups"));
        var service = new LocalBackupService(paths, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareRestore("../zhiwei-backup.zip"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("备份校验失败");
    }

    @Test
    void 准备恢复_解压失败时清理半成品目录() throws Exception {
        var backupDir = tempDir.resolve("backups");
        var runtimeDir = tempDir.resolve("runtime");
        Files.createDirectories(backupDir);
        Files.createDirectories(runtimeDir);
        var brokenBackup = backupDir.resolve("zhiwei-backup-conflict.zip");
        try (var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(brokenBackup))) {
            output.putNextEntry(new java.util.zip.ZipEntry("db/zhiwei.db"));
            output.write("db".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("db"));
            output.write("conflict".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        var paths = mock(ZhiweiPaths.class);
        when(paths.home()).thenReturn(tempDir);
        when(paths.home(ZhiweiPaths.DIR_BACKUPS)).thenReturn(backupDir);
        when(paths.home(ZhiweiPaths.DIR_RUNTIME)).thenReturn(runtimeDir);
        var service = new LocalBackupService(paths, objectMapper);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepareRestore(brokenBackup.getFileName().toString()))
                .isInstanceOf(java.io.IOException.class);

        try (var stream = Files.list(runtimeDir.resolve("restore-staging"))) {
            assertThat(stream.toList()).isEmpty();
        }
    }
}
