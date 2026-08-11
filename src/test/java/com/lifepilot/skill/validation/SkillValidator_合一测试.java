package com.lifepilot.skill.validation;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillValidator 合一校验测试。
 *
 * <p>覆盖 9 用例：合法路径 / description 违规 / body 违规 /
 * 两类 secret 模式 / 预置路径 WARN / 自生成路径三种失败 + 通过。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillValidator_合一测试 {

    @Mock DynamicToolRegistry toolRegistry;

    SkillValidator validator;
    MarkdownSkillParser parser;

    @BeforeEach
    void setup() {
        parser = new MarkdownSkillParser();
        validator = new SkillValidator(
                new SkillDescriptionValidator(),
                new SkillBodyValidator(),
                toolRegistry);
    }

    @Test
    void 合法skill应通过() {
        var parsed = parser.parse(validMd("valid", List.of()));
        assertThatCode(() -> validator.validate(parsed)).doesNotThrowAnyException();
    }

    @Test
    void description违规应拒绝() {
        var parsed = parser.parse(validMd("bad-desc", List.of()).replace("当需要", "描述"));
        assertThatThrownBy(() -> validator.validate(parsed))
                .hasMessageContaining("开头");
    }

    @Test
    void body违规应拒绝_缺少必需小节() {
        var parsed = new MarkdownSkillParser.ParsedSkill(
                parser.parse(validMd("x", List.of())).frontmatter(),
                "## 触发判断\n- only this\n");
        assertThatThrownBy(() -> validator.validate(parsed)).hasMessageContaining("小节");
    }

    @Test
    void 身体命中API_key模式应拒绝() {
        String md = validMd("secret", List.of())
                .replace("## 决策路径\n1. do",
                        "## 决策路径\n1. 使用 api_key=\"sk-abc12345678901234567890123456789012345678901234\"");
        var parsed = parser.parse(md);
        assertThatThrownBy(() -> validator.validate(parsed)).hasMessageContaining("secret");
    }

    @Test
    void 身体命中PRIVATE_KEY模式应拒绝() {
        String md = validMd("pk", List.of())
                .replace("## 决策路径\n1. do",
                        "## 决策路径\n1. 参考 -----BEGIN RSA PRIVATE KEY-----");
        var parsed = parser.parse(md);
        assertThatThrownBy(() -> validator.validate(parsed)).hasMessageContaining("secret");
    }

    @Test
    void 预置路径对未知工具只WARN不阻断() {
        lenient().when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
        var parsed = parser.parse(validMd("warn", List.of("unknown.tool")));
        assertThatCode(() -> validator.validate(parsed)).doesNotThrowAnyException();
    }

    @Test
    void 预置路径对核心工具目录不依赖运行时注册表() {
        var parsed = parser.parse(validMd("canonical", List.of("shell.exec", "memory", "ui.render")));

        assertThatCode(() -> validator.validate(parsed)).doesNotThrowAnyException();

        verify(toolRegistry, never()).resolve(anyString());
    }

    @Test
    void 自生成路径对未知工具应ERROR() {
        when(toolRegistry.resolve("unknown.tool")).thenReturn(Optional.empty());
        var parsed = parser.parse(validMd("gen-bad", List.of("unknown.tool")));
        assertThatThrownBy(() -> validator.validateGenerated(parsed))
                .hasMessageContaining("未知工具");
    }

    @Test
    void 自生成路径对未注册核心工具应ERROR() {
        when(toolRegistry.resolve("ui.render")).thenReturn(Optional.empty());
        var parsed = parser.parse(validMd("gen-missing-core", List.of("ui.render")));

        assertThatThrownBy(() -> validator.validateGenerated(parsed))
                .hasMessageContaining("当前不可用工具")
                .hasMessageContaining("ui.render");
    }

    @Test
    void 自生成路径对HIGH风险工具应ERROR() {
        var highRiskTool = fakeTool(RiskLevel.HIGH);
        when(toolRegistry.resolve("shell.exec")).thenReturn(Optional.of(highRiskTool));
        var parsed = parser.parse(validMd("gen-shell", List.of("shell.exec")));
        assertThatThrownBy(() -> validator.validateGenerated(parsed))
                .hasMessageContaining("HIGH");
    }

    @Test
    void 自生成路径对LOW风险工具应通过() {
        var lowRiskTool = fakeTool(RiskLevel.LOW);
        when(toolRegistry.resolve("todo.list")).thenReturn(Optional.of(lowRiskTool));
        var parsed = parser.parse(validMd("gen-ok", List.of("todo.list")));
        assertThatCode(() -> validator.validateGenerated(parsed)).doesNotThrowAnyException();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /** 生成一份最小合法 SKILL.md（frontmatter + v3 四必需小节 + 可选 suggested_tools）。 */
    private String validMd(String name, List<String> suggestedTools) {
        String tools = suggestedTools.isEmpty() ? "" :
                "\n    suggested_tools: [" + String.join(", ", suggestedTools) + "]";
        return """
                ---
                name: %s
                description: 当需要测试时使用。关键词 test
                version: 1.0.0
                metadata:
                  zhiwei:%s
                    outputs:
                      - text
                ---
                ## 触发判断
                - a
                ## 决策路径
                1. do
                ## 输出标准
                - b
                ## 失败策略
                - c
                """.formatted(name, tools);
    }

    /** 造一个最小可用的 BuiltinTool，按 risk level 区分，只供 registry.resolve 返回。 */
    private BuiltinTool fakeTool(RiskLevel risk) {
        return BuiltinTool.builder()
                .id("fake")
                .name("fake")
                .description("fake tool for test")
                .tags(List.of("test", "fake", "mock"))
                .category(ToolCategory.COGNITION)
                .riskLevel(risk)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(i -> ToolResult.success(Map.of()))
                .build();
    }
}
