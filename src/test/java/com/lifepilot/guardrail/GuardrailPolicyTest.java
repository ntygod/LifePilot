package com.lifepilot.guardrail;

import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GuardrailPolicy 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class GuardrailPolicyTest {

    private GuardrailPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new GuardrailPolicy();
    }

    @Test
    void 白名单工具_允许执行() {
        policy.addAllowedTools(List.of("test.echo"));
        ToolContract tool = createTool("test.echo", RiskLevel.LOW);
        ToolInput input = new ToolInput("test.echo", Map.of(), JsonSchema.empty(), null);

        GuardrailResult result = policy.checkToolCall(tool, input);
        assertFalse(result.blocked());
        assertFalse(result.requiresConfirmation());
    }

    @Test
    void 非白名单工具_拦截() {
        ToolContract tool = createTool("test.unknown", RiskLevel.LOW);
        ToolInput input = new ToolInput("test.unknown", Map.of(), JsonSchema.empty(), null);

        GuardrailResult result = policy.checkToolCall(tool, input);
        assertTrue(result.blocked());
    }

    @Test
    void 黑名单优先于白名单() {
        policy.addAllowedTools(List.of("test.danger"));
        policy.addBlockedTools(List.of("test.danger"));
        ToolContract tool = createTool("test.danger", RiskLevel.LOW);
        ToolInput input = new ToolInput("test.danger", Map.of(), JsonSchema.empty(), null);

        GuardrailResult result = policy.checkToolCall(tool, input);
        assertTrue(result.blocked());
    }

    @Test
    void HIGH风险_需要用户确认() {
        policy.addAllowedTools(List.of("test.high"));
        ToolContract tool = createTool("test.high", RiskLevel.HIGH);
        ToolInput input = new ToolInput("test.high", Map.of(), JsonSchema.empty(), null);

        GuardrailResult result = policy.checkToolCall(tool, input);
        assertFalse(result.blocked());
        assertTrue(result.requiresConfirmation());
        assertFalse(result.requiresVerification());
    }

    @Test
    void CRITICAL风险_需要二次验证() {
        policy.addAllowedTools(List.of("test.critical"));
        ToolContract tool = createTool("test.critical", RiskLevel.CRITICAL);
        ToolInput input = new ToolInput("test.critical", Map.of(), JsonSchema.empty(), null);

        GuardrailResult result = policy.checkToolCall(tool, input);
        assertFalse(result.blocked());
        assertTrue(result.requiresConfirmation());
        assertTrue(result.requiresVerification());
    }

    @Test
    void 移除白名单后_工具被拦截() {
        policy.addAllowedTools(List.of("test.echo"));
        ToolContract tool = createTool("test.echo", RiskLevel.LOW);
        ToolInput input = new ToolInput("test.echo", Map.of(), JsonSchema.empty(), null);
        assertFalse(policy.checkToolCall(tool, input).blocked());

        policy.removeAllowedTools(List.of("test.echo"));
        assertTrue(policy.checkToolCall(tool, input).blocked());
    }

    private ToolContract createTool(String id, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id).name(id).description("测试工具")
                .inputSchema(JsonSchema.empty()).outputSchema(JsonSchema.empty())
                .riskLevel(riskLevel).idempotent(true)
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }
}
