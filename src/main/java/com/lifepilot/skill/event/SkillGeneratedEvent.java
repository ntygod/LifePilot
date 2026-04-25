package com.lifepilot.skill.event;

import com.lifepilot.skill.install.SkillSourceType;

import java.time.Instant;

/**
 * Skill 自动生成完成事件。
 *
 * <p>由 {@link com.lifepilot.skill.generation.SkillSynthesizer} 在自生成 SKILL.md
 * 经过严格校验并成功落库（{@code source_type = AUTO_GENERATED}）后，
 * 通过 {@link org.springframework.context.ApplicationEventPublisher} 发布。</p>
 *
 * <p>C.3 阶段事件内容最小化：仅 name + 来源类型 + 发生时间；
 * C.5 将新增 SSE 广播监听器，不改此 record 定义。</p>
 *
 * @param skillName  新落库 Skill 的 name（即 skills 表主键）
 * @param sourceType 来源类型（自生成路径必为 {@link SkillSourceType#AUTO_GENERATED}）
 * @param at         发生时间戳（UTC）
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillGeneratedEvent(String skillName, SkillSourceType sourceType, Instant at) {

    public SkillGeneratedEvent {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName 不能为空");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType 不能为空");
        }
        if (at == null) {
            throw new IllegalArgumentException("at 不能为空");
        }
    }
}
