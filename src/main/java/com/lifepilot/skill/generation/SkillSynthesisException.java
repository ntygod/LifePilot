package com.lifepilot.skill.generation;

/**
 * Skill 自生成管线最终失败异常。
 *
 * <p>仅在经过 {@link SkillSynthesizer} 首次生成 + 允许的迭代修正次数
 * 仍无法产出合规 SKILL.md 时抛出。原始根因通过 {@link #getCause()} 暴露。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class SkillSynthesisException extends RuntimeException {

    public SkillSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
