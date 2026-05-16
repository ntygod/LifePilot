package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 路径设置更新请求 DTO —— 支持部分更新 HOME、WORKSPACE 和 PathAccessControl 配置。
 *
 * <p>所有字段均为可选，仅提供的字段会被更新。</p>
 *
 * @param home       新的 HOME 目录路径（绝对路径），null 表示不修改
 * @param workspace  新的 WORKSPACE 目录路径（绝对路径），null 表示不修改
 * @param pathAccess 新的路径访问控制配置，null 表示不修改
 * @author zsg
 * @since 2026-06-18
 */
public record PathSettingsRequest(
        @Nullable String home,
        @Nullable String workspace,
        @Nullable PathAccessDto pathAccess
) {

    /**
     * 路径访问控制配置 DTO。
     *
     * @param mode      访问控制模式
     * @param whitelist 白名单路径列表
     * @param blacklist 黑名单路径列表
     */
    public record PathAccessDto(
            @Nullable String mode,
            @Nullable List<String> whitelist,
            @Nullable List<String> blacklist
    ) {}
}
