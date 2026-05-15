package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 路径设置响应 DTO —— 返回 HOME、WORKSPACE 和 PathAccessControl 配置。
 *
 * @param home            当前生效的 HOME 目录绝对路径
 * @param workspace       当前生效的 WORKSPACE 目录绝对路径
 * @param pathAccess      路径访问控制配置
 * @param restartRequired 是否需要重启应用（HOME 变更后为 true）
 * @author zsg
 * @since 2026-06-18
 */
public record PathSettingsResponse(
        String home,
        String workspace,
        PathAccessDto pathAccess,
        boolean restartRequired
) {

    /**
     * 路径访问控制配置 DTO。
     *
     * @param mode      访问控制模式：unrestricted / whitelist-only / blacklist-only / whitelist-plus-blacklist
     * @param whitelist 白名单路径列表
     * @param blacklist 黑名单路径列表
     */
    public record PathAccessDto(
            String mode,
            List<String> whitelist,
            List<String> blacklist
    ) {}
}
