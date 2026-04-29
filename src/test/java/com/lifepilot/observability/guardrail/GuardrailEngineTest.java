package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.BuiltinTool;
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
 * GuardrailEngine 单元测试。
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
        engine = new GuardrailEngine(jdbcTemplate, propagator, properties);
    }

    @Test
    void 内容安全策略命中阻断模式_返回Blocked() {
        engine.registerPolicy(new ContentSafetyPolicy(
                "content-safety", true, 1, List.of("危险操作"), List.of()));

        var result = engine.checkInput(null, "请执行危险操作");

        assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        assertThat(((GuardrailResult.Blocked) result).policyId()).isEqualTo("content-safety");
    }

    @Test
    void DataRedaction策略不阻断工具调用() {
        engine.registerPolicy(new DataRedactionPolicy("redaction", true, 1));

        var tool = 创建工具("test.echo");
        var result = engine.checkToolCall(null, tool, 创建空输入(tool.id()));

        assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
    }

    private BuiltinTool 创建工具(String id) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("test")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("business"))
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    private ToolInput 创建空输入(String toolId) {
        return new ToolInput(toolId, Map.of(), JsonSchema.empty(), null, null);
    }
}
