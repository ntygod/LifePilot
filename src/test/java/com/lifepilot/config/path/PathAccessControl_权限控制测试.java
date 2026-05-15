package com.lifepilot.config.path;

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
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * {@link PathAccessControl} 路径权限控制组件测试。
 *
 * <p>覆盖四种模式、路径前缀匹配、HOME 默认黑名单、拒绝消息等场景。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
@ExtendWith(MockitoExtension.class)
class PathAccessControl_权限控制测试 {

    @TempDir
    Path tempDir;

    @Mock
    ZhiweiPaths zhiweiPaths;

    private Path homeDir;
    private Path projectDir;
    private Path sensitiveDir;

    @BeforeEach
    void setUp() throws IOException {
        homeDir = tempDir.resolve("zhiwei");
        projectDir = tempDir.resolve("projects");
        sensitiveDir = tempDir.resolve("sensitive");
        Files.createDirectories(homeDir);
        Files.createDirectories(projectDir);
        Files.createDirectories(sensitiveDir);
        lenient().when(zhiweiPaths.home()).thenReturn(homeDir);
    }

    /**
     * 创建一个使用指定 HOME 目录的 PathAccessControl 实例（绕过 Spring 初始化）。
     */
    private PathAccessControl createControl(PathAccessControl.Mode mode,
                                            List<Path> whitelist,
                                            List<Path> blacklist) {
        PathAccessControl control = new PathAccessControl(zhiweiPaths);
        control.updateRules(mode, whitelist, blacklist);
        return control;
    }

    // ==================== unrestricted 模式 ====================

    @Nested
    class Unrestricted模式 {

        @Test
        void 允许所有路径() {
            var control = createControl(PathAccessControl.Mode.UNRESTRICTED, List.of(), List.of());

            assertThat(control.isAllowed(projectDir.resolve("file.txt")).allowed()).isTrue();
            assertThat(control.isAllowed(sensitiveDir.resolve("secret.key")).allowed()).isTrue();
            assertThat(control.isAllowed(homeDir.resolve("db/zhiwei.db")).allowed()).isTrue();
        }
    }

    // ==================== whitelist-only 模式 ====================

    @Nested
    class WhitelistOnly模式 {

        @Test
        void 白名单内路径允许访问() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            assertThat(control.isAllowed(projectDir.resolve("src/Main.java")).allowed()).isTrue();
        }

