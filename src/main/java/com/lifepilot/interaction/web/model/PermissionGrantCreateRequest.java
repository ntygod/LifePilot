package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 手动创建授权请求。
 *
 * @param subjectType 授权主体类型
 * @param subjectId 授权主体标识
 * @param actionType 操作类型
 * @param riskCeiling 风险上限
 * @param scope 资源作用域
 * @param channels 允许的渠道列表
 * @param autonomousAllowed 是否允许自主执行
 * @param expiresAt 过期时间
 * @param createdBy 创建人
 * @param sourceEntryId 来源条目 ID
 * @param reason 授权原因
 * @param metadata 扩展元数据
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionGrantCreateRequest(
        String subjectType,
        String subjectId,
        String actionType,
        String riskCeiling,
        @Nullable Map<String, Object> scope,
        @Nullable List<String> channels,
        boolean autonomousAllowed,
        @Nullable Instant expiresAt,
        @Nullable String createdBy,
        @Nullable String sourceEntryId,
        @Nullable String reason,
        @Nullable Map<String, Object> metadata
) {
}
