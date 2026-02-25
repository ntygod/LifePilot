package com.lifepilot.skill;

/**
 * Skill 模块基础异常。
 *
 * <p>所有 Skill 相关异常的父类，继承自 {@link RuntimeException}。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillException extends RuntimeException {

    public SkillException(String message) {
        super(message);
    }

    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
