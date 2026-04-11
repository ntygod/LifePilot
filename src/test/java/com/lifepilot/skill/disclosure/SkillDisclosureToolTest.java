package com.lifepilot.skill.disclosure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillDisclosureTool 单元测试。
 *
 * <p>load_skill 工具已移除（改为 file.read skill 参数），原有测试已清理。
 * 新的 Skill 加载测试在 FileReadToolExecutor 和 ReactAgentLoop 层面覆盖。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
class SkillDisclosureToolTest {

    @Test
    void placeholder_防止空测试类报错() {
        assertThat(true).isTrue();
    }
}
