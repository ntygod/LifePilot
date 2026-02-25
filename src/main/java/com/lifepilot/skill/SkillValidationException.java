package com.lifepilot.skill;

/**
 * Skill 校验异常。
 *
 * <p>当 Skill 定义校验失败时抛出，包括 ID 格式不合法、字段超长等场景。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillValidationException extends SkillException {

    public SkillValidationException(String message) {
        super(message);
    }

    public SkillValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
