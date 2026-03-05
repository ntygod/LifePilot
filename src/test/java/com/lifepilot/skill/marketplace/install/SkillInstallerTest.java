package com.lifepilot.skill.marketplace.install;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.model.*;
import com.lifepilot.skill.marketplace.security.SkillSecurityScanner;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.model.ExecutionStrategy;
import com.lifepilot.skill.model.MemoryAccessPolicy;
import com.lifepilot.skill.model.SkillBudget;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.yaml.YamlSchemaValidator;
import com.lifepilot.skill.yaml.YamlSkillLoader;
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
 * SkillInstaller 单元测试 — Mock HTTP 下载、SkillRegistry、YamlSkillLoader。
 *
 * @author zsg
 * @since 2026-03-05
 */
@ExtendWith(MockitoExtension.class)
class SkillInstallerTest {

    @Mock
    private IndexManager indexManager;

    @Mock
    private SkillSecurityScanner securityScanner;

    @Mock
    private YamlSchemaValidator schemaValidator;

    @Mock
    private YamlSkillLoader yamlSkillLoader;

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private InstalledSkillRepository installedSkillRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @TempDir
    Path tempDir;

    private MarketplaceProperties marketplaceProperties;
    private VersionResolver versionResolver;
    private SkillInstaller installer;

    /** 示例 YAML 内容。 */
    private static final String SAMPLE_YAML = """
            skill:
              id: test-skill
              name: 测试 Skill
              description: 用于测试的 Skill
              version: "1.0.0"
              system-prompt: 你是一个测试助手
              allowed-tools:
                - todo-add
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
                indexManager, versionResolver, securityScanner, schemaValidator,
                yamlSkillLoader, skillRegistry, installedSkillRepository,
                marketplaceProperties, skillConfigProperties, builder
        );
    }

    // ── install ─────────────────────────────────────────────

    @Nested
    class Install {

        @Test
        void 安装成功_完整流程() {
            // 准备
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(true);

            // 执行
            var result = installer.install("test-pkg", false);

            // 验证
            assertThat(result.success()).isTrue();
            assertThat(result.skillId()).isEqualTo("test-skill");
            assertThat(result.requiresConfirmation()).isFalse();
            verify(installedSkillRepository).save(any(InstalledSkill.class));
            // 验证 YAML 文件已写入
            assertThat(Files.exists(tempDir.resolve("test-pkg.yaml"))).isTrue();
        }

        @Test
        void 包不存在_返回失败() {
            when(indexManager.getPackage("nonexistent")).thenReturn(Optional.empty());

            var result = installer.install("nonexistent", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("未找到包");
        }

        @Test
        void 版本不兼容_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0").toBuilder()
                    .minLifepilotVersion("99.0.0")
                    .build();
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("版本不兼容");
        }

        @Test
        void HTTP下载失败_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownloadFailure();

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("下载失败");
        }

        @Test
        void Schema校验失败_返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(
                    new YamlSchemaValidator.ValidationResult(false, List.of("缺少必填字段: id")));

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("Schema 校验失败");
        }

        @Test
        void HIGH风险_未确认_返回需要确认() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "危险工具", "包含 shell 执行工具")),
                    RiskLevel.HIGH
            );
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
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "危险工具", "包含 shell 执行工具")),
                    RiskLevel.HIGH
            );
            when(securityScanner.scan(any())).thenReturn(highRiskReport);
            when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
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
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            var highRiskReport = new SecurityReport(
                    List.of(new SecurityFinding(RiskLevel.HIGH, "危险工具", "包含 shell 执行工具")),
                    RiskLevel.HIGH
            );
            when(securityScanner.scan(any())).thenReturn(highRiskReport);

            var result = installer.install("test-pkg", true);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("安全策略禁止");
        }

        @Test
        void SkillRegistry注册失败_清理文件并返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(false);

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("注册被拒绝");
            // 文件应被清理
            assertThat(Files.exists(tempDir.resolve("test-pkg.yaml"))).isFalse();
        }

        @Test
        void YamlSkillLoader加载失败_清理文件并返回失败() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.empty());

            var result = installer.install("test-pkg", false);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("加载失败");
            assertThat(Files.exists(tempDir.resolve("test-pkg.yaml"))).isFalse();
        }

        @Test
        void 安装成功后_source替换为Marketplace() {
            var pkg = samplePackage("test-pkg", "1.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(sampleDefinition()));
            when(skillRegistry.register(any())).thenReturn(true);

            installer.install("test-pkg", false);

            // 验证注册时 source 已替换为 Marketplace
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
            // 准备：写入 YAML 文件模拟已安装状态
            Path yamlFile = tempDir.resolve("test-pkg.yaml");
            Files.writeString(yamlFile, SAMPLE_YAML);

            var installed = sampleInstalledSkill("test-pkg", "1.0.0");
            when(installedSkillRepository.findByPackageId("test-pkg")).thenReturn(Optional.of(installed));
            when(yamlSkillLoader.loadFile(yamlFile)).thenReturn(Optional.of(sampleDefinition()));

            // 执行
            var result = installer.uninstall("test-pkg");

            // 验证
            assertThat(result.success()).isTrue();
            verify(skillRegistry).unregister("test-skill");
            verify(installedSkillRepository).deleteByPackageId("test-pkg");
            assertThat(Files.exists(yamlFile)).isFalse();
        }

        @Test
        void 未安装_返回失败() {
            when(installedSkillRepository.findByPackageId("nonexistent")).thenReturn(Optional.empty());

            var result = installer.uninstall("nonexistent");

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains("未找到已安装");
        }

        @Test
        void YAML文件不存在_仍然清理记录() {
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
            // 准备卸载
            Path yamlFile = tempDir.resolve("test-pkg.yaml");
            Files.writeString(yamlFile, SAMPLE_YAML);
            var installed = sampleInstalledSkill("test-pkg", "1.0.0");
            when(installedSkillRepository.findByPackageId("test-pkg"))
                    .thenReturn(Optional.of(installed))   // upgrade 检查
                    .thenReturn(Optional.of(installed))   // uninstall 查找
                    .thenReturn(Optional.empty());        // install 后不再有旧记录
            when(yamlSkillLoader.loadFile(any(Path.class)))
                    .thenReturn(Optional.of(sampleDefinition()));

            // 准备安装
            var pkg = samplePackage("test-pkg", "2.0.0");
            when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(pkg));
            mockHttpDownload(SAMPLE_YAML);
            when(schemaValidator.validate(any())).thenReturn(new YamlSchemaValidator.ValidationResult(true, List.of()));
            when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));
            when(skillRegistry.register(any())).thenReturn(true);

            // 执行
            var result = installer.upgrade("test-pkg", false);

            // 验证
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
            assertThat(result.errorMessage()).contains("未找到已安装");
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
        when(headerSpec.retrieve()).thenThrow(new ResourceAccessException("连接超时"));
    }

    private static SkillPackage samplePackage(String id, String version) {
        return SkillPackage.builder()
                .id(id)
                .name("测试 Skill")
                .description("用于测试的 Skill")
                .version(version)
                .author("test-author")
                .repoUrl("https://raw.githubusercontent.com/test/repo/main")
                .filePath("skills/test.yaml")
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
                .name("测试 Skill")
                .description("用于测试的 Skill")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/tmp/test.yaml"))
                .systemPrompt("你是一个测试助手")
                .allowedTools(List.of("todo-add"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }

    private static InstalledSkill sampleInstalledSkill(String packageId, String version) {
        return new InstalledSkill(
                "id-" + packageId,
                packageId,
                "测试 Skill",
                version,
                "https://example.com/index.json",
                "https://raw.githubusercontent.com/test/repo/main",
                "skills/test.yaml",
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
