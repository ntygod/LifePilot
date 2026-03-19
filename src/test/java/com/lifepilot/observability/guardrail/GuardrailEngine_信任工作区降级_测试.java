package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信任工作区风险降级单元测试。
 *
 * <p>验证在信任目录下 shell.exec / code.execute 的风险等级从 HIGH 降级为 MEDIUM，
 * 以及不匹配、空配置、非目标工具等场景的正确行为。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@ExtendWith(MockitoExtension.class)
class GuardrailEngine_信任工作区降级_测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    // ─── 辅助方法 ───

    private GuardrailEngine 创建引擎(ToolConfigProperties toolConfig) {
        var propagator = new TraceContextPropagator(false);
        var properties = new ObservabilityProperties();
        var engine = new GuardrailEngine(jdbcTemplate, propagator, properties, toolConfig);
        // 注册 ToolRiskPolicy：shell.exec 和 code.execute 映射为 HIGH
        engine.registerPolicy(new ToolRiskPolicy(
                "tool-risk", true, 10,
                Map.of("builtin.shell.exec", RiskLevel.HIGH,
                        "builtin.code.execute", RiskLevel.HIGH),
                RiskLevel.LOW));
        return engine;
    }

    private BuiltinTool 创建工具(String id, RiskLevel riskLevel, List<String> tags) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("test")
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    private ToolInput 创建带路径输入(String toolId, String cwd) {
        return new ToolInput(toolId, Map.of("cwd", cwd), JsonSchema.empty(), null, null);
    }

    private ToolInput 创建空输入(String toolId) {
        return new ToolInput(toolId, Map.of(), JsonSchema.empty(), null, null);
    }

    private ToolConfigProperties 创建信任配置(List<String> paths) {
        var config = new ToolConfigProperties();
        var tw = new ToolConfigProperties.TrustedWorkspace();
        tw.setPaths(paths);
        tw.setDowngradeLevel("MEDIUM");
        config.setTrustedWorkspace(tw);
        return config;
    }

    // ─── 匹配降级测试 ───

    @Test
    void shellExec_在信任目录下_降级为MEDIUM_返回Passed() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), trustedPath);
        var result = engine.checkToolCall(tool, input);

        // HIGH 降级为 MEDIUM → AUTO_WITH_AUDIT → Passed
        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }

    @Test
    void codeExecute_在信任子目录下_降级为MEDIUM_返回Passed() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        String subDir = tempDir.resolve("subproject").toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        var tool = 创建工具("builtin.code.execute", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), subDir);
        var result = engine.checkToolCall(tool, input);

        // 子目录也匹配信任路径
        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }

    // ─── 不匹配保持测试 ───

    @Test
    void shellExec_不在信任目录下_保持HIGH_返回NeedsConfirmation() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), "/some/other/path");
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        var confirmation = (GuardrailResult.NeedsConfirmation) result;
        assertThat(confirmation.approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    // ─── 空配置测试 ───

    @Test
    void 空信任路径配置_不降级_保持原风险等级() {
        var engine = 创建引擎(创建信任配置(List.of()));

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), tempDir.toAbsolutePath().toString());
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
    }

    @Test
    void 默认ToolConfigProperties_不降级() {
        // 默认配置 trustedWorkspace.paths 为空
        var engine = 创建引擎(new ToolConfigProperties());

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), tempDir.toAbsolutePath().toString());
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
    }

    // ─── 非目标工具不降级测试 ───

    @Test
    void 非目标工具_即使在信任目录下_不降级() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        // browser.navigate 不在 TRUSTED_WORKSPACE_TOOLS 白名单中
        var tool = 创建工具("builtin.browser.navigate", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建带路径输入(tool.id(), trustedPath);
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
    }

    // ─── 无路径参数测试 ───

    @Test
    void 无路径参数_不降级() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = 创建空输入(tool.id());
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
    }

    // ─── workingDirectory 参数名测试 ───

    @Test
    void workingDirectory参数名_也能匹配降级() {
        String trustedPath = tempDir.toAbsolutePath().toString();
        var engine = 创建引擎(创建信任配置(List.of(trustedPath)));

        var tool = 创建工具("builtin.shell.exec", RiskLevel.HIGH, List.of("infrastructure"));
        var input = new ToolInput(tool.id(),
                Map.of("workingDirectory", trustedPath),
                JsonSchema.empty(), null, null);
        var result = engine.checkToolCall(tool, input);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }
}
