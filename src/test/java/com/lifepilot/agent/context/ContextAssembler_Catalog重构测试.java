package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
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
 * ContextAssembler.buildSkillCatalog 测试 —— XML 标签格式 + 全量列出，按 query 关键词排序。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>只包含 enabled=true 的 skill（查 skills 表）</li>
 *   <li>按关键词命中度优先 + priority 兜底排序（命中 query 的排在前面，便于 LLM 优先注意）</li>
 *   <li>{@link SkillRequirementGate#satisfies(SkillRequires)} 为 false 时被过滤</li>
 *   <li>空目录 / 注册表未命中时返回空字符串，不调用模板渲染</li>
 *   <li>全量列出（不截断），无"剩余 X 个"提示</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-25
 */
class ContextAssembler_Catalog重构测试 {

    @Test
    void catalog应只包含enabled的skill() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("alpha"), buildInstallation("beta")));
        when(skillRegistry.find("alpha"))
                .thenReturn(Optional.of(buildSkill("alpha", "alpha desc")));
        when(skillRegistry.find("beta"))
                .thenReturn(Optional.of(buildSkill("beta", "beta desc")));

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries).contains("alpha").contains("beta");
        verify(installationRepository).findAllByEnabled(true);
        verify(installationRepository, never()).findAllByEnabled(false);
    }

    @Test
    void 关键词命中的skill应排在前面() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("daily"), buildInstallation("code"), buildInstallation("data")));
        when(skillRegistry.find("daily")).thenReturn(Optional.of(
                buildSkill("daily", "日常事务规划与日报")));
        when(skillRegistry.find("code")).thenReturn(Optional.of(
                buildSkill("code", "代码生成、修改、解释")));
        when(skillRegistry.find("data")).thenReturn(Optional.of(
                buildSkill("data", "数据分析与可视化")));

        // 用户 query 涉及"代码"，code 应在最前
        String entries = invokeBuildSkillCatalogEntries(
                skillRegistry, installationRepository, null, "帮我写一段代码");

        int codeIdx = entries.indexOf("code");
        int dailyIdx = entries.indexOf("daily");
        int dataIdx = entries.indexOf("data");
        assertThat(codeIdx).isGreaterThan(-1);
        assertThat(codeIdx).isLessThan(dailyIdx);
        assertThat(codeIdx).isLessThan(dataIdx);
    }

    @Test
    void requires未满足的skill应被过滤() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);
        var gate = mock(SkillRequirementGate.class);

        var satisfied = buildSkill("satisfied", "ok");
        var unsatisfied = buildSkillWithRequires("missing-bin", "needs git",
                new SkillRequires(List.of("git"), List.of(), List.of(), List.of()));

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("satisfied"), buildInstallation("missing-bin")));
        when(skillRegistry.find("satisfied")).thenReturn(Optional.of(satisfied));
        when(skillRegistry.find("missing-bin")).thenReturn(Optional.of(unsatisfied));
        when(gate.satisfies(satisfied.zhiweiMeta().requires())).thenReturn(true);
        when(gate.satisfies(unsatisfied.zhiweiMeta().requires())).thenReturn(false);

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, gate, null);

        assertThat(entries).contains("satisfied");
        assertThat(entries).doesNotContain("missing-bin");
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
    void 注册表未命中的skill应被跳过不抛异常() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true)).thenReturn(List.of(
                buildInstallation("present"), buildInstallation("ghost")));
        when(skillRegistry.find("present")).thenReturn(Optional.of(
                buildSkill("present", "ok")));
        when(skillRegistry.find("ghost")).thenReturn(Optional.empty());

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries).contains("present").doesNotContain("ghost");
    }

    @Test
    void gate为null时默认全部放行() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        var needsBin = buildSkillWithRequires("needs-bin", "want git",
                new SkillRequires(List.of("git-never-exists"), List.of(), List.of(), List.of()));
        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("needs-bin")));
        when(skillRegistry.find("needs-bin")).thenReturn(Optional.of(needsBin));

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries).contains("needs-bin");
    }

    @Test
    void 全量列出所有skill不截断() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        var installs = new java.util.ArrayList<SkillInstallation>();
        for (int i = 0; i < 12; i++) {
            String name = String.format("skill%02d", i);
            installs.add(buildInstallation(name));
            when(skillRegistry.find(name)).thenReturn(Optional.of(
                    buildSkill(name, "desc " + i)));
        }
        when(installationRepository.findAllByEnabled(true)).thenReturn(installs);

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        // 12 个 skill 全部列出（XML 格式）
        long lineCount = entries.lines().filter(l -> l.startsWith("<skill name=\"skill")).count();
        assertThat(lineCount).isEqualTo(12);
        // 不再有"剩余 X 个"提示（删除了 find-skills 兜底入口）
        assertThat(entries).doesNotContain("另有").doesNotContain("未列出");
    }

    @Test
    void description中的关键词段应在渲染时被剥离() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("research")));
        when(skillRegistry.find("research")).thenReturn(Optional.of(buildSkill(
                "research",
                "当用户要做多源搜索时使用。关键词：调研、查资料、对比分析。代码搜索用 code-assistant。")));

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries)
                .contains("当用户要做多源搜索时使用。")
                .contains("代码搜索用 code-assistant")
                .doesNotContain("关键词")
                .doesNotContain("调研、查资料");
    }

    @Test
    void description无关键词段时应保持原文不变() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("a")));
        when(skillRegistry.find("a")).thenReturn(Optional.of(buildSkill(
                "a", "纯描述，没有关键词段。")));

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries).contains("纯描述，没有关键词段。");
    }

    @Test
    void XML标签格式包含name与description() {
        var skillRegistry = mock(SkillRegistry.class);
        var installationRepository = mock(SkillInstallationRepository.class);

        when(installationRepository.findAllByEnabled(true))
                .thenReturn(List.of(buildInstallation("alpha")));
        when(skillRegistry.find("alpha")).thenReturn(Optional.of(
                buildSkill("alpha", "alpha 描述文本")));

        String entries = invokeBuildSkillCatalogEntries(skillRegistry, installationRepository, null, null);

        assertThat(entries).contains("<skill name=\"alpha\">")
                .contains("<description>alpha 描述文本</description>")
                .contains("</skill>");
    }

    // ───────────────────────────── 辅助 ─────────────────────────────

    private String invokeBuildSkillCatalogEntries(SkillRegistry skillRegistry,
                                                   SkillInstallationRepository installationRepository,
                                                   SkillRequirementGate gate,
                                                   String userGoal) {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system");

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

        ReactAgentState state = userGoal == null ? null : mock(ReactAgentState.class);
        if (state != null) {
            when(state.goal()).thenReturn(userGoal);
        }

        assembler.buildReactSystemPrompt(state);
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

    private static SkillDefinition buildSkill(String name, String description) {
        var meta = new SkillZhiweiMeta(List.of(), List.of(), SkillRequires.empty());
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
        var meta = new SkillZhiweiMeta(List.of(), List.of(), requires);
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
