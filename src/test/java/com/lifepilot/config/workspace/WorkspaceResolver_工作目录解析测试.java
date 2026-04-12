package com.lifepilot.config.workspace;

import com.lifepilot.interaction.web.model.UserSettings;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * WorkspaceResolver 工作目录解析测试。
 *
 * <p>覆盖三级优先级解析（用户设置 > yml 配置 > 默认路径）以及目录创建行为。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceResolver 工作目录解析")
class WorkspaceResolver_工作目录解析测试 {

    @Mock
    private UserSettingsRepository settingsRepository;

    /** 计算出的默认路径，用于断言比较 */
    private static final Path SYSTEM_DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".zhiwei", "workspace");

    // ─── 默认路径（无用户设置、无 yml 配置） ───

    @Nested
    @DisplayName("无用户设置且无 yml 配置时")
    class 默认路径 {

        private WorkspaceResolver resolver;

        @BeforeEach
        void 初始化() {
            // configuredDir 为空字符串，模拟 yml 未配置
            resolver = new WorkspaceResolver(settingsRepository, "");
        }

        @Test
        void 返回默认的用户主目录下路径() {
            // given — settingsRepository 返回无 defaultWorkspace 的设置
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true, null)
            );

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(SYSTEM_DEFAULT_PATH);
        }

        @Test
        void getDefaultDir返回系统默认路径字符串() {
            // when & then
            assertThat(resolver.getDefaultDir()).isEqualTo(SYSTEM_DEFAULT_PATH.toString());
        }
    }

    // ─── yml 配置路径 ───

    @Nested
    @DisplayName("有 yml 配置时")
    class yml配置路径 {

        @TempDir
        Path tempDir;

        private WorkspaceResolver resolver;

        @BeforeEach
        void 初始化() {
            // 使用临时目录作为 yml 配置路径
            resolver = new WorkspaceResolver(settingsRepository, tempDir.toString());
        }

        @Test
        void 使用yml配置的路径() {
            // given — 用户设置中无自定义工作目录
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true, null)
            );

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(tempDir);
        }

        @Test
        void getDefaultDir返回yml配置路径() {
            // when & then
            assertThat(resolver.getDefaultDir()).isEqualTo(tempDir.toString());
        }
    }

    // ─── 用户设置优先 ───

    @Nested
    @DisplayName("有用户设置时")
    class 用户设置优先 {

        @TempDir
        Path tempDir;

        @Test
        void 优先使用用户设置的绝对路径() {
            // given
            Path userWorkspace = tempDir.resolve("my-workspace");
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("dark", "zh-CN", true, true, true, true,
                            userWorkspace.toAbsolutePath().toString())
            );
            var resolver = new WorkspaceResolver(settingsRepository, "/some/yml/path");

            // when
            Path result = resolver.resolve();

            // then — 用户设置路径应覆盖 yml 配置
            assertThat(result).isEqualTo(userWorkspace.toAbsolutePath());
        }

        @Test
        void 用户设置为非绝对路径时忽略并回退到yml配置() {
            // given — 相对路径应被忽略
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true, "relative/path")
            );
            var resolver = new WorkspaceResolver(settingsRepository, tempDir.toString());

            // when
            Path result = resolver.resolve();

            // then — 应回退到 yml 配置路径
            assertThat(result).isEqualTo(tempDir);
        }

        @Test
        void 用户设置为空字符串时忽略并回退() {
            // given
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true, "")
            );
            var resolver = new WorkspaceResolver(settingsRepository, tempDir.toString());

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(tempDir);
        }

        @Test
        void 用户设置为纯空白字符串时忽略并回退() {
            // given
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true, "   ")
            );
            var resolver = new WorkspaceResolver(settingsRepository, tempDir.toString());

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(tempDir);
        }
    }

    // ─── settingsRepository 为 null ───

    @Nested
    @DisplayName("settingsRepository 为 null 时")
    class Repository为null {

        @TempDir
        Path tempDir;

        @Test
        void 不报错并使用yml配置路径() {
            // given — repository 传 null
            var resolver = new WorkspaceResolver(null, tempDir.toString());

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(tempDir);
        }

        @Test
        void 不报错并使用系统默认路径() {
            // given — repository 和 yml 都缺失
            var resolver = new WorkspaceResolver(null, "");

            // when
            Path result = resolver.resolve();

            // then
            assertThat(result).isEqualTo(SYSTEM_DEFAULT_PATH);
        }
    }

    // ─── getSettings() 抛异常时的降级 ───

    @Nested
    @DisplayName("getSettings() 抛异常时")
    class 读取设置异常 {

        @TempDir
        Path tempDir;

        @Test
        void 捕获异常并回退到默认路径() {
            // given
            when(settingsRepository.getSettings())
                    .thenThrow(new RuntimeException("数据库连接失败"));
            var resolver = new WorkspaceResolver(settingsRepository, tempDir.toString());

            // when
            Path result = resolver.resolve();

            // then — 异常不应传播，应回退到默认值
            assertThat(result).isEqualTo(tempDir);
        }
    }

    // ─── resolveAndCreate() 目录创建 ───

    @Nested
    @DisplayName("resolveAndCreate() 目录创建")
    class 目录创建 {

        @TempDir
        Path tempDir;

        @Test
        void 目录不存在时自动创建() {
            // given — 指定一个尚不存在的子目录
            Path targetDir = tempDir.resolve("new-workspace");
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true,
                            targetDir.toAbsolutePath().toString())
            );
            var resolver = new WorkspaceResolver(settingsRepository, "");

            // when
            Path result = resolver.resolveAndCreate();

            // then
            assertThat(result).isEqualTo(targetDir.toAbsolutePath());
            assertThat(Files.exists(result)).isTrue();
            assertThat(Files.isDirectory(result)).isTrue();
        }

        @Test
        void 目录已存在时不报错() {
            // given — tempDir 本身已存在
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true,
                            tempDir.toAbsolutePath().toString())
            );
            var resolver = new WorkspaceResolver(settingsRepository, "");

            // when
            Path result = resolver.resolveAndCreate();

            // then
            assertThat(result).isEqualTo(tempDir.toAbsolutePath());
            assertThat(Files.isDirectory(result)).isTrue();
        }

        @Test
        void 创建多层嵌套目录() {
            // given
            Path deepDir = tempDir.resolve("a").resolve("b").resolve("c");
            when(settingsRepository.getSettings()).thenReturn(
                    new UserSettings("system", "zh-CN", true, true, true, true,
                            deepDir.toAbsolutePath().toString())
            );
            var resolver = new WorkspaceResolver(settingsRepository, "");

            // when
            Path result = resolver.resolveAndCreate();

            // then
            assertThat(result).isEqualTo(deepDir.toAbsolutePath());
            assertThat(Files.exists(result)).isTrue();
        }
    }
}
