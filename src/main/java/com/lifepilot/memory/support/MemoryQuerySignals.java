package com.lifepilot.memory.support;

import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 记忆查询中的高信号文本提取与精确匹配评分。
 *
 * <p>编号型标记（如 MT-CANCEL-0507、RQ-7391）对记忆操作有强指向性，不能被
 * 语义向量排序和重要度加权淹没。本工具把这类标记提升为显式查询信号，供 FTS、
 * L2 recall 和 cancel 复用。</p>
 *
 * @author zsg
 * @since 2026-05-07
 */
public final class MemoryQuerySignals {

    private static final Pattern MARKER_PATTERN = Pattern.compile(
            "\\b[A-Z]{2,}(?:-[A-Z0-9]+)+\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_LOOKUP_TERMS = 8;
    private static final int MAX_PHRASE_LOOKUP_LENGTH = 80;

    private MemoryQuerySignals() {
    }

    /**
     * 提取编号型高信号标记，保持出现顺序并去重。
     */
    public static List<String> highSignalTerms(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        var matcher = MARKER_PATTERN.matcher(query);
        while (matcher.find() && terms.size() < MAX_LOOKUP_TERMS) {
            String term = matcher.group();
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * 构造适合 SQL LIKE 精确兜底的查询项。
     *
     * <p>编号标记优先；短句查询额外保留整句，覆盖"下午5点检查日志"这类无编号场景。
     * 长自然语言请求不作为整句 LIKE 条件，避免过窄导致漏召回。</p>
     */
    public static List<String> lookupTerms(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>(highSignalTerms(query));
        String trimmed = query.trim();
        if (!trimmed.isBlank() && trimmed.length() <= MAX_PHRASE_LOOKUP_LENGTH) {
            terms.add(trimmed);
        }
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .limit(MAX_LOOKUP_TERMS)
                .toList();
    }

    /**
     * 转为 SQLite LIKE 参数，使用反斜杠转义通配符。
     */
    public static String likePattern(String term) {
        return "%" + escapeLike(term) + "%";
    }

    /**
     * 对实体名称/描述与查询的精确文本相关性打分。
     */
    public static float textMatchScore(@Nullable String query,
                                       @Nullable String name,
                                       @Nullable String description) {
        if (query == null || query.isBlank()) {
            return 0.0f;
        }
        String normalizedName = normalize(name);
        String normalizedDescription = normalize(description);
        if (normalizedName.isBlank() && normalizedDescription.isBlank()) {
            return 0.0f;
        }

        float score = 0.0f;
        String normalizedQuery = normalize(query);
        if (!normalizedQuery.isBlank() && normalizedQuery.length() <= MAX_PHRASE_LOOKUP_LENGTH) {
            if (normalizedName.equals(normalizedQuery)) {
                score = Math.max(score, 5.0f);
            } else if (normalizedName.contains(normalizedQuery)) {
                score = Math.max(score, 3.2f);
            }
            if (normalizedDescription.contains(normalizedQuery)) {
                score = Math.max(score, 2.6f);
            }
        }

        for (String term : highSignalTerms(query)) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (normalizedName.equals(normalizedTerm)) {
                score = Math.max(score, 5.0f);
            } else if (normalizedName.startsWith(normalizedTerm)) {
                score = Math.max(score, 4.8f);
            } else if (normalizedName.contains(normalizedTerm)) {
                score = Math.max(score, 4.5f);
            }
            if (normalizedDescription.contains(normalizedTerm)) {
                score = Math.max(score, 4.0f);
            }
        }
        return score;
    }

    /**
     * 基于查询项构造 SQL OR 条件占位。
     */
    public static String likeWhereClause(String columnExpression, int termCount) {
        if (termCount <= 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < termCount; i++) {
            parts.add(columnExpression + " LIKE ? ESCAPE '\\'");
        }
        return String.join(" OR ", parts);
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String normalize(@Nullable String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
