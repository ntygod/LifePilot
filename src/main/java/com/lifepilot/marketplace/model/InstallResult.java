package com.lifepilot.marketplace.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 扩展安装结果。
 *
 * @param success              是否安装成功
 * @param extensionId          安装成功时的扩展 ID
 * @param extensionType        扩展类型
 * @param securityReport       安全扫描报告
 * @param requirements         前置条件说明列表
 * @param errorMessage         失败时的错误消息
 * @param requiresConfirmation 是否需要用户确认（HIGH 风险时）
 * @author zsg
 * @since 2026-03-08
 */
public record InstallResult(
        boolean success,
        @Nullable String extensionId,
        @Nullable ExtensionType extensionType,
        @Nullable SecurityReport securityReport,
        @Nullable List<String> requirements,
        @Nullable String errorMessage,
        boolean requiresConfirmation
) {}
