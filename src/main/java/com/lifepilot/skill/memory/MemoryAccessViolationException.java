package com.lifepilot.skill.memory;

import com.lifepilot.skill.SkillException;

/**
 * 记忆访问违规异常。
 *
 * <p>当 SubAgent 尝试读写未声明的记忆层或实体类型时抛出。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class MemoryAccessViolationException extends SkillException {

    public MemoryAccessViolationException(String message) {
        super(message);
    }

    public MemoryAccessViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
