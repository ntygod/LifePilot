package com.lifepilot.tool.search;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * FTS5 MATCH 查询消毒器。
 *
 * <p>FTS5 的 MATCH 语法有保留字符（`"`、`(`、`)`、`*`）和保留关键字
 * （`AND`、`OR`、`NOT`、`NEAR`），LLM 生成的 query 若无意命中会导致
 * 查询异常。本类把每个 token 加双引号做 phrase search，让保留关键字
 * 被当作普通字符处理。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchQuerySanitizer {

    /**
     * 消毒查询字符串。
     *
     * @param query 原始查询（可为 null / 空）
     * @return FTS5 可安全执行的 MATCH 表达式；输入为空时返回空字符串
     */
    public String sanitize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = query.replaceAll("[\"()*]", " ");
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(t -> !t.isBlank())
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(" "));
    }
}
