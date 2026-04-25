package com.lifepilot.integration;

import com.lifepilot.LifePilotApplication;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.tool.SkillLoadToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skill 系统重构 E2E 集成测试。
 *
 * <p>启动最小 Spring 上下文（tool / memory / knowledge / datastore / skills），
 * 在每个用例前手动触发 classpath 下全部 BUILTIN SKILL.md 的安装 + 注册到 SkillRegistry
 * —— 与 {@code SkillDiscoveryRegistrar} 等价的启动期逻辑。不启用 meta 模块是为了避免
 * 引入 InfraToolProvider / CapabilityAggregator 等重型依赖。</p>
 *
 * <p>覆盖 6 个核心场景：</p>
 * <ol>
 *   <li>BUILTIN skill 启动安装 + skills 表写入</li>
 *   <li>{@code skill.load} 激活 + {@code activated_tool_ids} 合并</li>
 *   <li>禁用 skill 再激活应抛异常（DB enabled=false 过滤生效）</li>
 *   <li>未知 skill 应抛 "未知 skill" 异常</li>
 *   <li>一次最多加载 3 个 skill（超出抛异常）</li>
 *   <li>{@code AUTO_GENERATED} 入库后可被激活</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@SpringBootTest(
        classes = LifePilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "lifepilot.tool.enabled=true",
                "lifepilot.meta.enabled=false",
                "lifepilot.memory.enabled=true",
                "lifepilot.knowledge.enabled=true",
                "lifepilot.datastore.enabled=true",
                "lifepilot.workflow.enabled=false",
                "lifepilot.skills.enabled=true",
                "lifepilot.skills.auto-generation.enabled=false",
                "lifepilot.llm.enabled=false",
                "lifepilot.agent.enabled=false",
                "lifepilot.agent.multi-agent.enabled=false",
                "lifepilot.gateway.enabled=false",
                "lifepilot.media.enabled=false",
                "lifepilot.a2a.enabled=false",
                "lifepilot.mcp.enabled=false",
                "lifepilot.marketplace.enabled=false",
                "lifepilot.notification.enabled=false"
        }
)
@ActiveProfiles("test")
class SkillSystemRefactor_端到端集成测试 {

