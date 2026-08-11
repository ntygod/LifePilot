package com.lifepilot.memory.semantic;

import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * dueAt 字段格式约束 —— 统一要求 {@code yyyy-MM-dd} 日期字符串。
 *
 * @author zsg
 * @since 2026-06-30
 */
public final class DueAtFormat {

    private DueAtFormat() {
    }

    /**
     * 严格解析 canonical dueAt 日期。
     *
     * @param raw         原始属性值
     * @param errorPrefix 错误前缀，如 "AUDN dueAt" 或 "注意力 dueAt"
     * @param owner       所属对象描述，如 entity 信息
     * @return 解析后的日期
     */
    public static LocalDate requireCanonicalDate(@Nullable Object raw,
                                                 String errorPrefix,
                                                 String owner) {
        if (raw == null) {
            throw new IllegalStateException("%s 不能为空: %s".formatted(errorPrefix, owner));
        }
        if (!(raw instanceof String value)) {
            throw new IllegalStateException("%s 必须是 yyyy-MM-dd 字符串: %s, dueAt=%s"
                    .formatted(errorPrefix, owner, raw));
        }
        if (value.isBlank()) {
            throw new IllegalStateException("%s 不能为空: %s".formatted(errorPrefix, owner));
        }
        if (!value.equals(value.trim())) {
            throw invalid(errorPrefix, owner, value, null);
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) {
                throw invalid(errorPrefix, owner, value, null);
            }
            return parsed;
        } catch (DateTimeParseException ex) {
            throw invalid(errorPrefix, owner, value, ex);
        }
    }

    private static IllegalStateException invalid(String errorPrefix,
                                                 String owner,
                                                 String value,
                                                 @Nullable Throwable cause) {
        var message = "%s 格式非法: %s, dueAt=%s，必须使用 yyyy-MM-dd"
                .formatted(errorPrefix, owner, value);
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
