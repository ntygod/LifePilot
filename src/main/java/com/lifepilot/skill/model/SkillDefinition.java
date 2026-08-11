package com.lifepilot.skill.model;

import com.lifepilot.skill.spec.SkillZhiweiMeta;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Skill 定义 — 程序性知识包的完整蓝图。
 *
 * <p>每个 Skill 通过此 record 描述其 ID、名称、描述、版本、来源、指令（instructions）、
 * 建议工具列表（suggestedTools）、扁平 metadata 以及结构化 {@link SkillZhiweiMeta}
 * （frontmatter 下 {@code metadata.zhiwei} 块的视图）。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
@Builder(toBuilder = true)
public record SkillDefinition(
        String id,
        String name,
        String description,
        String version,
        SkillSource source,
        String instructions,
        /** Skill 静态声明的配套工具 ID — 仅作为 UI 展示、检索和人工参考元数据；
         * 加载 Skill 不会直接注入这些工具，缺失能力统一通过 {@code tool.search} 发现。 */
        List<String> suggestedTools,
        Map<String, String> metadata,
        SkillZhiweiMeta zhiweiMeta
) {

    /** 紧凑构造器 — 校验 + 防御性拷贝。 */
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill ID 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill 名称不能为空");
        if (instructions == null || instructions.isBlank()) throw new IllegalArgumentException("Skill 指令不能为空");
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        zhiweiMeta = zhiweiMeta == null ? SkillZhiweiMeta.empty() : zhiweiMeta;
    }

    /**
     * 返回 XML 格式的摘要字符串，用于系统提示词中的 Skill 发现。
     *
     * @return 包含 id、name、description 的 XML 摘要
     */
    public String toDiscoverySummary() {
        var sb = new StringBuilder();
        sb.append("<skill id=\"").append(id).append("\" name=\"").append(name).append("\">\n");
        sb.append("  <description>").append(description != null ? description : "").append("</description>\n");
        if (!zhiweiMeta.outputs().isEmpty()) {
            sb.append("  <outputs>").append(String.join(",", zhiweiMeta.outputs())).append("</outputs>\n");
        }
        sb.append("</skill>");
        return sb.toString();
    }
}
