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
 * GuardrailEngine 单元测试，验证基础设施工具的放行与风险策略行为。
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

    private BuiltinTool 创建基础设施工具(String id, RiskLevel riskLevel) {
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

    private GuardrailResult 检查工具(BuiltinTool tool) {
        engine.addAllowedTools(List.of(tool.id()));
        return engine.checkToolCall(tool, 创建空输入(tool.id()));
    }

    private void 注册ToolRiskPolicy(RiskLevel defaultRiskLevel, Map<String, RiskLevel> mapping) {
        engine.registerPolicy(new ToolRiskPolicy(
                "tool-risk", true, 10, mapping, defaultRiskLevel));
    }

    @Test
    void LOW风险infrastructure工具_跳过策略评估_返回Passed() {
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建基础设施工具("builtin.env.datetime", RiskLevel.LOW);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isEqualTo("infrastructure-low-risk");
    }

    @Test
    void HIGH风险infrastructure工具_仍走策略评估_触发NeedsConfirmation() {
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建基础设施工具("builtin.shell.exec", RiskLevel.HIGH);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        var confirmation = (GuardrailResult.NeedsConfirmation) result;
        assertThat(confirmation.policyId()).isEqualTo("tool-risk");
        assertThat(confirmation.approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    @Test
    void MEDIUM风险infrastructure工具_仍走策略评估() {
        注册ToolRiskPolicy(RiskLevel.MEDIUM, Map.of());

        var tool = 创建基础设施工具("builtin.browser.navigate", RiskLevel.MEDIUM);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isNotEqualTo("infrastructure-low-risk");
    }

    @Test
    void 非infrastructure的LOW风险工具_仍走正常策略评估() {
        注册ToolRiskPolicy(RiskLevel.HIGH, Map.of());

        var tool = 创建普通工具("custom.tool.read", RiskLevel.LOW);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isEqualTo("all_policies");
    }

    @Test
    void 白名单仅解除访问控制_仍沿用infrastructure豁免结果() {
        var tool = 创建基础设施工具("builtin.env.datetime", RiskLevel.LOW);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isEqualTo("infrastructure-low-risk");
    }
}
