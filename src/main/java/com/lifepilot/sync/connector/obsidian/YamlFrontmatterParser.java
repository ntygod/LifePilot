package com.lifepilot.sync.connector.obsidian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * YAML Frontmatter 解析器，解析 Markdown 文件中 {@code ---} 分隔的 YAML frontmatter。
 *
 * <p>支持解析和格式化两个方向：
 * <ul>
 *   <li>{@link #parse(String)}：从 Markdown 文本中提取 frontmatter 键值对和正文</li>
 *   <li>{@link #format(Map, String)}：将键值对和正文格式化为 Markdown 文本</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class YamlFrontmatterParser {

    /** Frontmatter 分隔符。 */
    private static final String DELIMITER = "---";

    /** 需要引号包裹的特殊字符。 */
    private static final String SPECIAL_CHARS = ":{}[]#&*!|>'\"%@`,?";

    private YamlFrontmatterParser() {
        // 工具类，禁止实例化
    }

    /**
     * 解析结果，包含 frontmatter 键值对和正文。
     *
     * @param frontmatter 键值对映射（值为 String 或 List&lt;String&gt;）
     * @param body        正文内容（可能为空字符串）
     */
    public record ParseResult(Map<String, Object> frontmatter, String body) {}

    // ==================== 解析方法 ====================

    /**
     * 解析 Markdown 内容，提取 YAML frontmatter 和正文。
     *
     * <p>解析规则：
     * <ol>
     *   <li>内容必须以 {@code ---} 开头</li>
     *   <li>frontmatter 在下一个 {@code ---} 行结束</li>
     *   <li>如果没有有效的分隔符，返回空 frontmatter 和完整内容作为正文</li>
     * </ol>
     *
     * @param content Markdown 文本内容（可以为 null 或空）
     * @return 解析结果
     */
    public static ParseResult parse(String content) {
        if (content == null || content.isEmpty()) {
            return new ParseResult(Map.of(), "");
        }

        var lines = content.split("\\r?\\n", -1);

        // 第一行必须是 ---
        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            return new ParseResult(Map.of(), content.strip());
        }

        // 查找结束分隔符
        int closingIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                closingIndex = i;
                break;
            }
        }

        if (closingIndex < 0) {
            // 没有找到结束分隔符，视为无 frontmatter
            return new ParseResult(Map.of(), content.strip());
        }

        // 解析 frontmatter 键值对
        var frontmatter = new LinkedHashMap<String, Object>();
        for (int i = 1; i < closingIndex; i++) {
            parseLine(lines[i], frontmatter);
        }

        // 提取正文（结束分隔符之后的内容）
        var bodyBuilder = new StringBuilder();
        for (int i = closingIndex + 1; i < lines.length; i++) {
            if (i > closingIndex + 1) {
                bodyBuilder.append('\n');
            }
            bodyBuilder.append(lines[i]);
        }
        var body = bodyBuilder.toString().strip();

        return new ParseResult(Map.copyOf(frontmatter), body);
    }

    // ==================== 格式化方法 ====================

    /**
     * 将 frontmatter 键值对和正文格式化为 Markdown 文本。
     *
     * <p>格式化规则：
     * <ol>
     *   <li>以 {@code ---} 开头</li>
     *   <li>每个键值对占一行：{@code key: value}</li>
     *   <li>列表值格式化为 {@code key: [item1, item2]}</li>
     *   <li>包含特殊字符的字符串值用双引号包裹</li>
     *   <li>以 {@code ---} 结尾</li>
     *   <li>正文追加在分隔符之后，用空行分隔</li>
     * </ol>
     *
     * @param frontmatter 键值对映射
     * @param body        正文内容（可以为 null 或空）
     * @return 格式化后的 Markdown 文本
     */
    public static String format(Map<String, Object> frontmatter, String body) {
        var sb = new StringBuilder();
        sb.append(DELIMITER).append('\n');

        if (frontmatter != null) {
            for (var entry : frontmatter.entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                sb.append(key).append(": ").append(formatValue(value)).append('\n');
            }
        }

        sb.append(DELIMITER).append('\n');

        if (body != null && !body.isEmpty()) {
            sb.append('\n').append(body).append('\n');
        }

        return sb.toString();
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 解析单行 YAML 键值对。
     */
    private static void parseLine(String line, Map<String, Object> map) {
        if (line == null || line.isBlank()) {
            return;
        }

        // 查找第一个冒号分隔符
        int colonIndex = line.indexOf(':');
        if (colonIndex < 0) {
            return;
        }

        var key = line.substring(0, colonIndex).strip();
        if (key.isEmpty()) {
            return;
        }

        var rawValue = line.substring(colonIndex + 1);

        // 去除行内注释（# 前有空格的情况）
        rawValue = stripInlineComment(rawValue);
        rawValue = rawValue.strip();

        // 空值跳过
        if (rawValue.isEmpty()) {
            return;
        }

        // 解析列表值 [a, b, c]
        if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
            var listContent = rawValue.substring(1, rawValue.length() - 1);
            var items = parseListItems(listContent);
            if (!items.isEmpty()) {
                map.put(key, items);
            }
            return;
        }

        // 解析引号包裹的值
        var unquoted = stripQuotes(rawValue);
        map.put(key, unquoted);
    }

    /**
     * 去除行内注释。
     *
     * <p>注释以 {@code " #"} 开头（# 前有空格），但引号内的 # 不视为注释。
     */
    private static String stripInlineComment(String value) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean inBracket = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '[' && !inDoubleQuote && !inSingleQuote) {
                inBracket = true;
            } else if (c == ']' && !inDoubleQuote && !inSingleQuote) {
                inBracket = false;
            } else if (c == '#' && !inDoubleQuote && !inSingleQuote && !inBracket) {
                // 检查 # 前是否有空格
                if (i > 0 && value.charAt(i - 1) == ' ') {
                    return value.substring(0, i - 1);
                }
            }
        }
        return value;
    }

    /**
     * 去除字符串两端的引号（单引号或双引号）。
     */
    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 解析列表内容，支持引号包裹的元素。
     */
    private static List<String> parseListItems(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var items = new ArrayList<String>();
        var parts = content.split(",");
        for (var part : parts) {
            var item = stripQuotes(part.strip());
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    /**
     * 格式化值为 YAML 字符串。
     */
    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof List<?> list) {
            var formatted = list.stream()
                    .map(item -> String.valueOf(item))
                    .collect(Collectors.joining(", "));
            return "[" + formatted + "]";
        }

        var str = String.valueOf(value);
        if (needsQuoting(str)) {
            return "\"" + str + "\"";
        }
        return str;
    }

    /**
     * 判断字符串值是否需要引号包裹。
     */
    private static boolean needsQuoting(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (SPECIAL_CHARS.indexOf(value.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
