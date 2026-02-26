package com.lifepilot.app;

import java.util.Optional;

/**
 * 应用启动模式。
 *
 * <p>通过 {@code --mode=cli|web|tray|full} 命令行参数或
 * {@code lifepilot.app.launch-mode} 配置项控制应用启动行为。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum LaunchMode {

    /** 仅 CLI Shell，禁用 Web 服务器。 */
    CLI,

    /** 仅 Web 服务器，禁用 CLI Shell。 */
    WEB,

    /** 系统托盘 + Web 服务器，禁用 CLI Shell。 */
    TRAY,

    /** CLI + Web（默认）。 */
    FULL;

    /**
     * 从字符串解析启动模式，忽略大小写。
     *
     * @param value 模式字符串
     * @return 对应的枚举值，无效值返回 empty
     */
    public static Optional<LaunchMode> fromString(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
