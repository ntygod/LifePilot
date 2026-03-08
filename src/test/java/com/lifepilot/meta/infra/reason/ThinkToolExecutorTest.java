package com.lifepilot.meta.infra.reason;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThinkToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class ThinkToolExecutorTest {

    private ThinkToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThinkToolExecutor();
    }

    @Test
    void execute_正常推理内容_返回确认() {
        String reasoning = "用户需要计算复利，我需要先确认本金、利率和期限";
        ToolInput input = new ToolInput(
                "builtin.reason.think",
                Map.of("reasoning", reasoning),
                JsonSchema.empty(), null
        );

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("status")).isEqualTo("思考已记录");
        assertThat(result.data().get("reasoning")).isEqualTo(reasoning);
        assertThat((int) result.data().get("length")).isEqualTo(reasoning.length());
    }

    @Test
    void execute_空白推理内容_返回错误() {
        ToolInput input = new ToolInput(
                "builtin.reason.think",
                Map.of("reasoning", "   "),
                JsonSchema.empty(), null
        );

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不能为空");
    }

    @Test
    void execute_缺少reasoning参数_返回错误() {
        ToolInput input = new ToolInput(
                "builtin.reason.think",
                Map.of(),
                JsonSchema.empty(), null
        );

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数错误");
    }

    @Test
    void execute_长文本推理_返回正确长度() {
        String reasoning = "A".repeat(10000);
        ToolInput input = new ToolInput(
                "builtin.reason.think",
                Map.of("reasoning", reasoning),
                JsonSchema.empty(), null
        );

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((int) result.data().get("length")).isEqualTo(10000);
    }
}
