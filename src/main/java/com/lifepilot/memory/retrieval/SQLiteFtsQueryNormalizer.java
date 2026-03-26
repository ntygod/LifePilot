package com.lifepilot.memory.retrieval;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite FTS5 查询正规化工具。
 *
 * <p>将自由文本、JSON 片段、路径和命令行等脏输入提取为稳定关键词，
 * 避免反引号、括号、路径分隔符等特殊字符直接进入 MATCH 表达式导致语法错误。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public final class SQLiteFtsQueryNormalizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}\\p{L}\\p{Nd}_-]+");
    private static final Set<String> RESERVED_WORDS = Set.of("AND", "OR", "NOT", "NEAR");
    private static final int DEFAULT_MAX_TOKENS = 12;
    private static final int DEFAULT_MAX_TOKEN_LENGTH = 48;

    private SQLiteFtsQueryNormalizer() {
    }

    /**
     * 将原始文本正规化为 FTS5 可接受的 MATCH 查询。
     *
     * @param rawQuery 原始查询文本
     * @return FTS5 查询字符串；若无法提取有效关键词则返回空串
     */
    public static String normalize(String rawQuery) {
        return normalize(rawQuery, DEFAULT_MAX_TOKENS, DEFAULT_MAX_TOKEN_LENGTH);
    }

    /**
     * 将原始文本正规化为 FTS5 可接受的 MATCH 查询。
     *
     * @param rawQuery       原始查询文本
     * @param maxTokens      最多保留的关键词数
     * @param maxTokenLength 单个关键词最大长度
     * @return FTS5 查询字符串；若无法提取有效关键词则返回空串
     */
    public static String normalize(String rawQuery, int maxTokens, int maxTokenLength) {
        if (rawQuery == null || rawQuery.isBlank() || maxTokens <= 0 || maxTokenLength <= 0) {
            return "";
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(rawQuery);
        while (matcher.find() && tokens.size() < maxTokens) {
            String token = sanitizeToken(matcher.group(), maxTokenLength);
            if (token.isBlank()) {
                continue;
            }
            tokens.add("\"" + token.replace("\"", "\"\"") + "\"");
        }

        return String.join(" ", tokens);
    }

    private static String sanitizeToken(String token, int maxTokenLength) {
        if (token == null) {
            return "";
        }
        String sanitized = trimNoise(token.strip());
        if (sanitized.isBlank()) {
            return "";
        }
        if (RESERVED_WORDS.contains(sanitized.toUpperCase(Locale.ROOT))) {
            return "";
        }
        if (sanitized.length() == 1 && isAsciiAlphaNumeric(sanitized.charAt(0))) {
            return "";
        }
        if (sanitized.length() > maxTokenLength) {
            sanitized = sanitized.substring(0, maxTokenLength);
        }
        return sanitized;
    }

    private static String trimNoise(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && isTrimChar(token.charAt(start))) {
            start++;
        }
        while (end > start && isTrimChar(token.charAt(end - 1))) {
            end--;
        }
        return start >= end ? "" : token.substring(start, end);
    }

    private static boolean isTrimChar(char ch) {
        return ch == '_' || ch == '-';
    }

    private static boolean isAsciiAlphaNumeric(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9');
    }
}
