package com.lifepilot.skill.spec;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillToolReferenceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 Skill 元数据规范测试。
 *
 * @author zsg
 * @since 2026-07-02
 */
class BuiltinSkillMetadata_规范测试 {

    private static final List<String> LEGACY_TOOL_ALIASES = List.of(
            "shell_exec",
            "shell_process",
            "web_fetch",
            "web_search",
            "file_read",
            "file_write",
            "file_manage",
            "skill_load"
    );

    private static final Set<String> ALLOWED_OUTPUTS = Set.of(
            "text", "file", "a2ui", "memory", "notification", "task");

    private static final List<String> V3_REQUIRED_HEADINGS = List.of(
            "## 触发判断",
            "## 决策路径",
            "## 输出标准",
            "## 失败策略");

    private final MarkdownSkillParser parser = new MarkdownSkillParser();
    private final SkillDescriptionValidator descriptionValidator = new SkillDescriptionValidator();
    private final SkillBodyValidator bodyValidator = new SkillBodyValidator();

    @Test
    void 内置Skill不应再出现旧工具别名() throws Exception {
        Resource[] resources = loadSkillResources();
        var violations = new LinkedHashSet<String>();

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            for (String alias : LEGACY_TOOL_ALIASES) {
                if (content.contains(alias)) {
                    violations.add(resource.getURL() + " 包含旧工具别名 " + alias);
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void 内置SkillSuggestedTools必须使用CanonicalToolId() throws Exception {
        Resource[] resources = loadSkillResources();
        var violations = new LinkedHashSet<String>();

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            var parsed = parser.parse(content);
            for (String toolId : parsed.frontmatter().zhiweiMeta().suggestedTools()) {
                if (!SkillToolReferenceCatalog.isCanonicalToolId(toolId)) {
                    violations.add(parsed.frontmatter().name() + " 引用了未知工具 ID: " + toolId);
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void 内置Skill应通过Description和Body基础校验() throws Exception {
        Resource[] resources = loadSkillResources();
        var violations = new LinkedHashSet<String>();

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            try {
                var parsed = parser.parse(content);
                descriptionValidator.validate(parsed.frontmatter().description());
                bodyValidator.validate(parsed.body());
            } catch (RuntimeException e) {
                violations.add(resource.getURL() + " 校验失败: " + e.getMessage());
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void 内置Skill必须声明合法Outputs并采用v3四段式() throws Exception {
        Resource[] resources = loadSkillResources();
        var violations = new LinkedHashSet<String>();

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            var parsed = parser.parse(content);
            var outputs = parsed.frontmatter().zhiweiMeta().outputs();
            if (outputs.isEmpty()) {
                violations.add(parsed.frontmatter().name() + " 缺少 metadata.zhiwei.outputs");
            }
            for (String output : outputs) {
                if (!ALLOWED_OUTPUTS.contains(output)) {
                    violations.add(parsed.frontmatter().name() + " 声明了未知 outputs: " + output);
                }
            }
            for (String heading : V3_REQUIRED_HEADINGS) {
                if (!parsed.body().contains(heading)) {
                    violations.add(parsed.frontmatter().name() + " 缺少 v3 小节: " + heading);
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private Resource[] loadSkillResources() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
        assertTrue(resources.length > 0, "未加载到内置 Skill 资源");
        return resources;
    }
}
