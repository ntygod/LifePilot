package com.lifepilot.skill.marketplace.install;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillParser.ParseResult;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.model.*;
import com.lifepilot.skill.marketplace.security.SkillSecurityScanner;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SkillInstaller 单元测试 — Mock HTTP 下载、SkillRegistry、MarkdownSkillLoader。
 *
 * @author zsg
 * @since 2026-03-05
 */
@ExtendWith(MockitoExtension.class)
class SkillInstallerTest {

    @Mock private IndexManager indexManager;
    @Mock private SkillSecurityScanner securityScanner;
    @Mock private MarkdownSkillParser markdownParser;
    @Mock private MarkdownSkillLoader markdownSkillLoader;
    @Mock private SkillRegistry skillRegistry;
    @Mock private InstalledSkillRepository installedSkillRepository;
    @Mock private RestClient restClient;
    @Mock private RestClient.ResponseSpec responseSpec;

    @TempDir
    Path tempDir;

    private MarketplaceProperties marketplaceProperties;
    private VersionResolver versionResolver;
    private SkillInstaller installer;

    private static final String SAMPLE_MARKDOWN = """
            ---
            id: test-skill
            name: "\u6D4B\u8BD5 Skill"
            description: "\u7528\u4E8E\u6D4B\u8BD5\u7684 Skill"
            version: "1.0.0"
            allowed-tools:
              - todo-add
            ---

            \u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B
            """;

    @BeforeEach
    void setUp() {
        marketplaceProperties = new MarketplaceProperties();
        marketplaceProperties.setIndexSources(List.of("https://example.com/index.json"));
        versionResolver = new VersionResolver();

        var skillConfigProperties = new SkillConfigProperties();
        skillConfigProperties.setDirectory(tempDir.toString());

        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        installer = new SkillInstaller(
                indexManager, versionResolver, securityScanner, markdownParser,
                markdownSkillLoader, skillRegistry, installedSkillRepository,
                marketplaceProperties, skillConfigProperties, builder
        );
    }

    private ParseResult successResult() {
        return new ParseResult(true, sampleDefinition(), List.of(), Map.of("id", "test-skill"));
    }

    private ParseResult failureResult(String error) {
        return new ParseResult(false, null, List.of(error), null);
    }

    // ── install ─────────────────────────────────────────────

    @Nested
    class Install {

        @Test
        void 安装成功_完整流程() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(true);

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isTrue();
            assertThat(result.skillId()).isEqualTo("test-skill");
            assertThat(result.requiresConfirmation()).isFalse();
            verify(installedSkillRepository).save(any(InstalledSkill.class));
            assertThat(Files.exists(tempDir.resolve("test-pkg").resolve("SKILL.md"))).isTrue();
        }

        @Test
        void 包不存在_返回失败() {
            when(indexManager.getPackage("nonexistent")).thenReturn(Optional.empty());

            var result = installer.install("nonexistent", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u672A\u627E\u5230\u5305");
        }

        @Test
        void 版本不兼容_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0").toBuilder()
                    .minLifepilotVersion("99.0.0")
                    .build();
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u7248\u672C\u4E0D\u517C\u5BB9");
        }

