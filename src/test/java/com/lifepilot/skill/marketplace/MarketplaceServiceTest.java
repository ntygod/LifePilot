package com.lifepilot.skill.marketplace;

import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.install.SkillInstaller;
import com.lifepilot.skill.marketplace.model.InstallResult;
import com.lifepilot.skill.marketplace.model.InstalledSkill;
import com.lifepilot.skill.marketplace.model.SkillPackage;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * MarketplaceService 单元测试 — Mock IndexManager、SkillInstaller、VersionResolver。
 *
 * @author zsg
 * @since 2026-03-05
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceServiceTest {

    @Mock
    private IndexManager indexManager;

    @Mock
    private SkillInstaller skillInstaller;

    @Mock
    private VersionResolver versionResolver;

    @Mock
    private InstalledSkillRepository installedSkillRepository;

    private MarketplaceService service;

    @BeforeEach
    void setUp() {
        service = new MarketplaceService(indexManager, skillInstaller, versionResolver,
                installedSkillRepository);
    }

    // ── getSkills ──────────────────────────────────────────────

    @Nested
    class GetSkills {

        @Test
        void 无过滤条件_返回所有包并按下载量降序() {
            var pkg1 = testPackage("a", "Alpha 工具", "描述A", 100, List.of("tool"));
            var pkg2 = testPackage("b", "Beta 工具", "描述B", 200, List.of("ai"));
            var pkg3 = testPackage("c", "Gamma 工具", "描述C", 50, List.of("tool"));
            when(indexManager.getPackages()).thenReturn(List.of(pkg1, pkg2, pkg3));

            var result = service.getSkills(null, null, 0, 20);

            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(1);
            assertThat(result.content()).hasSize(3);
            // 按下载量降序：200, 100, 50
            assertThat(result.content().get(0).id()).isEqualTo("b");
            assertThat(result.content().get(1).id()).isEqualTo("a");
            assertThat(result.content().get(2).id()).isEqualTo("c");
        }

        @Test
        void 关键词搜索_匹配名称() {
            var pkg1 = testPackage("a", "Alpha 工具", "描述A", 100, List.of());
            var pkg2 = testPackage("b", "Beta 工具", "描述B", 200, List.of());
            when(indexManager.getPackages()).thenReturn(List.of(pkg1, pkg2));

            var result = service.getSkills("alpha", null, 0, 20);

            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.content().getFirst().id()).isEqualTo("a");
        }

        @Test
        void 关键词搜索_匹配描述_大小写不敏感() {
            var pkg1 = testPackage("a", "工具A", "这是一个 AI 助手", 100, List.of());
            var pkg2 = testPackage("b", "工具B", "普通工具", 200, List.of());
            when(indexManager.getPackages()).thenReturn(List.of(pkg1, pkg2));

            var result = service.getSkills("ai", null, 0, 20);

            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.content().getFirst().id()).isEqualTo("a");
        }

        @Test
        void 标签过滤() {
            var pkg1 = testPackage("a", "工具A", "描述A", 100, List.of("tool", "ai"));
            var pkg2 = testPackage("b", "工具B", "描述B", 200, List.of("productivity"));
            when(indexManager.getPackages()).thenReturn(List.of(pkg1, pkg2));

            var result = service.getSkills(null, "ai", 0, 20);

            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.content().getFirst().id()).isEqualTo("a");
        }

        @Test
        void 关键词加标签_同时过滤() {
            var pkg1 = testPackage("a", "Alpha 工具", "描述A", 100, List.of("tool"));
            var pkg2 = testPackage("b", "Alpha 助手", "描述B", 200, List.of("ai"));
            var pkg3 = testPackage("c", "Beta 工具", "描述C", 50, List.of("tool"));
            when(indexManager.getPackages()).thenReturn(List.of(pkg1, pkg2, pkg3));

            var result = service.getSkills("alpha", "tool", 0, 20);

            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.content().getFirst().id()).isEqualTo("a");
        }

        @Test
        void 分页_第一页() {
            var packages = List.of(
                    testPackage("a", "A", "d", 300, List.of()),
                    testPackage("b", "B", "d", 200, List.of()),
                    testPackage("c", "C", "d", 100, List.of())
            );
            when(indexManager.getPackages()).thenReturn(packages);

            var result = service.getSkills(null, null, 0, 2);

            assertThat(result.page()).isEqualTo(0);
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
            assertThat(result.content()).hasSize(2);
            assertThat(result.content().get(0).id()).isEqualTo("a");
            assertThat(result.content().get(1).id()).isEqualTo("b");
        }

        @Test
        void 分页_第二页() {
            var packages = List.of(
                    testPackage("a", "A", "d", 300, List.of()),
                    testPackage("b", "B", "d", 200, List.of()),
                    testPackage("c", "C", "d", 100, List.of())
            );
            when(indexManager.getPackages()).thenReturn(packages);

            var result = service.getSkills(null, null, 1, 2);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().id()).isEqualTo("c");
        }

        @Test
        void 空搜索关键词_视为无过滤() {
            var pkg = testPackage("a", "工具", "描述", 10, List.of());
            when(indexManager.getPackages()).thenReturn(List.of(pkg));

            var result = service.getSkills("  ", null, 0, 20);

            assertThat(result.totalElements()).isEqualTo(1);
        }
    }

    // ── getSkill ───────────────────────────────────────────────

    @Nested
    class GetSkill {

        @Test
        void 存在的包_返回Optional() {
            var pkg = testPackage("test-id", "测试", "描述", 10, List.of());
            when(indexManager.getPackage("test-id")).thenReturn(Optional.of(pkg));

            var result = service.getSkill("test-id");

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("test-id");
        }

        @Test
        void 不存在的包_返回empty() {
            when(indexManager.getPackage("unknown")).thenReturn(Optional.empty());

            var result = service.getSkill("unknown");

            assertThat(result).isEmpty();
        }
    }

    // ── install ────────────────────────────────────────────────

    @Nested
    class Install {

        @Test
        void 委托给SkillInstaller() {
            var expected = new InstallResult(true, "skill-1", null, null, false);
            when(skillInstaller.install("pkg-1", false)).thenReturn(expected);

            var result = service.install("pkg-1", false);

            assertThat(result).isEqualTo(expected);
            verify(skillInstaller).install("pkg-1", false);
        }

        @Test
        void 确认高风险安装() {
            var expected = new InstallResult(true, "skill-1", null, null, false);
            when(skillInstaller.install("pkg-1", true)).thenReturn(expected);

            var result = service.install("pkg-1", true);

            assertThat(result).isEqualTo(expected);
            verify(skillInstaller).install("pkg-1", true);
        }
    }

    // ── uninstall ──────────────────────────────────────────────

    @Nested
    class Uninstall {

        @Test
        void 委托给SkillInstaller() {
            var expected = new InstallResult(true, "skill-1", null, null, false);
            when(skillInstaller.uninstall("pkg-1")).thenReturn(expected);

            var result = service.uninstall("pkg-1");

            assertThat(result).isEqualTo(expected);
            verify(skillInstaller).uninstall("pkg-1");
        }
    }

    // ── upgrade ────────────────────────────────────────────────

    @Nested
    class Upgrade {

        @Test
        void 委托给SkillInstaller() {
            var expected = new InstallResult(true, "skill-1", null, null, false);
            when(skillInstaller.upgrade("pkg-1", false)).thenReturn(expected);

            var result = service.upgrade("pkg-1", false);

            assertThat(result).isEqualTo(expected);
            verify(skillInstaller).upgrade("pkg-1", false);
        }
    }

    // ── refreshIndex ───────────────────────────────────────────

    @Nested
    class RefreshIndex {

        @Test
        void 委托给IndexManager() {
            when(indexManager.refreshAll()).thenReturn(2);

            var result = service.refreshIndex();

            assertThat(result).isEqualTo(2);
            verify(indexManager).refreshAll();
        }
    }

    // ── getUpdates ─────────────────────────────────────────────

    @Nested
    class GetUpdates {

        @Test
        void 协调InstalledSkillRepository和VersionResolver() {
            var installed = List.of(
                    testInstalledSkill("pkg-1", "1.0.0"),
                    testInstalledSkill("pkg-2", "2.0.0")
            );
            var allPackages = List.of(
                    testPackage("pkg-1", "工具1", "描述", 10, List.of()),
                    testPackage("pkg-2", "工具2", "描述", 20, List.of())
            );
            var updatable = List.of(allPackages.getFirst());

            when(installedSkillRepository.findAll()).thenReturn(installed);
            when(indexManager.getPackages()).thenReturn(allPackages);
            when(versionResolver.findUpdates(installed, allPackages)).thenReturn(updatable);

            var result = service.getUpdates();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().id()).isEqualTo("pkg-1");
            verify(installedSkillRepository).findAll();
            verify(indexManager).getPackages();
            verify(versionResolver).findUpdates(installed, allPackages);
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────

    private static SkillPackage testPackage(String id, String name, String description,
                                            int downloads, List<String> tags) {
        return SkillPackage.builder()
                .id(id)
                .name(name)
                .description(description)
                .version("1.0.0")
                .author("test-author")
                .repoUrl("https://github.com/test/repo")
                .filePath("skills/" + id + ".yaml")
                .tags(tags)
                .downloads(downloads)
                .verified(false)
                .installed(false)
                .build();
    }

    private static InstalledSkill testInstalledSkill(String packageId, String version) {
        return new InstalledSkill(
                "id-" + packageId,
                packageId,
                "Skill " + packageId,
                version,
                "https://example.com/index.json",
                "https://github.com/test/repo",
                "skills/" + packageId + ".yaml",
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
