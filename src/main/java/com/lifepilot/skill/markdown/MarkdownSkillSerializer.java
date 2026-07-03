package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 序列化器 — 将 SkillDefinition 序列化为 SKILL.md 格式。
 *
 * <p>序列化规则：
 * <ol>
 *   <li>输出 {@code ---} 开头</li>
 *   <li>使用 SnakeYAML 将元数据字段序列化为 YAML（保持字段顺序：name → description → version → metadata）</li>
 *   <li>省略空的可选字段（metadata.zhiwei / metadata 扩展字段）</li>
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
     * <p>字段顺序：name → description → version → metadata。
     * 空的可选字段不输出。</p>
     */
    private LinkedHashMap<String, Object> buildFrontmatterMap(SkillDefinition definition) {
        var map = new LinkedHashMap<String, Object>();

        // 必填字段（始终输出）
        map.put("name", definition.name());
        map.put("description", definition.description());
        map.put("version", definition.version());

        LinkedHashMap<String, Object> metadata = buildMetadata(definition);
        if (!metadata.isEmpty()) {
            map.put("metadata", metadata);
        }

        return map;
    }

    private LinkedHashMap<String, Object> buildMetadata(SkillDefinition definition) {
        var metadata = new LinkedHashMap<String, Object>();
        SkillZhiweiMeta zhiweiMeta = definition.zhiweiMeta();
        LinkedHashMap<String, Object> zhiwei = buildZhiweiMeta(zhiweiMeta, definition.suggestedTools());
        if (!zhiwei.isEmpty()) {
            metadata.put("zhiwei", zhiwei);
        }
        for (Map.Entry<String, String> entry : definition.metadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        return metadata;
    }

    private LinkedHashMap<String, Object> buildZhiweiMeta(SkillZhiweiMeta meta,
                                                           List<String> fallbackSuggestedTools) {
        var zhiwei = new LinkedHashMap<String, Object>();
        List<String> suggestedTools = meta.suggestedTools().isEmpty()
                ? fallbackSuggestedTools
                : meta.suggestedTools();
        putNonEmptyList(zhiwei, "tags", meta.tags());
        putNonEmptyList(zhiwei, "suggested_tools", suggestedTools);
        putNonEmptyList(zhiwei, "outputs", meta.outputs());
        LinkedHashMap<String, Object> requires = buildRequires(meta.requires());
        if (!requires.isEmpty()) {
            zhiwei.put("requires", requires);
        }
        return zhiwei;
    }

    private LinkedHashMap<String, Object> buildRequires(SkillRequires requires) {
        var map = new LinkedHashMap<String, Object>();
        putNonEmptyList(map, "bins", requires.bins());
        putNonEmptyList(map, "env", requires.env());
        putNonEmptyList(map, "os", requires.os());
        putNonEmptyList(map, "tools", requires.tools());
        return map;
    }

    private void putNonEmptyList(LinkedHashMap<String, Object> map, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            map.put(key, List.copyOf(values));
        }
    }
}
