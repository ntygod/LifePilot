package com.lifepilot.skill.builtin;

import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.tool.registry.DynamicToolRegistry;

/**
 * 内置 Skill 提供者接口。
 *
 * <p>每个内置 Skill 实现此接口，提供 Skill 定义并注册该 Skill 的工具到 DynamicToolRegistry。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface BuiltinSkillProvider {

    /**
     * 提供 Skill 定义。
     *
     * @return Skill 定义蓝图
     */
    SkillDefinition provide();

    /**
     * 注册该 Skill 的工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    void registerTools(DynamicToolRegistry toolRegistry);
}