    private static final Logger log = LoggerFactory.getLogger(SkillSystemRefactor_端到端集成测试.class);

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String SKILLS_RESOURCE_PATTERN = "classpath:skills/*/SKILL.md";

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "skill-e2e-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "skill-e2e-vec-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        var skillsDir = Path.of(tmpDir, "skill-e2e-skills-" + DB_ID)
                .toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
        registry.add("lifepilot.skills.directory", () -> skillsDir);
    }

    @Autowired SkillInstaller installer;
    @Autowired SkillInstallationRepository repository;
    @Autowired SkillRegistry registry;
    @Autowired SkillActivator activator;
    @Autowired SkillLoadToolExecutor skillLoadExecutor;
    @Autowired MarkdownSkillParser parser;
    @Autowired SkillConfigProperties skillConfig;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * 每个测试前清表 + 重装全部 BUILTIN —— 替代未启用 meta 模块时缺席的
     * {@code SkillDiscoveryRegistrar}。与其启动期逻辑等价（install + registerToRegistry）。
     */
    @BeforeEach
    void installBuiltinSkills() throws IOException {
        jdbcTemplate.update("DELETE FROM skills");
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
        Path skillsRoot = Path.of(skillConfig.getDirectory());
        for (Resource resource : resources) {
            String skillName = extractSkillName(resource);
            if (skillName == null) {
                continue;
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                    SkillSourceType.BUILTIN,
                    "classpath:skills/" + skillName,
                    null,
                    content,
                    skillsRoot));
            registerToRegistry(content, Path.of(install.filePath()));
        }
    }

    // ==================== 6 场景 ====================

    @Test
    void BUILTIN_skill应在启动时全部安装到skills表() {
        List<SkillInstallation> builtin = repository.findAllBySourceType(SkillSourceType.BUILTIN);
        // classpath 下 25 个 BUILTIN Skill 全部应入库（Skill v2 fixup 下架 datastore / workflow-creator 后）
        assertThat(builtin).hasSizeGreaterThanOrEqualTo(25);
        // 抽查几个典型 skill 存在
        assertThat(builtin).extracting(SkillInstallation::name)
                .contains("introspection", "github-workflow", "skill-creator");
        // 全部默认启用
        assertThat(builtin).allMatch(SkillInstallation::enabled);
    }

    @Test
    void skill_load应合并activated_tool_ids() {
        // github-workflow 的 suggested_tools 包含 shell.exec / git.query 等多个工具
        SkillInstallation github = repository.findByName("github-workflow").orElseThrow();
        assertThat(github.enabled()).isTrue();

        Map<String, Object> result = skillLoadExecutor.execute(
                Map.of("names", List.of("github-workflow")));

        assertThat(result).containsKeys("content", "activated_tool_ids");
        String content = (String) result.get("content");
        assertThat(content)
                .contains("<skill name=\"github-workflow\">")
                .contains("</skill>")
                .contains("## 适用场景");
        List<?> activatedTools = (List<?>) result.get("activated_tool_ids");
        // github-workflow 声明了 shell.exec / git.query 等工具，合并列表不应为空
        assertThat(activatedTools).isNotEmpty();
    }

    @Test
    void 禁用skill后不应能激活_应抛异常() {
        String name = "introspection";
        repository.setEnabled(name, false);
        try {
            assertThatThrownBy(() -> skillLoadExecutor.execute(
                    Map.of("names", List.of(name))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已被禁用");
        } finally {
            // 恢复，避免污染其它用例（@BeforeEach 也会清表，但双保险）
            repository.setEnabled(name, true);
        }
    }

    @Test
    void 未知skill应抛未知异常() {
        assertThatThrownBy(() -> skillLoadExecutor.execute(
                Map.of("names", List.of("non-existent-ghost-skill-xyz"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 skill");
    }

    @Test
    void 一次最多加载3个skill() {
        assertThatThrownBy(() -> skillLoadExecutor.execute(
                Map.of("names", List.of("a", "b", "c", "d"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 3");
    }

    @Test
    void AUTO_GENERATED_skill入库后应可激活() throws IOException {
        String uniqueName = "e2e-auto-gen-" + UUID.randomUUID().toString().substring(0, 8);
        String md = """
                ---
                name: %s
                description: 当测试 AUTO_GENERATED 激活场景时使用。关键词 e2e、test、auto。本 Skill 仅用于端到端集成测试，不应出现在生产环境。
                version: 1.0.0
                ---
                # 自动生成 Skill 测试

                ## 适用场景
                - 端到端集成测试验证 AUTO_GENERATED 激活链路

                ## 不适用场景
                - 生产环境，真实用户请求

                ## 工作流
                1. 仅做激活链路验证，无实际逻辑
                """.formatted(uniqueName);

        Path autoDir = Path.of(skillConfig.getDirectory(), "auto");
        SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.AUTO_GENERATED,
                "ai-generated://e2e-test",
                null,
                md,
                autoDir));

        assertThat(install.sourceType()).isEqualTo(SkillSourceType.AUTO_GENERATED);
        assertThat(install.enabled()).isTrue();

        // Registry 侧也要注册，SkillActivator 激活时要从 Registry 拿 body
        registerToRegistry(md, Path.of(install.filePath()));

        // 应能被激活
        SkillActivation activation = activator.activate(install.name());
        assertThat(activation.name()).isEqualTo(install.name());
        assertThat(activation.instructions())
                .contains("## 适用场景")
                .contains("## 工作流");
    }

    // ==================== 辅助方法（等价于 SkillDiscoveryRegistrar 内部逻辑） ====================

    /**
     * 从 classpath Resource URL 中抽取 Skill 目录名（父目录名）。
     */
    private String extractSkillName(Resource resource) {
        try {
            String url = resource.getURL().toString();
            String[] parts = url.split("/");
            if (parts.length >= 2) {
                return parts[parts.length - 2];
            }
        } catch (IOException e) {
            log.debug("无法解析 Skill name: resource={}", resource.getFilename());
        }
        return null;
    }

    /**
     * 把 SKILL.md 解析为 SkillDefinition 并注册到 SkillRegistry ——
     * 等价于 {@code SkillDiscoveryRegistrar#registerToRegistry}。
     */
    private void registerToRegistry(String content, Path skillFolder) {
        var parsed = parser.parse(content);
        var fm = parsed.frontmatter();
        var zhiwei = fm.zhiweiMeta();

        Map<String, String> flatMetadata = zhiwei.category() != null
                ? Map.of("category", zhiwei.category())
                : Map.of();

        SkillDefinition definition = SkillDefinition.builder()
                .id(fm.name())
                .name(fm.name())
                .description(fm.description())
                .version(fm.version())
                .source(new SkillSource.UserDefined(skillFolder.toString(), Instant.now()))
                .instructions(parsed.body())
                .suggestedTools(zhiwei.suggestedTools())
                .metadata(flatMetadata)
                .zhiweiMeta(zhiwei)
                .build();

        registry.register(definition);
    }
}
