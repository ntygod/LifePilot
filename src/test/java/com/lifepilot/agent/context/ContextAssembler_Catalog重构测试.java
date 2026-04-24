package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.spec.SkillPriority;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import com.lifepilot.skill.validation.SkillRequirementGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A.7 —— ContextAssembler.buildSkillCatalog 查表重构测试。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>只包含 enabled=true 的 skill（查 skills 表）</li>
 *   <li>按 category 分组并生成 {@code <category>} 外层标签</li>
 *   <li>同 category 内按 priority 排序（HIGH → NORMAL → LOW）</li>
 *   <li>{@link SkillRequirementGate#satisfies(SkillRequires)} 为 false 时被过滤</li>
 *   <li>空目录 / 注册表未命中时返回空字符串，不调用模板渲染</li>
 *   <li>description 中的 XML 特殊字符被转义</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
class ContextAssembler_Catalog重构测试 {

    @Test
    void catalog应只包含enabled的skill() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        // 仓库只返回 enabled=true 的记录
        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("alpha"), buildInstallation("beta")));
        when(skillRegistry.find("alpha"))
                .thenReturn(Optional.of(buildSkill("alpha", "alpha desc", "group-x", SkillPriority.NORMAL)));
        when(skillRegistry.find("beta"))
                .thenReturn(Optional.of(buildSkill("beta", "beta desc", "group-x", SkillPriority.NORMAL)));

        String skillEntries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        assertThat(skillEntries).contains("alpha").contains("beta");
        verify(installationRepository).findAllByEnabled(true);
        verify(installationRepository, never()).findAllByEnabled(false);
    }

    @Test
    void catalog应按category分组() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("a-content"),
                buildInstallation("b-integration"),
                buildInstallation("c-content")));
        when(skillRegistry.find("a-content")).thenReturn(Optional.of(
                buildSkill("a-content", "desc-a", "content-creation", SkillPriority.NORMAL)));
        when(skillRegistry.find("b-integration")).thenReturn(Optional.of(
                buildSkill("b-integration", "desc-b", "external-integration", SkillPriority.NORMAL)));
        when(skillRegistry.find("c-content")).thenReturn(Optional.of(
                buildSkill("c-content", "desc-c", "content-creation", SkillPriority.NORMAL)));

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        // 两个 category 标签：content-creation 与 external-integration
        assertThat(xml).contains("<category name=\"content-creation\">");
        assertThat(xml).contains("<category name=\"external-integration\">");
        // content-creation 内两个 skill 紧邻在一起（a-content 和 c-content 之间不应出现 </category>）
        int contentStart = xml.indexOf("<category name=\"content-creation\">");
        int contentEnd = xml.indexOf("</category>", contentStart);
        String contentBlock = xml.substring(contentStart, contentEnd);
        assertThat(contentBlock).contains("a-content").contains("c-content");
        assertThat(contentBlock).doesNotContain("b-integration");
    }

    @Test
    void 同组内应按priority排序() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("low-skill"),
                buildInstallation("high-skill"),
                buildInstallation("normal-skill")));
        // 插入顺序与字母序都不是预期输出顺序，确保 priority 生效
        when(skillRegistry.find("low-skill")).thenReturn(Optional.of(
                buildSkill("low-skill", "low", "same-group", SkillPriority.LOW)));
        when(skillRegistry.find("high-skill")).thenReturn(Optional.of(
                buildSkill("high-skill", "high", "same-group", SkillPriority.HIGH)));
        when(skillRegistry.find("normal-skill")).thenReturn(Optional.of(
                buildSkill("normal-skill", "normal", "same-group", SkillPriority.NORMAL)));

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        int highIdx = xml.indexOf("high-skill");
        int normalIdx = xml.indexOf("normal-skill");
        int lowIdx = xml.indexOf("low-skill");
        assertThat(highIdx).isGreaterThan(-1);
        assertThat(normalIdx).isGreaterThan(highIdx);
        assertThat(lowIdx).isGreaterThan(normalIdx);
    }

    @Test
    void requires未满足的skill应被过滤() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);
        var gate = mock(SkillRequirementGate.class);

        var satisfied = buildSkill("satisfied", "ok", "cat", SkillPriority.NORMAL);
        var unsatisfied = buildSkillWithRequires("missing-bin", "needs git",
                new SkillRequires(List.of("git"), List.of(), List.of(), List.of()));

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("satisfied"), buildInstallation("missing-bin")));
        when(skillRegistry.find("satisfied")).thenReturn(Optional.of(satisfied));
        when(skillRegistry.find("missing-bin")).thenReturn(Optional.of(unsatisfied));
        when(gate.satisfies(satisfied.zhiweiMeta().requires())).thenReturn(true);
        when(gate.satisfies(unsatisfied.zhiweiMeta().requires())).thenReturn(false);

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, gate);

        assertThat(xml).contains("satisfied");
        assertThat(xml).doesNotContain("missing-bin");
    }

    @Test
    void 无enabled_skill时应返回空字符串() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system");
        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of());

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, skillRegistry);
        assembler.setSkillInstallationRepository(installationRepository);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("system");
        verify(promptRegistry, never()).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void description里特殊XML字符应被转义() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        var skill = buildSkill("tricky", "5 < 10 & <tag> \"quoted\"", "cat", SkillPriority.NORMAL);
        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("tricky")));
        when(skillRegistry.find("tricky")).thenReturn(Optional.of(skill));

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        // 原始字符不应未转义地出现在 description 里
        assertThat(xml).contains("&lt;").contains("&amp;").contains("&quot;").contains("&gt;");
        // 特别确认没有出现未转义的原始 < 或 & 在 description 中
        int descStart = xml.indexOf("<description>");
        int descEnd = xml.indexOf("</description>");
        String descBody = xml.substring(descStart + "<description>".length(), descEnd);
        assertThat(descBody).doesNotContain("<tag>");
        assertThat(descBody).doesNotContain(" & ");
    }

    @Test
    void 注册表未命中的skill应被跳过不抛异常() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("present"), buildInstallation("ghost")));
        when(skillRegistry.find("present")).thenReturn(Optional.of(
                buildSkill("present", "ok", "cat", SkillPriority.NORMAL)));
        when(skillRegistry.find("ghost")).thenReturn(Optional.empty());

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        assertThat(xml).contains("present").doesNotContain("ghost");
    }

    @Test
    void gate为null时默认全部放行() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        // 即便 skill 带有 requires，gate 为 null 时也应放行（过滤为无操作）
        var needsBin = buildSkillWithRequires("needs-bin", "want git",
                new SkillRequires(List.of("git-never-exists"), List.of(), List.of(), List.of()));
        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("needs-bin")));
        when(skillRegistry.find("needs-bin")).thenReturn(Optional.of(needsBin));

        String xml = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null);

        assertThat(xml).contains("needs-bin");
    }

    // ───────────────────────────── 辅助 ─────────────────────────────

    /**
     * 触发 buildReactSystemPrompt 并从 skill-catalog 模板渲染参数里捕获 skillEntries。
     */
    private String invokeBuildSkillCatalogEntries(SkillRegistry skillRegistry,
                                                   SkillInstallationRepository installationRepository,
                                                   SkillRequirementGate gate) {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system");

        // 捕获模板变量里的 skillEntries 原文，让断言直接作用在未经模板包装的 XML 片段上
        var captured = new String[]{""};
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = invocation.getArgument(1, Map.class);
            captured[0] = String.valueOf(vars.get("skillEntries"));
            return "<skill_catalog>\n" + captured[0] + "\n</skill_catalog>";
        });

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, skillRegistry);
        assembler.setSkillInstallationRepository(installationRepository);
        if (gate != null) {
            assembler.setSkillRequirementGate(gate);
        }

        assembler.buildReactSystemPrompt();
        return captured[0];
    }

    private static SkillInstallation buildInstallation(String name) {
        return new SkillInstallation(
                name,
                SkillSourceType.USER_IMPORTED,
                "/test/" + name,
                "/test/" + name + "/SKILL.md",
                "1.0",
                true,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
    }

    private static SkillDefinition buildSkill(String name, String description,
                                               String category, SkillPriority priority) {
        var meta = new SkillZhiweiMeta(List.of(), List.of(), category, priority, SkillRequires.empty());
        return SkillDefinition.builder()
                .id(name)
                .name(name)
                .description(description)
                .version("1.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("body")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .zhiweiMeta(meta)
                .build();
    }

    private static SkillDefinition buildSkillWithRequires(String name, String description,
                                                            SkillRequires requires) {
        var meta = new SkillZhiweiMeta(List.of(), List.of(), "cat", SkillPriority.NORMAL, requires);
        return SkillDefinition.builder()
                .id(name)
                .name(name)
                .description(description)
                .version("1.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("body")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .zhiweiMeta(meta)
                .build();
    }

    private static AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        var context = new AgentConfigProperties.ContextConfig();
        context.setMaxContextTokens(8000);
        config.setContext(context);
        return config;
    }
}
