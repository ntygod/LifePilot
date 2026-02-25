package com.lifepilot.skill.audit;

/**
 * Skill 审计事件类型。
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum SkillAuditEventType {
    /** Skill 注册。 */
    REGISTERED,
    /** Skill 注销。 */
    UNREGISTERED,
    /** 自生成 Skill 通过验证。 */
    GENERATED,
    /** 用户确认启用自生成 Skill。 */
    CONFIRMED,
    /** 用户拒绝自生成 Skill。 */
    REJECTED,
    /** Skill 被激活执行。 */
    ACTIVATED
}
