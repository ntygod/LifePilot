package com.lifepilot.skill.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SkillBodyValidator 小节结构测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillBodyValidator_小节测试 {

    private final SkillBodyValidator v = new SkillBodyValidator();

    @Test
    void 齐四个v3必需小节应通过() {
        var body = """
                # X 指南
                ## 触发判断
                - a
                ## 决策路径
                - b
                ## 输出标准
                - c
                ## 失败策略
                - d
                """;
        assertThatCode(() -> v.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void 缺少小节应拒绝() {
        var body = "# X\n## 触发判断\n- a\n## 输出标准\n- b\n## 失败策略\n- c";
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("决策路径");
    }

    @Test
    void 超5000字符应拒绝() {
        var body = "# X\n## 触发判断\n" + "a".repeat(5001);
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("≤5000");
    }
}
