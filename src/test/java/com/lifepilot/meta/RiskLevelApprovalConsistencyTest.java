package com.lifepilot.meta;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.*;
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
 * 风险等级审批一致性测试。对应 Property 3: 风险等级与审批模式一致性。
 *
 * <p>验证 shell.exec 和 code.execute 在 riskLevel == HIGH 时，
 * GuardrailEngine 对 HIGH 风险工具返回 NeedsConfirmation。
 * 验证 LOW 风险 infrastructure 工具跳过策略评估。</p>
 *
 * <p><b>Validates: Requirements 5.5, 7.4, 15.4</b></p>
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

        // 注册 ToolRiskPolicy：通过 GuardrailEngine.registerPolicy() 公开方法
        // ToolRiskPolicy 是 package-private，但 registerPolicy 接受 GuardrailPolicy
        // 使用反射构造 ToolRiskPolicy，或直接在 guardrail 包内构造
        // 由于 ToolRiskPolicy 不可从外部包访问，改用 GuardrailEngine 的白名单 + 策略机制验证
    }

    // ─── 辅助方法 ───

    private BuiltinTool 创建Infrastructure工具(String id, RiskLevel riskLevel) {
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

    // ─── HIGH 风险工具触发用户确认 ───

    @Test
    void shellExec_HIGH风险_触发NeedsConfirmation() {
        // 通过反射创建 ToolRiskPolicy 并注册
        注册ToolRiskPolicy();

        var tool = 创建Infrastructure工具("builtin.shell.exec", RiskLevel.HIGH);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        var confirmation = (GuardrailResult.NeedsConfirmation) result;
        assertThat(confirmation.approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    @Test
    void codeExecute_HIGH风险_触发NeedsConfirmation() {
        注册ToolRiskPolicy();

        var tool = 创建Infrastructure工具("builtin.code.execute", RiskLevel.HIGH);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.NeedsConfirmation.class);
        var confirmation = (GuardrailResult.NeedsConfirmation) result;
        assertThat(confirmation.approvalMode()).isEqualTo(ApprovalMode.USER_CONFIRM);
    }

    // ─── LOW 风险 infrastructure 工具跳过策略评估 ───

    @Test
    void LOW风险infrastructure工具_跳过策略评估_返回Passed() {
        注册ToolRiskPolicy();

        var tool = 创建Infrastructure工具("builtin.env.datetime", RiskLevel.LOW);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        var passed = (GuardrailResult.Passed) result;
        assertThat(passed.policyId()).isEqualTo("infrastructure-low-risk");
    }

    @Test
    void LOW风险infrastructure工具_userProfile_跳过策略评估() {
        注册ToolRiskPolicy();

        var tool = 创建Infrastructure工具("builtin.env.user-profile", RiskLevel.LOW);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        assertThat(((GuardrailResult.Passed) result).policyId())
                .isEqualTo("infrastructure-low-risk");
    }

    // ─── MEDIUM 风险 infrastructure 工具走完整策略评估 ───

    @Test
    void MEDIUM风险infrastructure工具_走完整策略评估_不跳过() {
        注册ToolRiskPolicy();

        var tool = 创建Infrastructure工具("builtin.browser.navigate", RiskLevel.MEDIUM);
        var result = engine.checkToolCall(tool, 创建空输入(tool.id()));

        // MEDIUM 风险走策略评估，ToolRiskPolicy defaultRiskLevel=LOW 则 AUTO 即 Passed
        // 关键断言：policyId 不是 "infrastructure-low-risk"（说明没有跳过策略评估）
        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        var passed = (GuardrailResult.Passed) result;
        assertThat(passed.policyId()).isNotEqualTo("infrastructure-low-risk");
    }

    // ─── 反射注册 ToolRiskPolicy ───

    private void 注册ToolRiskPolicy() {
        try {
            // ToolRiskPolicy 是 package-private record，通过反射构造
            var clazz = Class.forName("com.lifepilot.observability.guardrail.ToolRiskPolicy");
            var constructor = clazz.getDeclaredConstructors()[0];
            constructor.setAccessible(true);

            // ToolRiskPolicy(policyId, enabled, priority, toolRiskMapping, defaultRiskLevel)
            // shell.exec 和 code.execute 映射为 HIGH，默认 LOW
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
