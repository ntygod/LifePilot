package com.lifepilot.skill.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TemplateActionExecutor} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class TemplateActionExecutorTest {

    private TemplateActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TemplateActionExecutor(new VariableResolver());
    }

    @Test
    void 模板渲染_正常替换result变量() {
        var action = new SkillAction.TemplateAction("你好, ${result.name}! 你的分数是 ${result.score}");
        var previousResult = Map.<String, Object>of("name", "张三", "score", 95);

        ActionResult result = executor.execute(action, previousResult);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("你好, 张三! 你的分数是 95");
    }

    @Test
    void 模板渲染_previousResult为null时保留占位符() {
        var action = new SkillAction.TemplateAction("结果: ${result.value}");

        ActionResult result = executor.execute(action, null);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("结果: ${result.value}");
    }

    @Test
    void 模板渲染_previousResult中键不存在时保留占位符() {
        var action = new SkillAction.TemplateAction("结果: ${result.missing}");
        var previousResult = Map.<String, Object>of("other", "value");

        ActionResult result = executor.execute(action, previousResult);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("结果: ${result.missing}");
    }

    @Test
    void 模板渲染_无占位符时原样返回() {
        var action = new SkillAction.TemplateAction("纯文本内容，无变量");

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("纯文本内容，无变量");
    }

    @Test
    void 模板渲染_模板为null时返回错误() {
        var action = new SkillAction.TemplateAction(null);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("模板内容为空");
    }

    @Test
    void 模板渲染_模板为空白字符串时返回错误() {
        var action = new SkillAction.TemplateAction("   ");

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEqualTo("模板内容为空");
    }

    @Test
    void 模板渲染_多个result变量混合替换() {
        var action = new SkillAction.TemplateAction(
                "任务: ${result.task}, 状态: ${result.status}, 备注: ${result.note}");
        var previousResult = Map.<String, Object>of(
                "task", "代码审查",
                "status", "已完成",
                "note", "无问题");

        ActionResult result = executor.execute(action, previousResult);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("任务: 代码审查, 状态: 已完成, 备注: 无问题");
    }
}
