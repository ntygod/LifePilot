package com.lifepilot.skill.audit;

import org.springframework.lang.Nullable;

/**
 * Skill 审计事件。
 *
 * @param id          事件 ID（UUID）
 * @param skillId     Skill ID
 * @param eventType   事件类型
 * @param eventDetail 事件详情（JSON 字符串）
 * @param sourceType  来源类型（BUILTIN/USER_DEFINED/AUTO_GENERATED）
 * @param operator    操作者
 * @param createdAt   创建时间（ISO 8601）
 * @author zsg
 * @since 2026-02-25
 */
public record SkillAuditEvent(
        String id,
        String skillId,
        SkillAuditEventType eventType,
        @Nullable String eventDetail,
        String sourceType,
        String operator,
        String createdAt
) {}
