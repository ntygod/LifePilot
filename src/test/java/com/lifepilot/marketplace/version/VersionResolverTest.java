package com.lifepilot.marketplace.version;

import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstalledExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VersionResolver 单元测试 — 覆盖 SemVer 比较、兼容性检查和升级检测（ExtensionPackage / InstalledExtension）。
 *
 * @author zsg
 * @since 2026-03-08
 */
class VersionResolverTest {

    private VersionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VersionResolver();
    }

    // ── compareSemVer ──────────────────────────────────────────

    @Nested
    class CompareSemVer {

        @Test
        void 相同版本_返回0() {
            assertThat(resolver.compareSemVer("1.2.3", "1.2.3")).isEqualTo(0);
        }

        @Test
        void MAJOR更大_返回1() {
            assertThat(resolver.compareSemVer("2.0.0", "1.9.9")).isEqualTo(1);
        }

        @Test
        void MAJOR更小_返回负1() {
            assertThat(resolver.compareSemVer("1.9.9", "2.0.0")).isEqualTo(-1);
        }

        @Test
        void MINOR更大_返回1() {
            assertThat(resolver.compareSemVer("1.3.0", "1.2.9")).isEqualTo(1);
        }

        @Test
        void PATCH更大_返回1() {
            assertThat(resolver.compareSemVer("1.2.4", "1.2.3")).isEqualTo(1);
        }

        @Test
        void v前缀_自动去除() {
            assertThat(resolver.compareSemVer("v1.2.3", "1.2.3")).isEqualTo(0);
            assertThat(resolver.compareSemVer("1.2.3", "v1.2.3")).isEqualTo(0);
        }

        @Test
        void 大写V前缀_自动去除() {
            assertThat(resolver.compareSemVer("V1.2.3", "1.2.3")).isEqualTo(0);
        }

        @Test
        void SNAPSHOT后缀_自动忽略() {
            assertThat(resolver.compareSemVer("0.1.0-SNAPSHOT", "0.1.0")).isEqualTo(0);
            assertThat(resolver.compareSemVer("1.0.0-SNAPSHOT", "0.9.0")).isEqualTo(1);
        }

        @Test
        void null版本号_抛出异常() {
            assertThatThrownBy(() -> resolver.compareSemVer(null, "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 空字符串_抛出异常() {
            assertThatThrownBy(() -> resolver.compareSemVer("", "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 格式不合法_段数不足_抛出异常() {
            assertThatThrownBy(() -> resolver.compareSemVer("1.2", "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 格式不合法_非数字_抛出异常() {
            assertThatThrownBy(() -> resolver.compareSemVer("1.2.abc", "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── checkCompatibility ─────────────────────────────────────

    @Nested
    class CheckCompatibility {

        @Test
        void 当前版本等于最低版本_兼容() {
            assertThat(resolver.checkCompatibility("1.0.0", "1.0.0")).isTrue();
        }

        @Test
        void 当前版本高于最低版本_兼容() {
            assertThat(resolver.checkCompatibility("1.2.0", "1.0.0")).isTrue();
        }

        @Test
        void 当前版本低于最低版本_不兼容() {
            assertThat(resolver.checkCompatibility("0.9.0", "1.0.0")).isFalse();
        }

        @Test
        void minVersion为null_视为兼容() {
            assertThat(resolver.checkCompatibility("1.0.0", null)).isTrue();
        }

        @Test
        void minVersion为空字符串_视为兼容() {
            assertThat(resolver.checkCompatibility("1.0.0", "")).isTrue();
            assertThat(resolver.checkCompatibility("1.0.0", "  ")).isTrue();
        }

        @Test
        void minLifepilotVersion检查_ExtensionPackage场景() {
            // 模拟 ExtensionPackage 的 minLifepilotVersion 字段检查
            var pkg = testExtensionPackage("pkg-1", "1.0.0", "0.5.0");
            assertThat(resolver.checkCompatibility("1.0.0", pkg.minLifepilotVersion())).isTrue();

            var incompatiblePkg = testExtensionPackage("pkg-2", "1.0.0", "2.0.0");
            assertThat(resolver.checkCompatibility("1.0.0", incompatiblePkg.minLifepilotVersion())).isFalse();
        }
    }

    // ── findUpdates ────────────────────────────────────────────

    @Nested
    class FindUpdates {

        @Test
        void 远程版本更高_返回可更新包() {
            var installed = List.of(installedExtension("pkg-1", "1.0.0", ExtensionType.SKILL));
            var remote = List.of(testExtensionPackage("pkg-1", "1.1.0", null));

            var updates = resolver.findUpdates(installed, remote);

            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst().id()).isEqualTo("pkg-1");
            assertThat(updates.getFirst().version()).isEqualTo("1.1.0");
        }

        @Test
        void 远程版本相同_不返回() {
            var installed = List.of(installedExtension("pkg-1", "1.0.0", ExtensionType.AGENT));
            var remote = List.of(testExtensionPackage("pkg-1", "1.0.0", null));

            assertThat(resolver.findUpdates(installed, remote)).isEmpty();
        }

        @Test
        void 远程版本更低_不返回() {
            var installed = List.of(installedExtension("pkg-1", "2.0.0", ExtensionType.WORKFLOW));
            var remote = List.of(testExtensionPackage("pkg-1", "1.0.0", null));

            assertThat(resolver.findUpdates(installed, remote)).isEmpty();
        }

        @Test
        void 远程无对应包_不返回() {
            var installed = List.of(installedExtension("pkg-1", "1.0.0", ExtensionType.SKILL));
            var remote = List.of(testExtensionPackage("pkg-other", "2.0.0", null));

            assertThat(resolver.findUpdates(installed, remote)).isEmpty();
        }

        @Test
        void 多个已安装_部分有更新_混合类型() {
            var installed = List.of(
                    installedExtension("pkg-1", "1.0.0", ExtensionType.SKILL),
                    installedExtension("pkg-2", "2.0.0", ExtensionType.AGENT),
                    installedExtension("pkg-3", "1.0.0", ExtensionType.WORKFLOW)
            );
            var remote = List.of(
                    testExtensionPackage("pkg-1", "1.1.0", null),  // 有更新
                    testExtensionPackage("pkg-2", "2.0.0", null),  // 无更新
                    testExtensionPackage("pkg-3", "0.9.0", null)   // 远程更低
            );

            var updates = resolver.findUpdates(installed, remote);

            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst().id()).isEqualTo("pkg-1");
        }

        @Test
        void 空列表_返回空() {
            assertThat(resolver.findUpdates(List.of(), List.of())).isEmpty();
            assertThat(resolver.findUpdates(List.of(),
                    List.of(testExtensionPackage("pkg-1", "1.0.0", null)))).isEmpty();
            assertThat(resolver.findUpdates(
                    List.of(installedExtension("pkg-1", "1.0.0", ExtensionType.SKILL)),
                    List.of())).isEmpty();
        }

        @Test
        void 远程重复id_保留版本更高的() {
            var installed = List.of(installedExtension("pkg-1", "1.0.0", ExtensionType.SKILL));
            var remote = List.of(
                    testExtensionPackage("pkg-1", "1.1.0", null),
                    testExtensionPackage("pkg-1", "1.5.0", null)
            );

            var updates = resolver.findUpdates(installed, remote);

            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst().version()).isEqualTo("1.5.0");
        }

        @Test
        void 版本格式不合法_跳过该条目() {
            var installed = List.of(installedExtension("pkg-1", "bad-version", ExtensionType.SKILL));
            var remote = List.of(testExtensionPackage("pkg-1", "1.0.0", null));

            // 不抛异常，静默跳过
            assertThat(resolver.findUpdates(installed, remote)).isEmpty();
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    private static InstalledExtension installedExtension(String packageId, String version, ExtensionType type) {
        return new InstalledExtension(
                "id-" + packageId,
                packageId,
                type,
                "Extension " + packageId,
                version,
                "https://example.com/index.json",
                "https://github.com/example/" + packageId,
                "extensions/" + packageId,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    private static ExtensionPackage testExtensionPackage(String id, String version, String minLifepilotVersion) {
        return ExtensionPackage.builder()
                .id(id)
                .name("Extension " + id)
                .type(ExtensionType.SKILL)
                .description("描述")
                .version(version)
                .author("author")
                .repoUrl("https://github.com/example/" + id)
                .filePath("extensions/" + id)
                .tags(List.of())
                .requirements(List.of())
                .minLifepilotVersion(minLifepilotVersion != null ? minLifepilotVersion : "0.1.0")
                .createdAt("2026-03-08T00:00:00Z")
                .updatedAt("2026-03-08T00:00:00Z")
                .downloads(0)
                .verified(false)
                .installed(false)
                .build();
    }
}
