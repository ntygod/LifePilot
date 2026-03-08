package com.lifepilot.meta.infra.reason;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算工具执行器 — 使用 BigDecimal 进行精确算术运算。
 *
 * <p>支持四则运算、百分比计算和日期差计算。表达式格式：</p>
 * <ul>
 *   <li>四则运算：{@code 123.45 + 67.89}、{@code 100 * 0.15}</li>
 *   <li>百分比：{@code 200 * 15%}、{@code 15% of 200}</li>
 *   <li>日期差：{@code 2026-03-08 - 2025-01-01}（返回天数）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class CalculateToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(CalculateToolExecutor.class);

    /** 日期差表达式：yyyy-MM-dd - yyyy-MM-dd */
    private static final Pattern DATE_DIFF_PATTERN = Pattern.compile(
            "^\\s*(\\d{4}-\\d{2}-\\d{2})\\s*-\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$"
    );

    /** 百分比表达式：number * number% 或 number% * number */
    private static final Pattern PERCENT_PATTERN = Pattern.compile(
            "^\\s*([\\d.]+)\\s*\\*\\s*([\\d.]+)%\\s*$|^\\s*([\\d.]+)%\\s*\\*\\s*([\\d.]+)\\s*$"
    );

    /** 基本算术表达式：number op number */
    private static final Pattern ARITHMETIC_PATTERN = Pattern.compile(
            "^\\s*(-?[\\d.]+)\\s*([+\\-*/])\\s*(-?[\\d.]+)\\s*$"
    );

    /** 精度上下文：16 位有效数字。 */
    private static final MathContext PRECISION = MathContext.DECIMAL64;

    /**
     * 执行计算。
     *
     * @param input 工具输入，必需参数 expression（数学表达式或日期差）
     * @return 计算结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String expression = input.getParam("expression", String.class).trim();

            if (expression.isBlank()) {
                return ToolResult.error("表达式不能为空");
            }

            // 优先尝试日期差计算
            Matcher dateMatcher = DATE_DIFF_PATTERN.matcher(expression);
            if (dateMatcher.matches()) {
                return calculateDateDiff(expression, dateMatcher);
            }

            // 尝试百分比计算
            Matcher percentMatcher = PERCENT_PATTERN.matcher(expression);
            if (percentMatcher.matches()) {
                return calculatePercent(expression, percentMatcher);
            }

            // 基本算术运算
            Matcher arithmeticMatcher = ARITHMETIC_PATTERN.matcher(expression);
            if (arithmeticMatcher.matches()) {
                return calculateArithmetic(expression, arithmeticMatcher);
            }

            return ToolResult.error("无法解析表达式: " + expression
                    + "。支持格式: 四则运算(如 1+2)、百分比(如 200*15%)、日期差(如 2026-03-08 - 2025-01-01)");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("计算工具执行失败: expression={}, error={}", input.parameters().get("expression"), e.getMessage(), e);
            return ToolResult.error("计算失败: " + e.getMessage());
        }
    }

    /** 计算日期差（天数）。 */
    private ToolResult calculateDateDiff(String expression, Matcher matcher) {
        try {
            LocalDate date1 = LocalDate.parse(matcher.group(1));
            LocalDate date2 = LocalDate.parse(matcher.group(2));
            long days = ChronoUnit.DAYS.between(date2, date1);

            return ToolResult.success(Map.of(
                    "expression", expression,
                    "result", String.valueOf(days),
                    "unit", "天",
                    "type", "日期差"
            ));
        } catch (DateTimeParseException e) {
            return ToolResult.error("日期格式错误: " + e.getMessage() + "。请使用 yyyy-MM-dd 格式");
        }
    }

    /** 计算百分比。 */
    private ToolResult calculatePercent(String expression, Matcher matcher) {
        BigDecimal base;
        BigDecimal percent;

        if (matcher.group(1) != null) {
            // number * number%
            base = new BigDecimal(matcher.group(1));
            percent = new BigDecimal(matcher.group(2));
        } else {
            // number% * number
            percent = new BigDecimal(matcher.group(3));
            base = new BigDecimal(matcher.group(4));
        }

        BigDecimal result = base.multiply(percent).divide(BigDecimal.valueOf(100), PRECISION);

        return ToolResult.success(Map.of(
                "expression", expression,
                "result", stripTrailingZeros(result),
                "type", "百分比"
        ));
    }

    /** 计算基本算术运算。 */
    private ToolResult calculateArithmetic(String expression, Matcher matcher) {
        BigDecimal left = new BigDecimal(matcher.group(1));
        String operator = matcher.group(2);
        BigDecimal right = new BigDecimal(matcher.group(3));

        BigDecimal result = switch (operator) {
            case "+" -> left.add(right, PRECISION);
            case "-" -> left.subtract(right, PRECISION);
            case "*" -> left.multiply(right, PRECISION);
            case "/" -> {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    yield null;
                }
                yield left.divide(right, PRECISION.getPrecision(), RoundingMode.HALF_UP);
            }
            default -> throw new IllegalArgumentException("不支持的运算符: " + operator);
        };

        if (result == null) {
            return ToolResult.error("除数不能为零");
        }

        return ToolResult.success(Map.of(
                "expression", expression,
                "result", stripTrailingZeros(result),
                "type", "算术"
        ));
    }

    /** 去除尾部多余零（如 10.00 → 10）。 */
    private String stripTrailingZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
