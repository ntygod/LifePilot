package com.lifepilot.agent.task.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * 安全 Enum 解析工具 — 避免 valueOf() 遇到脏数据直接崩溃。
 *
 * @author zsg
 * @since 2026-04-14
 */
public final class SafeEnum {

    private static final Logger log = LoggerFactory.getLogger(SafeEnum.class);

    private SafeEnum() {}

    /**
     * 安全解析 Enum，解析失败返回 fallback。
     */
    @Nullable
    public static <T extends Enum<T>> T parse(Class<T> enumType, @Nullable String value, @Nullable T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException e) {
            log.warn("Enum 解析失败: type={}, value={}, fallback={}", enumType.getSimpleName(), value, fallback);
            return fallback;
        }
    }
}
