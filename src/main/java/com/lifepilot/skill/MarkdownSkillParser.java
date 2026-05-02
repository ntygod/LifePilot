package com.lifepilot.skill;

import com.lifepilot.skill.spec.SkillFrontmatter;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析 SKILL.md（YAML frontmatter + Markdown body）。
 *
 * <p>遵守 {@code docs/skill-spec.md} v2 规范：
 * <ul>
 *   <li>必需字段：{@code name / description / version}</li>
 *   <li>{@code name} 必须匹配 {@code ^[a-z0-9][a-z0-9-]{0,62}$}</li>
 *   <li>拒绝老字段 {@code id}（v1 规范已废弃）</li>
 *   <li>{@code metadata.zhiwei} 嵌套块解析为 {@link SkillZhiweiMeta}</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class MarkdownSkillParser {

    /** frontmatter 分隔符匹配：首行 {@code ---} + 内容 + 结束 {@code ---} + body。 */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.+?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    /** name 合法字符正则：小写字母/数字开头，中划线可出现在中间，总长 1-63 字符。 */
    private static final Pattern NAME_REGEX = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    /** frontmatter 必需字段列表。 */
    private static final List<String> REQUIRED = List.of("name", "description", "version");

    /** 解析产物：frontmatter 结构化视图 + 原始 body 文本。 */
    public record ParsedSkill(SkillFrontmatter frontmatter, String body) {}

    /**
     * 解析 SKILL.md 全文。
     *
     * @param content SKILL.md 文件完整内容
     * @return {@link ParsedSkill}（frontmatter + body）
     * @throws IllegalArgumentException 当格式非法、缺字段、使用废弃字段或 name 不合规时
     */
    @SuppressWarnings("unchecked")
    public ParsedSkill parse(String content) {
        var matcher = FRONTMATTER.matcher(content.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开头");
        }

        Map<String, Object> raw = new Yaml().load(matcher.group(1));
        if (raw == null) {
            raw = Map.of();
        }

        // 拒绝老字段 id
        if (raw.containsKey("id")) {
            throw new IllegalArgumentException(
                    "字段 'id' 已废弃，请用 'name'（见 docs/skill-spec.md §2.1）");
        }

        // 必需字段校验
        for (String f : REQUIRED) {
            if (!raw.containsKey(f) || raw.get(f) == null) {
                throw new IllegalArgumentException("缺少必需字段: " + f);
            }
        }

        String name = raw.get("name").toString();
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name 必须匹配正则 ^[a-z0-9][a-z0-9-]{0,62}$，实际: " + name);
        }

        SkillZhiweiMeta meta = parseZhiweiMeta(raw);

        var fm = new SkillFrontmatter(
                name,
                raw.get("description").toString(),
                raw.get("version").toString(),
                meta);

        return new ParsedSkill(fm, matcher.group(2));
    }

    /**
     * 解析 {@code metadata.zhiwei} 嵌套块；缺失或为 null 时返回 {@link SkillZhiweiMeta#empty()}。
     */
    @SuppressWarnings("unchecked")
    private SkillZhiweiMeta parseZhiweiMeta(Map<String, Object> raw) {
        Object metadataObj = raw.get("metadata");
        if (!(metadataObj instanceof Map<?, ?>)) {
            return SkillZhiweiMeta.empty();
        }
        Map<String, Object> metadata = (Map<String, Object>) metadataObj;

        Object zhiweiObj = metadata.get("zhiwei");
        if (!(zhiweiObj instanceof Map<?, ?>)) {
            return SkillZhiweiMeta.empty();
        }
        Map<String, Object> zhiwei = (Map<String, Object>) zhiweiObj;

        var suggestedTools = asStringList(zhiwei.get("suggested_tools"));
        var tags = asStringList(zhiwei.get("tags"));

        Object reqObj = zhiwei.get("requires");
        Map<String, Object> req = (reqObj instanceof Map<?, ?>)
                ? (Map<String, Object>) reqObj
                : Map.of();
        var requires = new SkillRequires(
                asStringList(req.get("bins")),
                asStringList(req.get("env")),
                asStringList(req.get("os")),
                asStringList(req.get("tools")));

        return new SkillZhiweiMeta(suggestedTools, tags, requires);
    }

    /**
     * 宽容地把任意 YAML 节点转为字符串列表：null → 空列表；List → 逐个 toString；标量 → 单元素列表。
     */
    private List<String> asStringList(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return l.stream().map(Object::toString).toList();
        }
        return List.of(o.toString());
    }

}
