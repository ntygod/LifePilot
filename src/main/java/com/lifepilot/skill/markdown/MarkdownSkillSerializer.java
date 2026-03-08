package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.SkillDefinition;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * SKILL.md 序列化器 — 将 SkillDefinition 序列化为 SKILL.md 格式。
 *
 * <p>序列化规则：
 * <ol>
 *   <li>输出 {@code ---} 开头</li>
 *   <li>使用 SnakeYAML 将元数据字段序列化为 YAML（保持字段顺序：id → name → description → version → suggested-tools → metadata）</li>
 *   <li>省略空的可选字段（metadata）</li>
 *   <li>输出 {@code ---} 结尾</li>
 *   <li>输出空行</li>
 *   <li>输出 instructions 作为 Markdown Body</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillSerializer {

    /**
     * 将 SkillDefinition 序列化为 SKILL.md 格式字符串。
     *
     * @param definition Skill 定义
     * @return SKILL.md 格式的完整文本
     */
    public String serialize(SkillDefinition definition) {
        var frontmatterMap = buildFrontmatterMap(definition);

        // 配置 SnakeYAML — BLOCK 风格、2 空格缩进
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndicatorIndent(0);
        options.setIndent(2);

        var yaml = new Yaml(options);
        String yamlContent = yaml.dump(frontmatterMap);

        // 组装 SKILL.md：--- + YAML + --- + 空行 + Markdown Body
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yamlContent);
        sb.append("---\n");
        sb.append("\n");
        sb.append(definition.instructions());
        sb.append("\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────
    //  Frontmatter Map 构建
    // ─────────────────────────────────────────────

    /**
     * 构建 Frontmatter 的有序 Map 表示。
     *
     * <p>字段顺序：id → name → description → version → suggested-tools → metadata。
     * 空的可选字段不输出。</p>
     */
    private LinkedHashMap<String, Object> buildFrontmatterMap(SkillDefinition definition) {
        var map = new LinkedHashMap<String, Object>();

        // 必填字段（始终输出）
        map.put("id", definition.id());
        map.put("name", definition.name());
        map.put("description", definition.description());
        map.put("version", definition.version());
        map.put("suggested-tools", new ArrayList<>(definition.suggestedTools()));

        // 可选：metadata（省略空 Map）
        if (!definition.metadata().isEmpty()) {
            map.put("metadata", new LinkedHashMap<>(definition.metadata()));
        }

        return map;
    }
}
