package com.lifepilot.config.bootstrap;

import java.util.List;

/**
 * 路径访问控制配置数据模型。
 *
 * <p>存储在 {@code ~/zhiwei/config.json} 的 {@code pathAccess} 字段中，
 * 定义 Agent 可访问的本机目录权限规则。</p>
 *
 * @param mode      访问控制模式：unrestricted / whitelist-only / blacklist-only / whitelist-plus-blacklist
 * @param whitelist 白名单路径列表
 * @param blacklist 黑名单路径列表
 * @author zsg
 * @since 2026-06-15
 */
public record PathAccessConfig(
        String mode,
        List<String> whitelist,
        List<String> blacklist
) {

    /** 不限制模式（默认） */
    public static final String MODE_UNRESTRICTED = "unrestricted";
    /** 仅白名单模式 */
    public static final String MODE_WHITELIST_ONLY = "whitelist-only";
    /** 仅黑名单模式 */
    public static final String MODE_BLACKLIST_ONLY = "blacklist-only";
    /** 白名单 + 黑名单模式（黑名单优先） */
    public static final String MODE_WHITELIST_PLUS_BLACKLIST = "whitelist-plus-blacklist";

    /**
     * 创建默认配置（不限制模式，空白名单/黑名单）。
     */
    public static PathAccessConfig defaultConfig() {
        return new PathAccessConfig(MODE_UNRESTRICTED, List.of(), List.of());
    }
}
