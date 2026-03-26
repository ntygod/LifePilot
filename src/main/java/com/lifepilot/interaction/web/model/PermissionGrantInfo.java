package com.lifepilot.interaction.web.model;

import com.lifepilot.permission.model.ExecutionGrant;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 授权记录响应体。
 *
 * @param id 授权 ID
 * @param subjectType 授权主体类型
 * @param subjectId 授权主体标识
 * @param actionType 操作类型
 * @param riskCeiling 风险上限
 * @param scope 资源作用域
 * @param channels 允许的渠道列表
 * @param autonomousAllowed 是否允许自主执行
 * @param expiresAt 过期时间
 * @param revokedAt 撤销时间
 * @param revokedBy 撤销人
 * @param revokedReason 撤销原因
 * @param createdBy 创建人
 * @param sourceEntryId 来源条目 ID
 * @param reason 授权原因
 * @param metadata 扩展元数据
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @author zsg
 * @since 2026-03-25
 */
public record PermissionGrantInfo(
        String id,
        String subjectType,
        String subjectId,
        String actionType,
        String riskCeiling,
        Map<String, Object> scope,
        List<String> channels,
        boolean autonomousAllowed,
        @Nullable Instant expiresAt,
        @Nullable Instant revokedAt,
        @Nullable String revokedBy,
        @Nullable String revokedReason,
        @Nullable String createdBy,
        @Nullable String sourceEntryId,
        @Nullable String reason,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public static PermissionGrantInfo from(ExecutionGrant grant) {
        return new PermissionGrantInfo(
                grant.id(),
                grant.subjectType().name(),
                grant.subjectId(),
                grant.actionType().name(),
                grant.riskCeiling().name(),
                grant.scope().values(),
                grant.channels(),
                grant.autonomousAllowed(),
                grant.expiresAt(),
                grant.revokedAt(),
                grant.revokedBy(),
                grant.revokedReason(),
                grant.createdBy(),
                grant.sourceEntryId(),
                grant.reason(),
                grant.metadata(),
                grant.createdAt(),
                grant.updatedAt()
        );
    }
}
