package com.lifepilot.config.path;

import com.lifepilot.config.bootstrap.BootstrapConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link HomeMigrator} HOME 目录自动迁移逻辑测试。
 *
 * <p>覆盖同文件系统原子迁移、跨文件系统递归复制、迁移失败回滚、
 * previousHome 清除等场景。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
@ExtendWith(MockitoExtension.class)
class HomeMigrator_迁移测试 {

    @TempDir
    Path tempDir;

    @Mock
    BootstrapConfigService bootstrapConfigService;

    private HomeMigrator migrator;

    @BeforeEach
    void setUp() {
        migrator = new HomeMigrator(bootstrapConfigService);
    }

    @Nested
    class 无需迁移 {

        @Test
        void previousHome为null时_直接返回currentHome() {
            when(bootstrapConfigService.getPreviousHome()).thenReturn(null);

            Path currentHome = tempDir.resolve("new-home");
            Path result = migrator.migrateIfNeeded(currentHome);

            assertThat(result).isEqualTo(currentHome);
            assertThat(migrator.isRestartRequired()).isFalse();
            verify(bootstrapConfigService, never()).savePreviousHome(any());
        }

        @Test
        void previousHome为空白时_直接返回currentHome() {
            when(bootstrapConfigService.getPreviousHome()).thenReturn("   ");

            Path currentHome = tempDir.resolve("new-home");
            Path result = migrator.migrateIfNeeded(currentHome);

            assertThat(result).isEqualTo(currentHome);
            assertThat(migrator.isRestartRequired()).isFalse();
        }

        @Test
        void previousHome与currentHome相同时_清除字段并返回currentHome() throws IOException {
            Path home = tempDir.resolve("zhiwei");
            Files.createDirectories(home);
            when(bootstrapConfigService.getPreviousHome()).thenReturn(home.toString());

            Path result = migrator.migrateIfNeeded(home);

            assertThat(result).isEqualTo(home);
            assertThat(migrator.isRestartRequired()).isFalse();
            verify(bootstrapConfigService).savePreviousHome(null);
        }

        @Test
        void previousHome目录不存在时_清除字段并返回currentHome() {
            Path nonExistent = tempDir.resolve("non-existent");
            when(bootstrapConfigService.getPreviousHome()).thenReturn(nonExistent.toString());

            Path currentHome = tempDir.resolve("new-home");
            Path result = migrator.migrateIfNeeded(currentHome);

            assertThat(result).isEqualTo(currentHome);
            assertThat(migrator.isRestartRequired()).isFalse();
            verify(bootstrapConfigService).savePreviousHome(null);
        }
    }

    @Nested
    class 同文件系统迁移 {

        @Test
        void 原子迁移成功_数据移动到新目录() throws IOException {
            // 准备旧 HOME 目录及文件
            Path oldHome = tempDir.resolve("old-home");
            Files.createDirectories(oldHome.resolve("db"));
            Files.writeString(oldHome.resolve("db/zhiwei.db"), "database content");
            Files.createDirectories(oldHome.resolve("skills"));
            Files.writeString(oldHome.resolve("skills/test.yml"), "skill: test");

            Path newHome = tempDir.resolve("new-home");

            when(bootstrapConfigService.getPreviousHome()).thenReturn(oldHome.toString());

            Path result = migrator.migrateIfNeeded(newHome);

            // 验证迁移结果
            assertThat(result).isEqualTo(newHome);
            assertThat(migrator.isRestartRequired()).isTrue();

            // 验证文件已迁移到新目录
            assertThat(Files.readString(newHome.resolve("db/zhiwei.db"))).isEqualTo("database content");
            assertThat(Files.readString(newHome.resolve("skills/test.yml"))).isEqualTo("skill: test");

            // 验证旧目录已不存在
            assertThat(Files.exists(oldHome)).isFalse();

            // 验证 previousHome 已清除
            verify(bootstrapConfigService).savePreviousHome(null);
        }

        @Test
        void 迁移成功后_标记需要重启() throws IOException {
            Path oldHome = tempDir.resolve("old-home");
            Files.createDirectories(oldHome);
            Files.writeString(oldHome.resolve("test.txt"), "hello");

            Path newHome = tempDir.resolve("new-home");

            when(bootstrapConfigService.getPreviousHome()).thenReturn(oldHome.toString());

            migrator.migrateIfNeeded(newHome);

            assertThat(migrator.isRestartRequired()).isTrue();
        }
    }

    @Nested
    class 迁移失败回滚 {

        @Test
        void 目标目录父路径不可写时_回滚到旧路径() throws IOException {
            Path oldHome = tempDir.resolve("old-home");
            Files.createDirectories(oldHome);
            Files.writeString(oldHome.resolve("data.txt"), "important data");

            // 使用一个无法创建的路径（在已存在的文件上创建子目录）
            Path blocker = tempDir.resolve("blocker");
            Files.writeString(blocker, "I am a file, not a directory");
            Path newHome = blocker.resolve("sub").resolve("new-home");

            when(bootstrapConfigService.getPreviousHome()).thenReturn(oldHome.toString());

            Path result = migrator.migrateIfNeeded(newHome);

            // 验证回滚：返回旧路径
            assertThat(result).isEqualTo(oldHome);
            assertThat(migrator.isRestartRequired()).isFalse();

            // 验证旧目录数据完好
            assertThat(Files.readString(oldHome.resolve("data.txt"))).isEqualTo("important data");

            // 验证回滚操作：home 恢复为旧路径，previousHome 清除
            verify(bootstrapConfigService).saveHome(oldHome.toString());
            verify(bootstrapConfigService).savePreviousHome(null);
        }
    }

    @Nested
    class 目标目录已存在 {

        @Test
        void 目标目录非空时_合并迁移() throws IOException {
            // 旧 HOME 有文件
            Path oldHome = tempDir.resolve("old-home");
            Files.createDirectories(oldHome.resolve("skills"));
            Files.writeString(oldHome.resolve("skills/old-skill.yml"), "old skill");
            Files.writeString(oldHome.resolve("config.txt"), "old config");

            // 新 HOME 已有部分文件（模拟部分迁移残留）
            Path newHome = tempDir.resolve("new-home");
            Files.createDirectories(newHome.resolve("skills"));
            Files.writeString(newHome.resolve("skills/new-skill.yml"), "new skill");

            when(bootstrapConfigService.getPreviousHome()).thenReturn(oldHome.toString());

            Path result = migrator.migrateIfNeeded(newHome);

            // 验证迁移成功
            assertThat(result).isEqualTo(newHome);
            assertThat(migrator.isRestartRequired()).isTrue();

            // 验证旧文件已复制到新目录
            assertThat(Files.readString(newHome.resolve("skills/old-skill.yml"))).isEqualTo("old skill");
            assertThat(Files.readString(newHome.resolve("config.txt"))).isEqualTo("old config");

            // 验证新目录原有文件保留
            assertThat(Files.readString(newHome.resolve("skills/new-skill.yml"))).isEqualTo("new skill");

            // 验证旧目录已删除
            assertThat(Files.exists(oldHome)).isFalse();
        }
    }
}
