package com.lifepilot.skill.builtin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置 Skill 注解 — 标注在 {@link BuiltinSkillProvider} 实现类上。
 *
 * <p>声明 Skill ID 和加载顺序，由 BuiltinSkillRegistrar 在应用启动时扫描并按 order 升序注册。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BuiltinSkill {

    /**
     * Skill 唯一标识。
     *
     * @return Skill ID
     */
    String id();

    /**
     * 加载顺序，值越小越先注册。
     *
     * @return 注册顺序，默认 0
     */
    int order() default 0;
}
