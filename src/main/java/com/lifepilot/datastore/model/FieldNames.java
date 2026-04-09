package com.lifepilot.datastore.model;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 字段名与标识符校验工具 — 防止动态 SQL 拼接时的注入风险。
 *
 * <p>提供两类校验：</p>
 * <ul>
 *   <li>{@link #validate(String)} — 字段名白名单（字母/数字/下划线）</li>
 *   <li>{@link #validateId(String)} — UUID 格式校验（十六进制/连字符）</li>
 * </ul>
 * <p>在 QueryEngine、AggregationEngine、CollectionRepository 构建 SQL 前调用。</p>
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

    /**
     * 校验 ID 是否为合法 UUID 格式 — 防止 collectionId 等参与 SQL 拼接时的注入风险。
     *
     * @param id 待校验的 ID
     * @throws IllegalArgumentException ID 不是合法 UUID
     */
    public static void validateId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("ID 不能为 null");
        }
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "非法 ID: '%s'，期望 UUID 格式".formatted(id));
        }
    }

    /**
     * 生成集合 ID 的安全前缀 — 用于 Generated Column 和索引命名。
     *
     * @param collectionId 集合 ID
     * @return 截取前 8 字符的安全前缀
     */
    public static String safePrefix(String collectionId) {
        return collectionId.length() >= 8 ? collectionId.substring(0, 8) : collectionId;
    }
}
