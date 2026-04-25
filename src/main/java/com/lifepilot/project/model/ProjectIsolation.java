package com.lifepilot.project.model;

import java.util.Locale;

/**
 * 项目记忆隔离模式。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ProjectIsolation {
    /** 隔离：写入只落项目 space；读取合并主账户 space。 */
    ISOLATED,
    /** 不隔离：写入路由到主账户 space（合流语义）。 */
    SHARED;

    public static ProjectIsolation defaultValue() {
        return ISOLATED;
    }

    public static ProjectIsolation fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("isolation 不能为 null");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
