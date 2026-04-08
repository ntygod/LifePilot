package com.lifepilot.datastore.model;

import java.util.regex.Pattern;

/**
 * 字段名校验工具 — 防止动态 SQL 拼接时的注入风险。
 *
 * <p>仅允许以字母或下划线开头、后接字母/数字/下划线的标识符。
 * 在 QueryEngine、AggregationEngine、CollectionRepository 构建 SQL 前调用。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
public final class FieldNames {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,63}");

    private FieldNames() {
    }

    /**
     * 校验字段名是否合法。
     *
     * @param fieldName 字段名
     * @throws IllegalArgumentException 字段名不合法
     */
    public static void validate(String fieldName) {
        if (fieldName == null || !VALID.matcher(fieldName).matches()) {
            throw new IllegalArgumentException(
                    "非法字段名: '%s'，仅允许字母、数字和下划线，且不能以数字开头".formatted(fieldName));
        }
    }
}
