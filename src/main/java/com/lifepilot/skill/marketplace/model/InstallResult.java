package com.lifepilot.skill.marketplace.model;

import org.springframework.lang.Nullable;

/**
 * Skill 安装结果。
 *
 * @param success              是否安装成功
 * @param skillId              安装成功时的 Skill ID
 * @param securityReport       安全扫描报告
 * @param errorMessage         失败时的错误消息
 * @param requiresConfirmation 是否需要用户确认（HIGH 风险时）
 * @author zsg
 * @since 2026-03-05
 */
public record InstallResult(
        boolean success,
        @Nullable String skillId,
        @Nullable SecurityReport securityReport,
        @Nullable String errorMessage,
        boolean requiresConfirmation
) {}
