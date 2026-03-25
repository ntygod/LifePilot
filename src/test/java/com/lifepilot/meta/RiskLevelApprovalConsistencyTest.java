package com.lifepilot.meta;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailPolicy;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.observability.guardrail.RiskLevel;
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
 * 风险等级与审批模式一致性测试。
 *
 * @author zsg
 * @since 2026-03-09
 */
@ExtendWith(MockitoExtension.class)
class RiskLevelApprovalConsistencyTest {

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
                .description("test infrastructure tool")
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(List.of("infrastructure"))
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

    @Test
    void shellExec_HIGH风险_触发NeedsConfirmation() {
        注册ToolRiskPolicy();

        var tool = 创建基础设施工具("builtin.shell.exec", RiskLevel.HIGH);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        assertThat(((GuardrailResult.NeedsConfirmation) result).approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    @Test
    void codeExecute_HIGH风险_触发NeedsConfirmation() {
        注册ToolRiskPolicy();

        var tool = 创建基础设施工具("builtin.code.execute", RiskLevel.HIGH);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        assertThat(((GuardrailResult.NeedsConfirmation) result).approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    @Test
    void LOW风险infrastructure工具_跳过策略评估_返回Passed() {
        注册ToolRiskPolicy();

        var tool = 创建基础设施工具("builtin.env.datetime", RiskLevel.LOW);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isEqualTo("infrastructure-low-risk");
    }

    @Test
    void LOW风险infrastructure工具_userProfile_跳过策略评估() {
        注册ToolRiskPolicy();

        var tool = 创建基础设施工具("builtin.env.user-profile", RiskLevel.LOW);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isEqualTo("infrastructure-low-risk");
    }

    @Test
    void MEDIUM风险infrastructure工具_走完整策略评估_不跳过() {
        注册ToolRiskPolicy();

        var tool = 创建基础设施工具("builtin.browser.navigate", RiskLevel.MEDIUM);
        var result = 检查工具(tool);

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId()).isNotEqualTo("infrastructure-low-risk");
    }

    private void 注册ToolRiskPolicy() {
        try {
            var clazz = Class.forName("com.lifepilot.observability.guardrail.ToolRiskPolicy");
            var constructor = clazz.getDeclaredConstructors()[0];
            constructor.setAccessible(true);

            var policy = constructor.newInstance(
                    "tool-risk-policy",
                    true,
                    1,
                    Map.of(
                            "builtin.shell.exec", RiskLevel.HIGH,
                            "builtin.code.execute", RiskLevel.HIGH
                    ),
                    RiskLevel.LOW
            );

            engine.registerPolicy((GuardrailPolicy) policy);
        } catch (Exception e) {
            throw new RuntimeException("无法通过反射创建 ToolRiskPolicy", e);
        }
    }
}
