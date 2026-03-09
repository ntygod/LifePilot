package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 解析器 — 解析 YAML Frontmatter + Markdown Body。
 *
 * <p>解析流程：
 * <ol>
 *   <li>查找第一个 {@code ---} 行（必须是文件首行或首行为空后的第一行）</li>
 *   <li>查找第二个 {@code ---} 行，提取之间的内容为 YAML Frontmatter</li>
 *   <li>使用 SnakeYAML 解析 Frontmatter 为 {@code Map<String, Object>}</li>
 *   <li>提取第二个 {@code ---} 之后的所有内容为 Markdown Body（trim 后作为 instructions）</li>
 *   <li>校验必填字段（id、name、description）</li>
 *   <li>校验 instructions 非空</li>
 *   <li>将 Map 字段映射为 {@link SkillDefinition} record</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillParser.class);

    /** Frontmatter 分隔符。 */
    private static final String FRONTMATTER_DELIMITER = "---";

    /** 必填字段列表。 */
    private static final List<String> REQUIRED_FIELDS = List.of("id", "name", "description");

    /**
     * 解析 SKILL.md 内容为 ParseResult。
     *
     * @param content SKILL.md 文件的完整文本内容
     * @return 解析结果
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.failure(List.of("SKILL.md 内容不能为空"));
        }

        // BOM 剥离：UTF-8 BOM 字符 \uFEFF 会导致首行 --- 匹配失败
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
            log.debug("SKILL.md 内容包含 BOM 标记，已剥离");
        }

        // CRLF 统一：避免后续 YAML 解析器的兼容性问题
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // 1. 查找 Frontmatter 分隔符
        var lines = content.lines().toList();
        int firstDelimiter = -1;
        int secondDelimiter = -1;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals(FRONTMATTER_DELIMITER)) {
                if (firstDelimiter == -1) {
                    // 第一个 --- 必须是首行或首行为空后的第一行
                    if (i == 0 || lines.subList(0, i).stream().allMatch(String::isBlank)) {
                        firstDelimiter = i;
                    } else {
                        return ParseResult.failure(List.of("YAML Frontmatter 分隔符 --- 必须在文件首行或首行为空后的第一行"));
                    }
                } else {
                    secondDelimiter = i;
                    break;
                }
            } else if (firstDelimiter == -1 && !trimmed.isEmpty()) {
                // 第一个非空行不是 ---，说明没有 Frontmatter
                return ParseResult.failure(List.of("缺少 YAML Frontmatter 分隔符 ---"));
            }
        }

        if (firstDelimiter == -1 || secondDelimiter == -1) {
            return ParseResult.failure(List.of("缺少 YAML Frontmatter 分隔符 ---"));
        }

        // 2. 提取 Frontmatter YAML 内容
        var yamlLines = lines.subList(firstDelimiter + 1, secondDelimiter);
        String yamlContent = String.join("\n", yamlLines);

        // 3. 使用 SnakeYAML 解析
        Map<String, Object> frontmatterMap;
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(yamlContent);
            if (parsed == null) {
                frontmatterMap = Map.of();
            } else if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var typedMap = (Map<String, Object>) map;
                frontmatterMap = typedMap;
            } else {
                return ParseResult.failure(List.of("YAML Frontmatter 格式错误: 期望 Map 结构"));
            }
        } catch (Exception e) {
            log.warn("YAML Frontmatter 解析失败: {}", e.getMessage());
            return ParseResult.failure(List.of("YAML 语法错误: " + e.getMessage()));
        }

        // 4. 提取 Markdown Body
        String markdownBody = "";
        if (secondDelimiter + 1 < lines.size()) {
            markdownBody = String.join("\n", lines.subList(secondDelimiter + 1, lines.size())).trim();
        }

        // 5. 校验必填字段
        var errors = new ArrayList<String>();
        for (String field : REQUIRED_FIELDS) {
            Object value = frontmatterMap.get(field);
            if (value == null || value.toString().isBlank()) {
                errors.add("缺少必填字段: " + field);
            }
        }

        // 6. 校验 instructions 非空
        if (markdownBody.isEmpty()) {
            errors.add("instructions 不能为空");
        }

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors, frontmatterMap);
        }

        // 7. 映射字段到 SkillDefinition
        try {
            SkillDefinition definition = mapToDefinition(frontmatterMap, markdownBody);
            return ParseResult.success(definition, frontmatterMap);
        } catch (Exception e) {
            log.warn("SkillDefinition 构建失败: {}", e.getMessage());
            return ParseResult.failure(List.of("SkillDefinition 构建失败: " + e.getMessage()), frontmatterMap);
        }
    }

    // ─────────────────────────────────────────────
    //  字段映射
    // ─────────────────────────────────────────────

    /**
     * 将 Frontmatter Map + Markdown Body 映射为 SkillDefinition。
     */
    private SkillDefinition mapToDefinition(Map<String, Object> fm, String instructions) {
        String id = getString(fm, "id");
        String name = getString(fm, "name");
        String description = getString(fm, "description");
        String version = getStringOrDefault(fm, "version", "1.0.0");
        List<String> suggestedTools = getStringList(fm, "suggested-tools");
        Map<String, String> metadata = parseMetadata(fm);

        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .source(new SkillSource.UserDefined("", null))
                .instructions(instructions)
                .suggestedTools(suggestedTools)
                .metadata(metadata)
                .build();
    }

    /**
     * 解析 metadata — 合并 frontmatter 中的 metadata 节点和顶层 tags/category/author/dependencies。
     */
    private Map<String, String> parseMetadata(Map<String, Object> fm) {
        var result = new HashMap<String, String>();

        // 从 metadata 节点提取
        var metadataNode = fm.get("metadata");
        if (metadataNode instanceof Map<?, ?> metaMap) {
            for (var entry : metaMap.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }

        // 从顶层提取 tags（列表→逗号拼接）
        var tagsNode = fm.get("tags");
        if (tagsNode instanceof List<?> tagsList) {
            String joined = tagsList.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
            if (!joined.isEmpty()) {
                result.put("tags", joined);
            }
        } else if (tagsNode instanceof String tagsStr && !tagsStr.isBlank()) {
            result.put("tags", tagsStr);
        }

        // 从顶层提取 category
        var categoryNode = fm.get("category");
        if (categoryNode != null && !categoryNode.toString().isBlank()) {
            result.put("category", categoryNode.toString());
        }

        // 从顶层提取 author
        var authorNode = fm.get("author");
        if (authorNode != null && !authorNode.toString().isBlank()) {
            result.put("author", authorNode.toString());
        }

        // 从顶层提取 dependencies（列表→逗号拼接）
        var depsNode = fm.get("dependencies");
        if (depsNode instanceof List<?> depsList) {
            String joined = depsList.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
            if (!joined.isEmpty()) {
                result.put("dependencies", joined);
            }
        } else if (depsNode instanceof String depsStr && !depsStr.isBlank()) {
            result.put("dependencies", depsStr);
        }

        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }


    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }



    // ─────────────────────────────────────────────
    //  解析结果
    // ─────────────────────────────────────────────

    /**
     * 解析结果。
     *
     * @param success        是否成功
     * @param definition     解析出的 SkillDefinition（成功时非 null）
     * @param errors         错误信息列表
     * @param frontmatterMap 解析出的 Frontmatter Map（用于验证管线）
     */
    public record ParseResult(
            boolean success,
            @Nullable SkillDefinition definition,
            List<String> errors,
            @Nullable Map<String, Object> frontmatterMap
    ) {

        /** 紧凑构造器 — 防御性拷贝。 */
        public ParseResult {
            errors = List.copyOf(errors);
        }

        /** 构建成功结果。 */
        static ParseResult success(SkillDefinition definition, Map<String, Object> frontmatterMap) {
            return new ParseResult(true, definition, List.of(), frontmatterMap);
        }

        /** 构建失败结果（无 frontmatterMap）。 */
        static ParseResult failure(List<String> errors) {
            return new ParseResult(false, null, errors, null);
        }

        /** 构建失败结果（含 frontmatterMap）。 */
        static ParseResult failure(List<String> errors, Map<String, Object> frontmatterMap) {
            return new ParseResult(false, null, errors, frontmatterMap);
        }
    }
}