        @Test
        void 白名单外路径拒绝访问() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            var result = control.isAllowed(sensitiveDir.resolve("secret.key"));
            assertThat(result.allowed()).isFalse();
            assertThat(result).isInstanceOf(PathAccessControl.AccessResult.Denied.class);
        }

        @Test
        void 拒绝消息包含路径信息() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            var result = control.isAllowed(sensitiveDir.resolve("secret.key"));
            assertThat(result).isInstanceOf(PathAccessControl.AccessResult.Denied.class);
            var denied = (PathAccessControl.AccessResult.Denied) result;
            assertThat(denied.reason()).contains("不在白名单范围内");
        }

        @Test
        void 白名单精确前缀匹配_不匹配部分路径名() {
            // projectDir = .../projects，不应匹配 .../projects-backup
            Path projectsBackup = tempDir.resolve("projects-backup");
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            var result = control.isAllowed(projectsBackup.resolve("file.txt"));
            assertThat(result.allowed()).isFalse();
        }
    }

    // ==================== blacklist-only 模式 ====================

    @Nested
    class BlacklistOnly模式 {

        @Test
        void 黑名单外路径允许访问() {
            var control = createControl(
                    PathAccessControl.Mode.BLACKLIST_ONLY,
                    List.of(),
                    List.of(sensitiveDir));

            assertThat(control.isAllowed(projectDir.resolve("file.txt")).allowed()).isTrue();
        }

        @Test
        void 黑名单内路径拒绝访问() {
            var control = createControl(
                    PathAccessControl.Mode.BLACKLIST_ONLY,
                    List.of(),
                    List.of(sensitiveDir));

            var result = control.isAllowed(sensitiveDir.resolve("secret.key"));
            assertThat(result.allowed()).isFalse();
        }

        @Test
        void 拒绝消息指明匹配的黑名单条目() {
            var control = createControl(
                    PathAccessControl.Mode.BLACKLIST_ONLY,
                    List.of(),
                    List.of(sensitiveDir));

            var result = control.isAllowed(sensitiveDir.resolve("secret.key"));
            assertThat(result).isInstanceOf(PathAccessControl.AccessResult.Denied.class);
            var denied = (PathAccessControl.AccessResult.Denied) result;
            assertThat(denied.reason()).contains("黑名单规则阻止");
            assertThat(denied.reason()).contains(sensitiveDir.toString());
        }
    }

    // ==================== whitelist-plus-blacklist 模式 ====================

    @Nested
    class WhitelistPlusBlacklist模式 {

        @Test
        void 白名单内且不在黑名单的路径允许访问() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_PLUS_BLACKLIST,
                    List.of(projectDir),
                    List.of(sensitiveDir));

            assertThat(control.isAllowed(projectDir.resolve("src/Main.java")).allowed()).isTrue();
        }

        @Test
        void 黑名单优先于白名单() {
            // projectDir 在白名单中，但其子目录 projectDir/secret 在黑名单中
            Path secretSubDir = projectDir.resolve("secret");
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_PLUS_BLACKLIST,
                    List.of(projectDir),
                    List.of(secretSubDir));

            // 普通子目录允许
            assertThat(control.isAllowed(projectDir.resolve("src/Main.java")).allowed()).isTrue();
            // 黑名单子目录拒绝
            var result = control.isAllowed(secretSubDir.resolve("key.pem"));
            assertThat(result.allowed()).isFalse();
        }

        @Test
        void 黑名单拒绝消息说明即使在白名单范围内() {
            Path secretSubDir = projectDir.resolve("secret");
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_PLUS_BLACKLIST,
                    List.of(projectDir),
                    List.of(secretSubDir));

            var result = control.isAllowed(secretSubDir.resolve("key.pem"));
            assertThat(result).isInstanceOf(PathAccessControl.AccessResult.Denied.class);
            var denied = (PathAccessControl.AccessResult.Denied) result;
            assertThat(denied.reason()).contains("即使在白名单范围内");
        }

        @Test
        void 不在白名单也不在黑名单的路径拒绝访问() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_PLUS_BLACKLIST,
                    List.of(projectDir),
                    List.of(sensitiveDir));

            // tempDir 本身不在白名单中
            Path outsidePath = tempDir.resolve("other/file.txt");
            var result = control.isAllowed(outsidePath);
            assertThat(result.allowed()).isFalse();
        }
    }

    // ==================== HOME 默认黑名单 ====================

    @Nested
    class HOME默认黑名单 {

        @Test
        void HOME目录始终在黑名单中() {
            var control = createControl(
                    PathAccessControl.Mode.BLACKLIST_ONLY,
                    List.of(),
                    List.of()); // 不额外传入黑名单，updateRules 会自动加 HOME

            var result = control.isAllowed(homeDir.resolve("db/zhiwei.db"));
            assertThat(result.allowed()).isFalse();
        }

        @Test
        void 更新规则后HOME仍在黑名单中() {
            PathAccessControl control = new PathAccessControl(zhiweiPaths);

            // 更新规则时不传入 HOME，但 HOME 应自动追加
            control.updateRules(
                    PathAccessControl.Mode.BLACKLIST_ONLY,
                    List.of(),
                    List.of(sensitiveDir));

            assertThat(control.getBlacklist()).contains(homeDir);
            var result = control.isAllowed(homeDir.resolve("skills/test.yaml"));
            assertThat(result.allowed()).isFalse();
        }
    }

    // ==================== 路径前缀匹配 ====================

    @Nested
    class 路径前缀匹配 {

        @Test
        void 子目录匹配父目录前缀() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            // 深层子目录应匹配
            assertThat(control.isAllowed(projectDir.resolve("a/b/c/d.txt")).allowed()).isTrue();
        }

        @Test
        void 目录本身匹配() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            assertThat(control.isAllowed(projectDir).allowed()).isTrue();
        }

        @Test
        void 不同目录不匹配() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            var result = control.isAllowed(sensitiveDir.resolve("file.txt"));
            assertThat(result.allowed()).isFalse();
        }
    }

    // ==================== validate 便捷方法 ====================

    @Nested
    class Validate方法 {

        @Test
        void 允许时不抛异常() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            assertThatCode(() -> control.validate(projectDir.resolve("file.txt").toString()))
                    .doesNotThrowAnyException();
        }

        @Test
        void 拒绝时抛SecurityException() {
            var control = createControl(
                    PathAccessControl.Mode.WHITELIST_ONLY,
                    List.of(projectDir),
                    List.of());

            assertThatThrownBy(() -> control.validate(sensitiveDir.resolve("file.txt").toString()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("不在白名单范围内");
        }

        @Test
        void null路径抛IllegalArgumentException() {
            var control = createControl(PathAccessControl.Mode.UNRESTRICTED, List.of(), List.of());

            assertThatThrownBy(() -> control.validate(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 空白路径抛IllegalArgumentException() {
            var control = createControl(PathAccessControl.Mode.UNRESTRICTED, List.of(), List.of());

            assertThatThrownBy(() -> control.validate("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== isAllowed(Path) 参数校验 ====================

    @Nested
    class 参数校验 {

        @Test
        void null_Path抛IllegalArgumentException() {
            var control = createControl(PathAccessControl.Mode.UNRESTRICTED, List.of(), List.of());

            assertThatThrownBy(() -> control.isAllowed((Path) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("路径不能为 null");
        }
    }

    // ==================== Mode 解析 ====================

    @Nested
    class Mode解析 {

        @Test
        void 支持连字符格式() {
            assertThat(PathAccessControl.Mode.fromString("whitelist-only"))
                    .isEqualTo(PathAccessControl.Mode.WHITELIST_ONLY);
            assertThat(PathAccessControl.Mode.fromString("blacklist-only"))
                    .isEqualTo(PathAccessControl.Mode.BLACKLIST_ONLY);
            assertThat(PathAccessControl.Mode.fromString("whitelist-plus-blacklist"))
                    .isEqualTo(PathAccessControl.Mode.WHITELIST_PLUS_BLACKLIST);
        }

        @Test
        void 不区分大小写() {
            assertThat(PathAccessControl.Mode.fromString("UNRESTRICTED"))
                    .isEqualTo(PathAccessControl.Mode.UNRESTRICTED);
            assertThat(PathAccessControl.Mode.fromString("Whitelist-Only"))
                    .isEqualTo(PathAccessControl.Mode.WHITELIST_ONLY);
        }

        @Test
        void null或空白返回UNRESTRICTED() {
            assertThat(PathAccessControl.Mode.fromString(null))
                    .isEqualTo(PathAccessControl.Mode.UNRESTRICTED);
            assertThat(PathAccessControl.Mode.fromString(""))
                    .isEqualTo(PathAccessControl.Mode.UNRESTRICTED);
            assertThat(PathAccessControl.Mode.fromString("  "))
                    .isEqualTo(PathAccessControl.Mode.UNRESTRICTED);
        }

        @Test
        void 无法识别的值返回UNRESTRICTED() {
            assertThat(PathAccessControl.Mode.fromString("invalid-mode"))
                    .isEqualTo(PathAccessControl.Mode.UNRESTRICTED);
        }
    }
}
