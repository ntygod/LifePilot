package com.lifepilot.skill.marketplace;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.install.SkillInstaller;
import com.lifepilot.skill.marketplace.model.*;
import com.lifepilot.skill.marketplace.security.SkillSecurityScanner;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.skill.yaml.YamlSchemaValidator;
import com.lifepilot.skill.yaml.YamlSkillLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Marketplace → SkillRegistry 跨模块集成测试。
 *
 * <p>验证安装后 Skill 注册到 SkillRegistry、卸载后从 SkillRegistry 注销。
 * 使用真实 SkillRegistry（内存）+ 真实 InstalledSkillRepository（内存 SQLite），
 * Mock HTTP 下载和索引管理。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class Marketplace_SkillRegistry_集成测试 {

    /** 示例 YAML 内容 — 合法的 Skill 定义。 */
    private static final String SAMPLE_YAML = """
            skill:
              id: test-marketplace-skill
              name: 测试市场 Skill
              description: 用于集成测试的市场 Skill
              version: "1.0.0"
              system-prompt: 你是一个测试助手
              allowed-tools:
                - todo-add
            """;

    @TempDir
    Path tempDir;

    // 真实组件
    private SkillRegistry skillRegistry;
    private InstalledSkillRepository installedSkillRepository;
    private VersionResolver versionResolver;

    // Mock 组件
    private IndexManager indexManager;
    private SkillSecurityScanner securityScanner;
    private YamlSchemaValidator schemaValidator;
    private YamlSkillLoader yamlSkillLoader;
    private RestClient restClient;

    // 被测对象
    private SkillInstaller installer;

    // 数据库资源
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        // 1. 内存 SQLite + 表结构
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS installed_skills (
                    id                  TEXT PRIMARY KEY,
                    package_id          TEXT NOT NULL UNIQUE,
                    name                TEXT NOT NULL,
                    version             TEXT NOT NULL,
                    index_source_url    TEXT NOT NULL,
                    repo_url            TEXT NOT NULL,
                    file_path           TEXT NOT NULL,
                    security_report_json TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        installedSkillRepository = new InstalledSkillRepository(jdbcTemplate);

        // 2. 真实 SkillRegistry（Mock 其依赖）
        var toolRegistry = mock(com.lifepilot.tool.registry.DynamicToolRegistry.class);
        // ToolContract 是 sealed interface，不能 mock，使用真实 BuiltinTool 实例
        var dummyTool = com.lifepilot.tool.BuiltinTool.builder()
                .id("todo-add").name("todo-add").description("测试工具")
                .executor(input -> null).build();
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.of(dummyTool));

        var skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(tempDir.toString());

        var validator = new SkillDefinitionValidator(toolRegistry, skillConfig);
        var searchIndex = mock(SkillSearchIndex.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        skillRegistry = new SkillRegistry(validator, searchIndex, eventPublisher, skillConfig);

        // 3. Mock 外部依赖
        indexManager = mock(IndexManager.class);
        securityScanner = mock(SkillSecurityScanner.class);
        schemaValidator = mock(YamlSchemaValidator.class);
        yamlSkillLoader = mock(YamlSkillLoader.class);
        restClient = mock(RestClient.class);
        versionResolver = new VersionResolver();

        // 4. 配置
        var marketplaceProperties = new MarketplaceProperties();
        marketplaceProperties.setIndexSources(List.of("https://example.com/index.json"));

        // 5. 构建 SkillInstaller
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        installer = new SkillInstaller(
                indexManager, versionResolver, securityScanner, schemaValidator,
                yamlSkillLoader, skillRegistry, installedSkillRepository,
                marketplaceProperties, skillConfig, builder
        );
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 安装后_Skill注册到SkillRegistry_可通过find查到() {
        // 准备：Mock 索引返回包元数据
        var skillPackage = samplePackage("test-pkg", "1.0.0");
        when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(skillPackage));

        // Mock HTTP 下载返回 YAML
        mockHttpDownload(SAMPLE_YAML);

        // Mock Schema 校验通过
        when(schemaValidator.validate(any())).thenReturn(
                new YamlSchemaValidator.ValidationResult(true, List.of()));

        // Mock 安全扫描返回 LOW 风险
        when(securityScanner.scan(any())).thenReturn(
                new SecurityReport(List.of(), RiskLevel.LOW));

        // Mock YamlSkillLoader 返回 SkillDefinition
        var definition = sampleDefinition("test-marketplace-skill");
        when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(definition));

        // 执行安装
        var result = installer.install("test-pkg", false);

        // 验证安装成功
        assertThat(result.success()).isTrue();
        assertThat(result.skillId()).isEqualTo("test-marketplace-skill");

        // 核心验证：SkillRegistry 中能找到已安装的 Skill
        var found = skillRegistry.find("test-marketplace-skill");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("test-marketplace-skill");
        assertThat(found.get().source()).isInstanceOf(SkillSource.Marketplace.class);

        // 验证 source 字段正确
        var marketplaceSource = (SkillSource.Marketplace) found.get().source();
        assertThat(marketplaceSource.packageId()).isEqualTo("test-pkg");
        assertThat(marketplaceSource.indexSourceUrl()).isEqualTo("https://example.com/index.json");

        // 验证 InstalledSkillRepository 中有记录
        var installed = installedSkillRepository.findByPackageId("test-pkg");
        assertThat(installed).isPresent();
        assertThat(installed.get().version()).isEqualTo("1.0.0");
    }

    @Test
    void 卸载后_Skill从SkillRegistry注销_find返回empty() {
        // 先安装
        var skillPackage = samplePackage("test-pkg-uninstall", "1.0.0");
        when(indexManager.getPackage("test-pkg-uninstall")).thenReturn(Optional.of(skillPackage));
        mockHttpDownload(SAMPLE_YAML);
        when(schemaValidator.validate(any())).thenReturn(
                new YamlSchemaValidator.ValidationResult(true, List.of()));
        when(securityScanner.scan(any())).thenReturn(
                new SecurityReport(List.of(), RiskLevel.LOW));

        var definition = sampleDefinition("test-marketplace-skill");
        when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(definition));

        var installResult = installer.install("test-pkg-uninstall", false);
        assertThat(installResult.success()).isTrue();

        // 确认已注册
        assertThat(skillRegistry.find("test-marketplace-skill")).isPresent();
        assertThat(installedSkillRepository.findByPackageId("test-pkg-uninstall")).isPresent();

        // 执行卸载
        var uninstallResult = installer.uninstall("test-pkg-uninstall");

        // 验证卸载成功
        assertThat(uninstallResult.success()).isTrue();

        // 核心验证：SkillRegistry 中已找不到
        assertThat(skillRegistry.find("test-marketplace-skill")).isEmpty();

        // 验证 InstalledSkillRepository 中记录已删除
        assertThat(installedSkillRepository.findByPackageId("test-pkg-uninstall")).isEmpty();
    }

    @Test
    void 安装_卸载_再安装_SkillRegistry状态正确() {
        var skillPackage = samplePackage("test-pkg-reinstall", "1.0.0");
        when(indexManager.getPackage("test-pkg-reinstall")).thenReturn(Optional.of(skillPackage));
        mockHttpDownload(SAMPLE_YAML);
        when(schemaValidator.validate(any())).thenReturn(
                new YamlSchemaValidator.ValidationResult(true, List.of()));
        when(securityScanner.scan(any())).thenReturn(
                new SecurityReport(List.of(), RiskLevel.LOW));

        var definition = sampleDefinition("test-marketplace-skill");
        when(yamlSkillLoader.loadFile(any(Path.class))).thenReturn(Optional.of(definition));

        // 第一次安装
        assertThat(installer.install("test-pkg-reinstall", false).success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isPresent();

        // 卸载
        assertThat(installer.uninstall("test-pkg-reinstall").success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isEmpty();

        // 重新安装
        assertThat(installer.install("test-pkg-reinstall", false).success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isPresent();
        assertThat(installedSkillRepository.findByPackageId("test-pkg-reinstall")).isPresent();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockHttpDownload(String content) {
        var requestHeadersUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var requestHeadersSpec = mock(RestClient.RequestHeadersSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(content);
    }

    private static SkillPackage samplePackage(String id, String version) {
        return SkillPackage.builder()
                .id(id)
                .name("测试 Skill")
                .description("用于集成测试的 Skill")
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

    private static SkillDefinition sampleDefinition(String skillId) {
        return SkillDefinition.builder()
                .id(skillId)
                .name("测试市场 Skill")
                .description("用于集成测试的市场 Skill")
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
}
