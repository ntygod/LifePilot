package com.lifepilot.skill.marketplace;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
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
 * Marketplace \u2192 SkillRegistry \u8DE8\u6A21\u5757\u96C6\u6210\u6D4B\u8BD5\u3002
 *
 * <p>\u9A8C\u8BC1\u5B89\u88C5\u540E Skill \u6CE8\u518C\u5230 SkillRegistry\u3001\u5378\u8F7D\u540E\u4ECE SkillRegistry \u6CE8\u9500\u3002
 * \u4F7F\u7528\u771F\u5B9E SkillRegistry\uFF08\u5185\u5B58\uFF09+ \u771F\u5B9E InstalledSkillRepository\uFF08\u5185\u5B58 SQLite\uFF09\uFF0C
 * Mock HTTP \u4E0B\u8F7D\u548C\u7D22\u5F15\u7BA1\u7406\u3002</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class Marketplace_SkillRegistry_\u96C6\u6210\u6D4B\u8BD5 {

    private static final String SAMPLE_MARKDOWN = """
            ---
            id: test-marketplace-skill
            name: "\u6D4B\u8BD5\u5E02\u573A Skill"
            description: "\u7528\u4E8E\u96C6\u6210\u6D4B\u8BD5\u7684\u5E02\u573A Skill"
            version: "1.0.0"
            allowed-tools:
              - todo-add
            ---

            \u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B
            """;

    @TempDir
    Path tempDir;

    private SkillRegistry skillRegistry;
    private InstalledSkillRepository installedSkillRepository;
    private VersionResolver versionResolver;

    private IndexManager indexManager;
    private SkillSecurityScanner securityScanner;
    private MarkdownSkillParser markdownParser;
    private MarkdownSkillLoader markdownSkillLoader;
    private RestClient restClient;

    private SkillInstaller installer;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        // 1. \u5185\u5B58 SQLite + \u8868\u7ED3\u6784
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

        // 2. \u771F\u5B9E SkillRegistry
        var toolRegistry = mock(com.lifepilot.tool.registry.DynamicToolRegistry.class);
        var dummyTool = com.lifepilot.tool.BuiltinTool.builder()
                .id("todo-add").name("todo-add").description("\u6D4B\u8BD5\u5DE5\u5177")
                .executor(input -> null).build();
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.of(dummyTool));

        var skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(tempDir.toString());

        var validator = new SkillDefinitionValidator(toolRegistry, skillConfig);
        var searchIndex = mock(SkillSearchIndex.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        skillRegistry = new SkillRegistry(validator, searchIndex, eventPublisher, skillConfig);

        // 3. Mock \u5916\u90E8\u4F9D\u8D56
        indexManager = mock(IndexManager.class);
        securityScanner = mock(SkillSecurityScanner.class);
        markdownParser = mock(MarkdownSkillParser.class);
        markdownSkillLoader = mock(MarkdownSkillLoader.class);
        restClient = mock(RestClient.class);
        versionResolver = new VersionResolver();

        // 4. \u914D\u7F6E
        var marketplaceProperties = new MarketplaceProperties();
        marketplaceProperties.setIndexSources(List.of("https://example.com/index.json"));

        // 5. \u6784\u5EFA SkillInstaller
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        installer = new SkillInstaller(
                indexManager, versionResolver, securityScanner, markdownParser,
                markdownSkillLoader, skillRegistry, installedSkillRepository,
                marketplaceProperties, skillConfig, builder
        );
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 安装后_Skill注册到SkillRegistry_可通过find查到() {
        var skillPackage = samplePackage("test-pkg", "1.0.0");
        when(indexManager.getPackage("test-pkg")).thenReturn(Optional.of(skillPackage));
        mockHttpDownload(SAMPLE_MARKDOWN);

        var parseResult = new MarkdownSkillParser.ParseResult(
                true, sampleDefinition("test-marketplace-skill"), List.of(),
                Map.of("id", "test-marketplace-skill"));
        when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(parseResult);
        when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));

        var definition = sampleDefinition("test-marketplace-skill");
        when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(definition));

        var result = installer.install("test-pkg", false);

        assertThat(result.success()).isTrue();
        assertThat(result.skillId()).isEqualTo("test-marketplace-skill");

        var found = skillRegistry.find("test-marketplace-skill");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("test-marketplace-skill");
        assertThat(found.get().source()).isInstanceOf(SkillSource.Marketplace.class);

        var marketplaceSource = (SkillSource.Marketplace) found.get().source();
        assertThat(marketplaceSource.packageId()).isEqualTo("test-pkg");
        assertThat(marketplaceSource.indexSourceUrl()).isEqualTo("https://example.com/index.json");

        var installed = installedSkillRepository.findByPackageId("test-pkg");
        assertThat(installed).isPresent();
        assertThat(installed.get().version()).isEqualTo("1.0.0");
    }

    @Test
    void 卸载后_Skill从SkillRegistry注销_find返回empty() {
        // \u5148\u5B89\u88C5
        var skillPackage = samplePackage("test-pkg-uninstall", "1.0.0");
        when(indexManager.getPackage("test-pkg-uninstall")).thenReturn(Optional.of(skillPackage));
        mockHttpDownload(SAMPLE_MARKDOWN);

        var parseResult = new MarkdownSkillParser.ParseResult(
                true, sampleDefinition("test-marketplace-skill"), List.of(),
                Map.of("id", "test-marketplace-skill"));
        when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(parseResult);
        when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));

        var definition = sampleDefinition("test-marketplace-skill");
        when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(definition));

        var installResult = installer.install("test-pkg-uninstall", false);
        assertThat(installResult.success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isPresent();
        assertThat(installedSkillRepository.findByPackageId("test-pkg-uninstall")).isPresent();

        var uninstallResult = installer.uninstall("test-pkg-uninstall");

        assertThat(uninstallResult.success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isEmpty();
        assertThat(installedSkillRepository.findByPackageId("test-pkg-uninstall")).isEmpty();
    }

    @Test
    void 安装_卸载_再安装_SkillRegistry状态正确() {
        var skillPackage = samplePackage("test-pkg-reinstall", "1.0.0");
        when(indexManager.getPackage("test-pkg-reinstall")).thenReturn(Optional.of(skillPackage));
        mockHttpDownload(SAMPLE_MARKDOWN);

        var parseResult = new MarkdownSkillParser.ParseResult(
                true, sampleDefinition("test-marketplace-skill"), List.of(),
                Map.of("id", "test-marketplace-skill"));
        when(markdownParser.parse(SAMPLE_MARKDOWN)).thenReturn(parseResult);
        when(securityScanner.scan(any())).thenReturn(new SecurityReport(List.of(), RiskLevel.LOW));

        var definition = sampleDefinition("test-marketplace-skill");
        when(markdownSkillLoader.loadFolder(any(Path.class))).thenReturn(Optional.of(definition));

        assertThat(installer.install("test-pkg-reinstall", false).success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isPresent();

        assertThat(installer.uninstall("test-pkg-reinstall").success()).isTrue();
        assertThat(skillRegistry.find("test-marketplace-skill")).isEmpty();

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
                .name("\u6D4B\u8BD5 Skill")
                .description("\u7528\u4E8E\u96C6\u6210\u6D4B\u8BD5\u7684 Skill")
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

    private static SkillDefinition sampleDefinition(String skillId) {
        return SkillDefinition.builder()
                .id(skillId)
                .name("\u6D4B\u8BD5\u5E02\u573A Skill")
                .description("\u7528\u4E8E\u96C6\u6210\u6D4B\u8BD5\u7684\u5E02\u573A Skill")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/tmp/test-skills"))
                .systemPrompt("\u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B")
                .allowedTools(List.of("todo-add"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }
}
