package com.lifepilot.workflow.expression;

import com.lifepilot.workflow.model.WorkflowContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExpressionEngine 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class ExpressionEngineTest {

    private ExpressionEngine engine;
    private WorkflowContext context;

    @BeforeEach
    void setUp() {
        engine = new ExpressionEngine();
        context = new WorkflowContext();
        // 设置测试数据
        context.set("inputs.name", "张三");
        context.set("inputs.count", 5);
        context.set("steps.fetch.output.result", "成功");
        context.set("steps.fetch.output.count", 10);
        context.set("steps.check.output.ok", true);
        context.set("steps.check.output.score", 85.5);
        context.set("loopVar", "当前项");
    }

    @Nested
    class resolve方法 {

        @Test
        void 简单变量插值() {
            String result = engine.resolve("你好, ${inputs.name}", context);
            assertEquals("你好, 张三", result);
        }

        @Test
        void 多个变量插值() {
            String result = engine.resolve("${inputs.name}有${inputs.count}个任务", context);
            assertEquals("张三有5个任务", result);
        }

        @Test
        void 嵌套路径访问_步骤输出() {
            String result = engine.resolve("结果: ${steps.fetch.output.result}", context);
            assertEquals("结果: 成功", result);
        }

        @Test
        void 无占位符的字符串原样返回() {
            String result = engine.resolve("普通文本", context);
            assertEquals("普通文本", result);
        }

        @Test
        void null输入返回null() {
            assertNull(engine.resolve(null, context));
        }

        @Test
        void 变量不存在时抛出异常() {
            ExpressionEvaluationException ex = assertThrows(
                    ExpressionEvaluationException.class,
                    () -> engine.resolve("${not.exist.path}", context));
            assertTrue(ex.detail().message().contains("不存在"));
        }

        @Test
        void 顶层变量访问() {
            String result = engine.resolve("当前: ${loopVar}", context);
            assertEquals("当前: 当前项", result);
        }

        @Test
        void 数值变量转为字符串() {
            String result = engine.resolve("数量: ${steps.fetch.output.count}", context);
            assertEquals("数量: 10", result);
        }

        @Test
        void 布尔变量转为字符串() {
            String result = engine.resolve("状态: ${steps.check.output.ok}", context);
            assertEquals("状态: true", result);
        }
    }

    @Nested
    class resolveMap方法 {

        @Test
        void 批量解析String值() {
            Map<String, Object> params = new HashMap<>();
            params.put("user", "${inputs.name}");
            params.put("result", "${steps.fetch.output.result}");

            Map<String, Object> resolved = engine.resolveMap(params, context);
            assertEquals("张三", resolved.get("user"));
            assertEquals("成功", resolved.get("result"));
        }

        @Test
        void 非String值原样保留() {
            Map<String, Object> params = new HashMap<>();
            params.put("text", "${inputs.name}");
            params.put("number", 42);
            params.put("flag", true);

            Map<String, Object> resolved = engine.resolveMap(params, context);
            assertEquals("张三", resolved.get("text"));
            assertEquals(42, resolved.get("number"));
            assertEquals(true, resolved.get("flag"));
        }

        @Test
        void 空Map返回空Map() {
            Map<String, Object> resolved = engine.resolveMap(Map.of(), context);
            assertTrue(resolved.isEmpty());
        }

        @Test
        void null参数返回空Map() {
            Map<String, Object> resolved = engine.resolveMap(null, context);
            assertTrue(resolved.isEmpty());
        }
    }

    @Nested
    class evaluateCondition方法 {

        @Test
        void 数值大于比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.count} > 0", context));
        }

        @Test
        void 数值小于比较() {
            assertFalse(engine.evaluateCondition("${steps.fetch.output.count} < 0", context));
        }

        @Test
        void 数值等于比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.count} == 10", context));
        }

        @Test
        void 数值不等于比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.count} != 5", context));
        }

        @Test
        void 数值大于等于比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.count} >= 10", context));
        }

        @Test
        void 数值小于等于比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.count} <= 10", context));
        }

        @Test
        void 布尔值等于true() {
            assertTrue(engine.evaluateCondition("${steps.check.output.ok} == true", context));
        }

        @Test
        void 布尔值等于false() {
            assertFalse(engine.evaluateCondition("${steps.check.output.ok} == false", context));
        }

        @Test
        void 逻辑与运算() {
            assertTrue(engine.evaluateCondition(
                    "${steps.check.output.ok} == true && ${steps.fetch.output.count} > 0", context));
        }

        @Test
        void 逻辑或运算() {
            assertTrue(engine.evaluateCondition(
                    "${steps.check.output.ok} == false || ${steps.fetch.output.count} > 0", context));
        }

        @Test
        void 逻辑非运算() {
            assertFalse(engine.evaluateCondition("!true", context));
            assertTrue(engine.evaluateCondition("!false", context));
        }

        @Test
        void 字符串字面量比较() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.result} == '成功'", context));
        }

        @Test
        void 字符串字面量不等于() {
            assertTrue(engine.evaluateCondition("${steps.fetch.output.result} != '失败'", context));
        }

        @Test
        void 浮点数比较() {
            assertTrue(engine.evaluateCondition("${steps.check.output.score} > 80", context));
            assertFalse(engine.evaluateCondition("${steps.check.output.score} > 90", context));
        }

        @Test
        void 括号表达式() {
            assertTrue(engine.evaluateCondition(
                    "(${steps.check.output.ok} == true) && (${steps.fetch.output.count} > 5)", context));
        }

        @Test
        void 空条件抛出异常() {
            assertThrows(ExpressionEvaluationException.class,
                    () -> engine.evaluateCondition("", context));
        }

        @Test
        void null条件抛出异常() {
            assertThrows(ExpressionEvaluationException.class,
                    () -> engine.evaluateCondition(null, context));
        }

        @Test
        void 未闭合字符串字面量抛出异常() {
            assertThrows(ExpressionEvaluationException.class,
                    () -> engine.evaluateCondition("'unclosed", context));
        }

        @Test
        void 缺少右括号抛出异常() {
            assertThrows(ExpressionEvaluationException.class,
                    () -> engine.evaluateCondition("(true", context));
        }

        @Test
        void 负数比较() {
            assertTrue(engine.evaluateCondition("-1 < 0", context));
        }

        @Test
        void 复合逻辑表达式() {
            assertTrue(engine.evaluateCondition(
                    "${steps.check.output.ok} == true && ${steps.fetch.output.count} >= 10 || false", context));
        }
    }
}
