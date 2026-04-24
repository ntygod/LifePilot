package com.lifepilot.skill.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SkillDescriptionValidator 硬约束测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillDescriptionValidator_硬约束测试 {

    private final SkillDescriptionValidator v = new SkillDescriptionValidator();

    @Test
    void 中文当时使用开头应通过() {
        assertThatCode(() -> v.validate("当需要创建 PR 时使用。关键词 pr / review"))
                .doesNotThrowAnyException();
    }

    @Test
    void Use_when开头应通过() {
        assertThatCode(() -> v.validate("Use when creating a PR. Keywords pr review"))
                .doesNotThrowAnyException();
    }

    @Test
    void 超过1024字符应拒绝() {
        String s = "当" + "a".repeat(1024);
        assertThatThrownBy(() -> v.validate(s))
                .hasMessageContaining("≤1024");
    }

    @Test
    void 工作流词汇应拒绝() {
        assertThatThrownBy(() -> v.validate("当使用时执行。步骤 1 打开浏览器"))
                .hasMessageContaining("工作流词");
        assertThatThrownBy(() -> v.validate("用于 X。首先打开浏览器"))
                .hasMessageContaining("工作流词");
    }

    @Test
    void 非触发词开头应拒绝() {
        assertThatThrownBy(() -> v.validate("这是一个 github 工具"))
                .hasMessageContaining("开头");
    }
}
