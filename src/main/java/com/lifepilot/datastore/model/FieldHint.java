package com.lifepilot.datastore.model;

import org.springframework.lang.Nullable;

/**
 * 字段提示 — 集合的可选元数据字段声明。
 *
 * <p>用于两个用途：(1) 在 documents 表上创建 Generated Column 索引加速结构化查询
 * (2) 为 Agent 提供可查询字段的提示信息。不做写入校验。</p>
 *
 * @param name        字段名，如 "rating"
 * @param type        SQLite 亲和类型：TEXT / NUMBER / BOOLEAN
 * @param description 语义描述，如 "用户对这本书的喜好评分，1-5"
 * @author zsg
 * @since 2026-04-13
 */
public record FieldHint(
        String name,
        String type,
        @Nullable String description
) {

    /** 校验 type 并转换为 SQLite 亲和类型。 */
    public String toSqliteAffinity() {
        return switch (type.toUpperCase()) {
            case "NUMBER" -> "REAL";
            case "BOOLEAN" -> "INTEGER";
            case "TEXT" -> "TEXT";
            default -> throw new IllegalArgumentException(
                    "不支持的字段类型: " + type + "，仅支持 TEXT/NUMBER/BOOLEAN");
        };
    }
}