        @Test
        void HTTP下载失败_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownloadFailure();

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u4E0B\u8F7D\u5931\u8D25");
        }

        @Test
        void SKILL_MD解析校验失败_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(failureResult("\u7F3A\u5C11\u5FC5\u586B\u5B57\u6BB5: id"));

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u89E3\u6790\u6821\u9A8C\u5931\u8D25");
        }

        @Test
        void HIGH风险_未确认_返回需要确认() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "\u5371\u9669\u5DE5\u5177", "\u5305\u542B shell \u6267\u884C\u5DE5\u5177")),
                    RiskLevel.HIGH);
            when(securityScanner.scan(any())).thenReturn(highRiskReport);

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.requiresConfirmation()).isTrue();
            assertThat(result.securityReport()).isEqualTo(highRiskReport);
        }

        @Test
        void HIGH风险_已确认_继续安装() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "\u5371\u9669\u5DE5\u5177", "\u5305\u542B shell \u6267\u884C\u5DE5\u5177")),
                    RiskLevel.HIGH);
            when(securityScanner.scan(any())).thenReturn(highRiskReport);
            when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(true);

            var result = installer.install("test-pkg", true);

            assertThat(result.success()).isTrue();
            assertThat(result.securityReport().overallRisk()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        void HIGH风险_配置阻止_返回失败() {
            marketplaceProperties.getSecurity().setBlockHighRisk(true);
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "\u5371\u9669\u5DE5\u5177", "\u5305\u542B shell \u6267\u884C\u5DE5\u5177")),
                    RiskLevel.HIGH);
            when(securityScanner.scan(any())).thenReturn(highRiskReport);

            var result = installer.install("test-pkg", true);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u5B89\u5168\u7B56\u7565\u7981\u6B62");
        }

        @Test
        void SkillRegistry注册失败_清理文件夹并返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(false);

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u6CE8\u518C\u88AB\u62D2\u7EDD");
            assertThat(Files.exists(tempDir.resolve("test-pkg"))).isFalse();
        }

        @Test
        void MarkdownSkillLoader加载失败_清理文件夹并返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.empty());

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u52A0\u8F7D\u5931\u8D25");
            assertThat(Files.exists(tempDir.resolve("test-pkg"))).isFalse();
        }

        @Test
        void 安装成功后_source替换为Marketplace() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(true);

            installer.install("test-pkg", false);

            verify(skillRegistry).register(argThat(def ->
                    def.source() instanceof SkillSource.Marketplace marketplace
                            && "test-pkg".equals(marketplace.packageId())
            ));
        }
    }

    // ── uninstall ─────────────────────────────────────────────

    @Nested
    class Uninstall {

        @Test
        void 卸载成功_完整流程() throws IOException {
            Path skillFolder = tempDir.resolve("test-pkg");
            Files.createDirectories(skillFolder);
            Files.writeString(skillFolder.resolve("SKILL.md"), SAMPLE_MARKDOWN);

            var installed = sampleInstalledSkill("test-pkg", "1.0.0");
            when(installedSkillRepository.findByPackageId("test-pkg")).thenReturn(Optional.of(installed));
            when(markdownSkillLoader.loadFolder(skillFolder)).thenReturn(Optional.of(sampleDefinition()));

            var result = installer.uninstall("test-pkg");

            assertThat(result.success()).isTrue();
            verify(skillRegistry).unregister("test-skill");
            verify(installedSkillRepository).deleteByPackageId("test-pkg");
            assertThat(Files.exists(skillFolder)).isFalse();
        }

        @Test
        void 未安装_返回失败() {
            when(installedSkillRepository.findByPackageId("nonexistent")).thenReturn(Optional.empty());

            var result = installer.uninstall("nonexistent");

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u672A\u627E\u5230\u5DF2\u5B89\u88C5");
        }

        @Test
        void Skill文件夹不存在_仍然清理记录() {
            var installed = sampleInstalledSkill("test-pkg", "1.0.0");
            when(installedSkillRepository.findByPackageId("test-pkg")).thenReturn(Optional.of(installed));

            var result = installer.uninstall("test-pkg");

            assertThat(result.success()).isTrue();
            verify(installedSkillRepository).deleteByPackageId("test-pkg");
        }
    }

    // ── upgrade ─────────────────────────────────────────────

    @Nested
    class Upgrade {

        @Test
        void 升级成功_先卸载后安装() throws IOException {
            Path skillFolder = tempDir.resolve("test-pkg");
            Files.createDirectories(skillFolder);
            Files.writeString(skillFolder.resolve("SKILL.md"), SAMPLE_MARKDOWN);
            var installed = sampleInstalledSkill("test-pkg", "1.0.0");
            when(installedSkillRepository.findByPackageId("test-pkg"))
                    .thenReturn(Optional.of(installed))
                    .thenReturn(Optional.of(installed))
                    .thenReturn(Optional.empty());
            when(markdownSkillLoader.loadFolder(any(Path.class)))
                    .thenReturn(Optional.of(sampleDefinition()));

            var pkg = samplePackage("test-pkg", "2.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_MARKDOWN);
            when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(successResult());
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(skillRegistry.register(any())).thenReturn(true);

            var result = installer.upgrade("test-pkg", false);

            assertThat(result.success()).isTrue();
            verify(skillRegistry).unregister("test-skill");
            verify(installedSkillRepository).deleteByPackageId("test-pkg");
            verify(skillRegistry).register(any());
            verify(installedSkillRepository).save(any(InstalledSkill.class));
        }

        @Test
        void 未安装_升级失败() {
            when(installedSkillRepository.findByPackageId("nonexistent")).thenReturn(Optional.empty());

            var result = installer.upgrade("nonexistent", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("\u672A\u627E\u5230\u5DF2\u5B89\u88C5");
        }
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockHttpDownload(String responseBody) {
        var headerUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var headerSpec = mock(RestClient.RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(headerUriSpec);
        when(headerUriSpec.uri(anyString())).thenReturn(headerSpec);
        when(headerSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseBody);
    }

    @SuppressWarnings("unchecked")
    private void mockHttpDownloadFailure() {
        var headerUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var headerSpec = mock(RestClient.RequestHeadersSpec.class);
        when(restClient.get()).thenReturn(headerUriSpec);
        when(headerUriSpec.uri(anyString())).thenReturn(headerSpec);
        when(headerSpec.retrieve()).thenThrow(new ResourceAccessException("\u8FDE\u63A5\u8D85\u65F6"));
    }

    private static SkillPackage samplePackage(String id, String version) {
        return SkillPackage.builder()
                .id(id)
                .name("\u6D4B\u8BD5 Skill")
                .description("\u7528\u4E8E\u6D4B\u8BD5\u7684 Skill")
                .version(version)
                .author("test-author")
                .repoUrl("https://raw.githubusercontent.com/test/repo/main")
                .filePath("skills/test-skill/SKILL.md")
                .tags(List.of("test"))
                .minLifepilotVersion("0.1.0")
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .downloads(100)
                .verified(true)
                .build();
    }

    private static SkillDefinition sampleDefinition() {
        return SkillDefinition.builder()
                .id("test-skill")
                .name("\u6D4B\u8BD5 Skill")
                .description("\u7528\u4E8E\u6D4B\u8BD5\u7684 Skill")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/tmp/test-skills"))
                .instructions("\u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B")
                .suggestedTools(List.of("todo-add"))
                .metadata(Map.of())
                .build();
    }

    private static InstalledSkill sampleInstalledSkill(String packageId, String version) {
        return new InstalledSkill(
                "id-" + packageId,
                packageId,
                "\u6D4B\u8BD5 Skill",
                version,
                "https://example.com/index.json",
                "https://raw.githubusercontent.com/test/repo/main",
                "skills/test-skill/SKILL.md",
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
