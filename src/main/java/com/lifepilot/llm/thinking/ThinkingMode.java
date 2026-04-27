package com.lifepilot.llm.thinking;

/**
 * 用户在 model_service 上配置的思考模式。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ThinkingMode {
    /** 不下发 thinking 字段，使用 provider 默认行为 */
    AUTO,
    /** 显式开启思考 */
    ENABLED,
    /** 显式关闭思考 */
    DISABLED;

    public static ThinkingMode fromString(String s) {
        if (s == null || s.isBlank()) return AUTO;
        return switch (s.trim().toLowerCase()) {
            case "auto" -> AUTO;
            case "enabled", "on", "true" -> ENABLED;
            case "disabled", "off", "false" -> DISABLED;
            default -> throw new IllegalArgumentException("无效的 thinking_mode: " + s);
        };
    }
}
