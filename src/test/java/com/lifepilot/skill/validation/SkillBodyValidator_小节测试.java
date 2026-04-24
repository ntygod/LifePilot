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
    void 齐三必需小节应通过() {
        var body = """
                # X 指南
                ## 适用场景
                - a
                ## 不适用场景
                - b
                ## 工作流
                - c
                """;
        assertThatCode(() -> v.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void 缺少小节应拒绝() {
        var body = "# X\n## 适用场景\n- a\n## 工作流\n- b";
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("不适用场景");
    }

    @Test
    void 超5000字符应拒绝() {
        var body = "# X\n## 适用场景\n" + "a".repeat(5001);
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("≤5000");
    }
}
