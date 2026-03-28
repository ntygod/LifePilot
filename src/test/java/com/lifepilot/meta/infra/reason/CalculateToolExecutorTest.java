package com.lifepilot.meta.infra.reason;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CalculateToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class CalculateToolExecutorTest {

    private CalculateToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CalculateToolExecutor();
    }

    // ─────────────────────────────────────────────
    //  四则运算
    // ─────────────────────────────────────────────

    @Test
    void execute_加法() {
        ToolResult result = calculate("123.45 + 67.89");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("191.34");
        assertThat(result.data().get("type")).isEqualTo("算术");
    }

    @Test
    void execute_减法() {
        ToolResult result = calculate("100 - 37.5");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("62.5");
    }

    @Test
    void execute_乘法() {
        ToolResult result = calculate("100 * 0.15");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("15");
    }

    @Test
    void execute_除法() {
        ToolResult result = calculate("100 / 3");

        assertThat(result.ok()).isTrue();
        // BigDecimal 精确除法，6 位精度
        assertThat(result.data().get("result").toString()).startsWith("33.333");
    }

    @Test
    void execute_除以零_返回错误() {
        ToolResult result = calculate("100 / 0");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("除数不能为零");
    }

    // ─────────────────────────────────────────────
    //  百分比
    // ─────────────────────────────────────────────

    @Test
    void execute_百分比_数值在前() {
        ToolResult result = calculate("200 * 15%");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("30");
        assertThat(result.data().get("type")).isEqualTo("百分比");
    }

    @Test
    void execute_百分比_百分号在前() {
        ToolResult result = calculate("15% * 200");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("30");
    }

    // ─────────────────────────────────────────────
    //  日期差
    // ─────────────────────────────────────────────

    @Test
    void execute_日期差计算() {
        ToolResult result = calculate("2026-03-08 - 2025-01-01");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("431");
        assertThat(result.data().get("unit")).isEqualTo("天");
        assertThat(result.data().get("type")).isEqualTo("日期差");
    }

    @Test
    void execute_日期差_负数() {
        ToolResult result = calculate("2025-01-01 - 2026-03-08");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("-431");
    }

    @Test
    void execute_日期差_同一天() {
        ToolResult result = calculate("2026-03-08 - 2026-03-08");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("0");
    }

    // ─────────────────────────────────────────────
    //  BigDecimal 精度
    // ─────────────────────────────────────────────

    @Test
    void execute_BigDecimal精度_浮点数加法() {
        // 经典浮点精度问题：0.1 + 0.2 != 0.3 in double
        ToolResult result = calculate("0.1 + 0.2");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("result")).isEqualTo("0.3");
    }

    // ─────────────────────────────────────────────
    //  错误场景
    // ─────────────────────────────────────────────

    @Test
    void execute_无效表达式_返回错误() {
        ToolResult result = calculate("hello world");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("无法解析表达式");
    }

    @Test
    void execute_空表达式_返回错误() {
        ToolResult result = calculate("   ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不能为空");
    }

    @Test
    void execute_缺少expression参数_返回错误() {
        ToolInput input = new ToolInput(
                "reason.calculate",
                Map.of(),
                JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数错误");
    }

    @Test
    void execute_无效日期格式_返回错误() {
        ToolResult result = calculate("2026-13-08 - 2025-01-01");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("日期格式错误");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ToolResult calculate(String expression) {
        ToolInput input = new ToolInput(
                "reason.calculate",
                Map.of("expression", expression),
                JsonSchema.empty(), null, null);
        return executor.execute(input);
    }
}
