package com.lifepilot.skill.activation;

import com.lifepilot.skill.SkillException;

/**
 * Skill 激活异常。
 *
 * <p>在 Skill 激活过程中发生的异常，包括 Skill 不存在、激活深度超限等场景。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillActivationException extends SkillException {

    public SkillActivationException(String message) {
        super(message);
    }

    public SkillActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
