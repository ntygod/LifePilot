package com.lifepilot.sync.model;

/**
 * 字段差异 record，描述冲突比较中单个字段的本地值与远程值。
 *
 * @param fieldName   字段名称
 * @param localValue  本地值
 * @param remoteValue 远程值
 * @author zsg
 * @since 2026-02-26
 */
public record FieldDiff(String fieldName, Object localValue, Object remoteValue) {
}
