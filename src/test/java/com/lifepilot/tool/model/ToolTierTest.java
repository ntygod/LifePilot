package com.lifepilot.tool.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolTier 枚举单元测试。
 *
 * @author zsg
 * @since 2026-03-31
 */
class ToolTierTest {

    @Test
    void 枚举应包含三个分层级别() {
        assertThat(ToolTier.values())
                .containsExactly(ToolTier.CORE, ToolTier.SKILL, ToolTier.DISCOVERY);
    }

    @Test
    void 枚举名称应与预期一致() {
        assertThat(ToolTier.CORE.name()).isEqualTo("CORE");
        assertThat(ToolTier.SKILL.name()).isEqualTo("SKILL");
        assertThat(ToolTier.DISCOVERY.name()).isEqualTo("DISCOVERY");
    }

    @Test
    void valueOf应正确解析字符串() {
        assertThat(ToolTier.valueOf("CORE")).isEqualTo(ToolTier.CORE);
        assertThat(ToolTier.valueOf("SKILL")).isEqualTo(ToolTier.SKILL);
        assertThat(ToolTier.valueOf("DISCOVERY")).isEqualTo(ToolTier.DISCOVERY);
    }
}
