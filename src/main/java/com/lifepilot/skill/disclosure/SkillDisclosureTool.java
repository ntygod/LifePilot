package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skill 披露工具 — 保留类结构，load_skill 已迁移至 file.read(skill=...) 自动激活。
 *
 * <p>load_skill 工具注册逻辑已删除，Skill 加载现由 ReactAgentLoop 通过
 * file.read 的 skill 参数自动触发 SkillActivator，无需独立工具。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class SkillDisclosureTool {

    private static final Logger log = LoggerFactory.getLogger(SkillDisclosureTool.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillActivator skillActivator;
    private final SkillRegistry skillRegistry;

    public SkillDisclosureTool(DynamicToolRegistry toolRegistry,
                               SkillActivator skillActivator,
                               SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.skillActivator = skillActivator;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 注册工具 — load_skill 已移除，当前为空实现。
     *
     * <p>保留方法签名以兼容 SkillAutoConfiguration 中的调用点。</p>
     */
    public void registerTools() {
        log.debug("SkillDisclosureTool.registerTools() — load_skill 已迁移至 file.read(skill=...)，跳过注册");
    }
}
