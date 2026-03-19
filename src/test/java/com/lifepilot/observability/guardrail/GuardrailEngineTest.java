package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GuardrailEngine 单元测试，验证 Infrastructure 审计豁免逻辑。
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(MockitoExtension.class)
class GuardrailEngineTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private GuardrailEngine engine;

    @BeforeEach
    void setUp() {
        var propagator = new TraceContextPropagator(false);
        var properties = new ObservabilityProperties();
        engine = new GuardrailEngine(jdbcTemplate, propagator, properties, new ToolConfigProperties());
    }

    // ─── 辅助方法 ───

    private BuiltinTool 创建Infrastructure工具(String id, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("test")
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    private BuiltinTool 创建普通工具(String id, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("test")
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(List.of("business"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    private ToolInput 创建空输入(String toolId) {
        return new ToolInput(toolId, Map.of(), JsonSchema.empty(), null, null);
    }

    private void 注册ToolRiskPolicy(RiskLevel defaultRiskLevel, Map<String, RiskLevel> mapping) {
        engine.registerPolicy(new ToolRiskPolicy(
                "tool-risk", true, 10, mapping, defaultRiskLevel));
    }

    // ─── Infrastructure 豁免测试 ───

    @Test
    void LOW风险infrastructure工具_跳过策略评估_返回Passed() {
        // 注册一个会对 LOW 风险工具返回 Passed 的策略（但不应被执行到）
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建Infrastructure工具("builtin.env.datetime", RiskLevel.LOW);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        var passed = (GuardrailResult.Passed) result;
        assertThat(passed.policyId()).isEqualTo("infrastructure-low-risk");
    }

    @Test
    void HIGH风险infrastructure工具_仍走策略评估_触发NeedsConfirmation() {
        // 注册 ToolRiskPolicy，defaultRiskLevel = HIGH 会触发 NeedsConfirmation
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建Infrastructure工具("builtin.shell.exec", RiskLevel.HIGH);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        var confirmation = (GuardrailResult.NeedsConfirmation) result;
        assertThat(confirmation.policyId()).isEqualTo("tool-risk");
        assertThat(confirmation.approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    @Test
    void MEDIUM风险infrastructure工具_仍走策略评估() {
        // MEDIUM 风险即 AUTO_WITH_AUDIT 即 Passed（但经过了策略评估）
        注册ToolRiskPolicy(RiskLevel.MEDIUM, Map.of());

        var tool = 创建Infrastructure工具("builtin.browser.navigate", RiskLevel.MEDIUM);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        var passed = (GuardrailResult.Passed) result;
        // 策略评估通过后返回 "all_policies" 或策略 ID，不是 "infrastructure-low-risk"
        assertThat(passed.policyId()).isNotEqualTo("infrastructure-low-risk");
    }

    @Test
    void 非infrastructure的LOW风险工具_仍走正常策略评估() {
        // 注册 ToolRiskPolicy，defaultRiskLevel = HIGH 会触发 NeedsConfirmation
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建普通工具("custom.tool.read", RiskLevel.LOW);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        // 非 infrastructure 工具不享受豁免，走策略评估，HIGH 即 NeedsConfirmation
        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
    }

    @Test
    void 白名单优先于infrastructure豁免() {
        engine.addAllowedTools(List.of("builtin.env.datetime"));

        var tool = 创建Infrastructure工具("builtin.env.datetime", RiskLevel.LOW);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        var passed = (GuardrailResult.Passed) result;
        // 白名单返回 "whitelist"，不是 "infrastructure-low-risk"
        assertThat(passed.policyId()).isEqualTo("whitelist");
    }
}
