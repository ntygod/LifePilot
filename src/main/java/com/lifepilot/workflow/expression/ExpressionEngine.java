package com.lifepilot.workflow.expression;

import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowException.ExpressionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级表达式引擎，支持 {@code ${...}} 变量插值和条件求值。
 *
 * <p>表达式语法：
 * <ul>
 *   <li>变量插值：{@code ${inputs.name}}、{@code ${steps.step1.output.result}}、{@code ${loopVar}}</li>
 *   <li>比较运算：{@code ${steps.check.output.count} > 0}</li>
 *   <li>逻辑运算：{@code ${steps.a.output.ok} == true && ${steps.b.output.ok} == true}</li>
 *   <li>字符串字面量：{@code 'hello'}</li>
 * </ul>
 *
 * <p>实现方式：正则提取 {@code ${...}} 占位符 + 递归下降解析器求值条件表达式。
 * 不引入外部表达式库依赖。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class ExpressionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEngine.class);

    /** 匹配 ${...} 占位符的正则 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** 函数注册表，管理内置函数和自定义函数 */
    private final FunctionRegistry functionRegistry;

    /**
     * 使用默认内置函数注册表构造。
     */
    public ExpressionEngine() {
        this.functionRegistry = FunctionRegistry.createWithBuiltins();
    }

    /**
     * 使用指定函数注册表构造。
     *
     * @param functionRegistry 函数注册表
     */
    public ExpressionEngine(FunctionRegistry functionRegistry) {
        this.functionRegistry = functionRegistry;
    }

    /**
     * 解析字符串中的 {@code ${...}} 表达式，替换为 {@link WorkflowContext} 中的值。
     *
     * @param template 包含 {@code ${...}} 占位符的模板字符串
     * @param context  工作流变量上下文
     * @return 替换后的字符串
     * @throws ExpressionEvaluationException 变量不存在或语法错误时抛出
     */
    public String resolve(String template, WorkflowContext context) {
        if (template == null) {
            return null;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = resolveVariable(path, context);
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 解析 Map 中所有 String 值的表达式。
     *
     * <p>非 String 类型的值原样保留，String 类型的值通过 {@link #resolve} 解析。
     *
     * @param params  包含表达式的参数 Map
     * @param context 工作流变量上下文
     * @return 解析后的参数 Map（新实例）
     * @throws ExpressionEvaluationException 变量不存在或语法错误时抛出
     */
    public Map<String, Object> resolveMap(Map<String, Object> params, WorkflowContext context) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new HashMap<>(params.size());
        for (var entry : params.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                resolved.put(entry.getKey(), resolve(strValue, context));
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(resolved);
    }

    /**
     * 求值条件表达式，返回布尔结果。
     *
     * <p>支持比较运算符（==, !=, >, <, >=, <=）和逻辑运算符（&&, ||, !）。
     * 表达式中的 {@code ${...}} 变量先解析为具体值，再进行条件求值。
     *
     * @param condition 条件表达式字符串
     * @param context   工作流变量上下文
     * @return 条件求值结果
     * @throws ExpressionEvaluationException 语法错误或变量不存在时抛出
     */
    public boolean evaluateCondition(String condition, WorkflowContext context) {
        if (condition == null || condition.isBlank()) {
            throw new ExpressionEvaluationException(
                    new ExpressionException(condition == null ? "" : condition, "条件表达式不能为空", 0));
        }
        // 先解析 ${...} 变量
        String resolved = resolve(condition, context);
        log.debug("条件表达式解析: 原始='{}', 解析后='{}'", condition, resolved);

        // 递归下降解析器求值
        ConditionParser parser = new ConditionParser(resolved, condition, functionRegistry);
        boolean result = parser.parseExpression();
        parser.expectEnd();
        return result;
    }

    /**
     * 试运行解析结果。
     *
     * @param resolved        解析后的字符串（缺失变量用占位符替代）
     * @param unresolvedPaths 未解析的变量路径列表
     */
    public record DryRunResolveResult(String resolved, List<String> unresolvedPaths) {
        public DryRunResolveResult {
            unresolvedPaths = unresolvedPaths == null ? List.of() : List.copyOf(unresolvedPaths);
        }
    }

    /**
     * 试运行模式解析字符串中的 {@code ${...}} 表达式。
     *
     * <p>与 {@link #resolve} 类似，但缺失变量时返回占位符 {@code <未解析: path>} 而非抛异常，
     * 同时收集所有未解析的变量路径。
     *
     * @param template 包含 {@code ${...}} 占位符的模板字符串
     * @param context  工作流变量上下文
     * @return 试运行解析结果（含占位符的字符串 + 未解析路径列表）
     */
    public DryRunResolveResult resolveDryRun(String template, WorkflowContext context) {
        if (template == null) {
            return new DryRunResolveResult(null, List.of());
        }

        List<String> unresolvedPaths = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Optional<Object> value = context.get(path);
            if (value.isPresent()) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value.get())));
            } else {
                unresolvedPaths.add(path);
                matcher.appendReplacement(result, Matcher.quoteReplacement("<未解析: " + path + ">"));
            }
        }
        matcher.appendTail(result);
        return new DryRunResolveResult(result.toString(), unresolvedPaths);
    }

    /**
     * 试运行模式解析 Map 中所有 String 值的表达式。
     *
     * <p>缺失变量时使用占位符替代，不抛异常。
     *
     * @param params  包含表达式的参数 Map
     * @param context 工作流变量上下文
     * @return 解析后的参数 Map（新实例）和未解析路径列表
     */
    public DryRunResolveResult resolveDryRunMap(Map<String, Object> params, WorkflowContext context) {
        if (params == null || params.isEmpty()) {
            return new DryRunResolveResult("{}", List.of());
        }
        List<String> allUnresolved = new ArrayList<>();
        Map<String, Object> resolved = new HashMap<>(params.size());
        for (var entry : params.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                DryRunResolveResult r = resolveDryRun(strValue, context);
                resolved.put(entry.getKey(), r.resolved());
                allUnresolved.addAll(r.unresolvedPaths());
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return new DryRunResolveResult(resolved.toString(), allUnresolved);
    }

    /**
     * 从 WorkflowContext 中解析变量路径。
     */
    private Object resolveVariable(String path, WorkflowContext context) {
        Optional<Object> value = context.get(path);
        if (value.isEmpty()) {
            log.warn("表达式变量未找到: path={}", path);
            throw new ExpressionEvaluationException(
                    new ExpressionException("${" + path + "}", "变量路径不存在: " + path, 0));
        }
        return value.get();
    }

    // ========== 递归下降条件解析器 ==========

    /**
     * 轻量级递归下降解析器，用于条件表达式求值。
     *
     * <p>语法（优先级从低到高）：
     * <pre>
     * expression  → orExpr
     * orExpr      → andExpr ( '||' andExpr )*
     * andExpr     → notExpr ( '&&' notExpr )*
     * notExpr     → '!' notExpr | comparison
     * comparison  → primary ( ('==' | '!=' | '>' | '<' | '>=' | '<=') primary )?
     * primary     → NUMBER | BOOLEAN | STRING_LITERAL | IDENTIFIER | '(' expression ')'
     * </pre>
     */
    private static class ConditionParser {
        private final String input;
        private final String originalExpression;
        private final FunctionRegistry functionRegistry;
        private int pos;

        ConditionParser(String input, String originalExpression, FunctionRegistry functionRegistry) {
            this.input = input;
            this.originalExpression = originalExpression;
            this.functionRegistry = functionRegistry;
            this.pos = 0;
        }

        boolean parseExpression() {
            return parseOrExpr();
        }

        void expectEnd() {
            skipWhitespace();
            if (pos < input.length()) {
                throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression,
                                "条件表达式语法错误: 位置 " + pos + " 处存在多余字符 '" + input.charAt(pos) + "'",
                                pos));
            }
        }

        // orExpr → andExpr ( '||' andExpr )*
        private boolean parseOrExpr() {
            boolean result = parseAndExpr();
            while (match("||")) {
                boolean right = parseAndExpr();
                result = result || right;
            }
            return result;
        }

        // andExpr → notExpr ( '&&' notExpr )*
        private boolean parseAndExpr() {
            boolean result = parseNotExpr();
            while (match("&&")) {
                boolean right = parseNotExpr();
                result = result && right;
            }
            return result;
        }

        // notExpr → '!' notExpr | comparison
        private boolean parseNotExpr() {
            skipWhitespace();
            if (match("!")) {
                // 确保不是 != 运算符
                if (pos < input.length() && input.charAt(pos) == '=') {
                    // 回退，这不是 ! 运算符，而是后续 != 的一部分
                    pos--;
                    return toBoolean(parseComparison());
                }
                return !parseNotExpr();
            }
            return toBoolean(parseComparison());
        }

        // comparison → primary ( ('==' | '!=' | '>' | '<' | '>=' | '<=') primary )?
        private Object parseComparison() {
            Object left = parsePrimary();
            skipWhitespace();

            String op = parseComparisonOp();
            if (op == null) {
                return left;
            }

            Object right = parsePrimary();
            return evaluateComparison(left, op, right);
        }

        private String parseComparisonOp() {
            if (match("==")) return "==";
            if (match("!=")) return "!=";
            if (match(">=")) return ">=";
            if (match("<=")) return "<=";
            if (match(">")) return ">";
            if (match("<")) return "<";
            return null;
        }

        // primary → NUMBER | BOOLEAN | STRING_LITERAL | IDENTIFIER | '(' expression ')'
        private Object parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression, "条件表达式语法错误: 意外的表达式结束", pos));
            }

            char ch = input.charAt(pos);

            // 括号表达式
            if (ch == '(') {
                pos++;
                boolean result = parseOrExpr();
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new ExpressionEvaluationException(
                            new ExpressionException(originalExpression, "条件表达式语法错误: 缺少右括号 ')'", pos));
                }
                pos++;
                return result;
            }

            // 字符串字面量（单引号）
            if (ch == '\'') {
                return parseStringLiteral();
            }

            // 数字（含负数）
            if (ch == '-' || Character.isDigit(ch)) {
                return parseNumber();
            }

            // 布尔值或标识符
            return parseIdentifierOrBoolean();
        }

        private String parseStringLiteral() {
            pos++; // 跳过开头的 '
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != '\'') {
                pos++;
            }
            if (pos >= input.length()) {
                throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression, "条件表达式语法错误: 字符串字面量未闭合", start - 1));
            }
            String value = input.substring(start, pos);
            pos++; // 跳过结尾的 '
            return value;
        }

        private Object parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                // 不是数字，回退
                pos = start;
                return parseIdentifierOrBoolean();
            }
            boolean hasDecimal = false;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                if (input.charAt(pos) == '.') {
                    if (hasDecimal) break;
                    hasDecimal = true;
                }
                pos++;
            }
            String numStr = input.substring(start, pos);
            if (hasDecimal) {
                return Double.parseDouble(numStr);
            }
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return Double.parseDouble(numStr);
            }
        }

        private Object parseIdentifierOrBoolean() {
            int start = pos;
            while (pos < input.length() && isIdentifierChar(input.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression,
                                "条件表达式语法错误: 位置 " + pos + " 处遇到意外字符 '" + input.charAt(pos) + "'",
                                pos));
            }
            String token = input.substring(start, pos);
            if ("true".equals(token)) return Boolean.TRUE;
            if ("false".equals(token)) return Boolean.FALSE;
            if ("null".equals(token)) return null;

            // 检测函数调用：标识符后跟 '('
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '(') {
                return parseFunctionCall(token);
            }

            // 作为已解析的标识符值返回
            return token;
        }

        /**
         * 解析函数调用：functionName(arg1, arg2, ...)
         * 参数通过递归调用 parseComparison() 解析，支持嵌套函数调用。
         */
        private Object parseFunctionCall(String functionName) {
            pos++; // 跳过 '('
            List<Object> args = new ArrayList<>();
            skipWhitespace();

            // 空参数列表
            if (pos < input.length() && input.charAt(pos) == ')') {
                pos++;
            } else {
                // 解析逗号分隔的参数
                while (true) {
                    Object arg = parseComparison();
                    args.add(arg);
                    skipWhitespace();
                    if (pos < input.length() && input.charAt(pos) == ',') {
                        pos++; // 跳过 ','
                    } else if (pos < input.length() && input.charAt(pos) == ')') {
                        pos++; // 跳过 ')'
                        break;
                    } else {
                        throw new ExpressionEvaluationException(
                                new ExpressionException(originalExpression,
                                        "函数 " + functionName + " 调用语法错误: 期望 ',' 或 ')'，位置 " + pos,
                                        pos));
                    }
                }
            }

            // 查找函数
            Optional<ExpressionFunction> func = functionRegistry.find(functionName);
            if (func.isEmpty()) {
                throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression,
                                "未知函数: " + functionName + "，可用函数: " + functionRegistry.listNames(),
                                pos));
            }

            // 执行函数（ExpressionFunction 内部负责参数类型校验，不匹配时抛出 ExpressionEvaluationException）
            return func.get().apply(args);
        }

        private boolean isIdentifierChar(char ch) {
            return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.';
        }

        private boolean evaluateComparison(Object left, String op, Object right) {
            return switch (op) {
                case "==" -> isEqual(left, right);
                case "!=" -> !isEqual(left, right);
                case ">" -> compare(left, right) > 0;
                case "<" -> compare(left, right) < 0;
                case ">=" -> compare(left, right) >= 0;
                case "<=" -> compare(left, right) <= 0;
                default -> throw new ExpressionEvaluationException(
                        new ExpressionException(originalExpression, "不支持的比较运算符: " + op, pos));
            };
        }

        private boolean isEqual(Object left, Object right) {
            if (left == null && right == null) return true;
            if (left == null || right == null) return false;
            // 数值比较：统一转为 double
            if (left instanceof Number leftNum && right instanceof Number rightNum) {
                return Double.compare(leftNum.doubleValue(), rightNum.doubleValue()) == 0;
            }
            return String.valueOf(left).equals(String.valueOf(right));
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number leftNum && right instanceof Number rightNum) {
                return Double.compare(leftNum.doubleValue(), rightNum.doubleValue());
            }
            if (left instanceof String leftStr && right instanceof String rightStr) {
                return leftStr.compareTo(rightStr);
            }
            // 尝试将两边都转为字符串比较
            String leftStr = String.valueOf(left);
            String rightStr = String.valueOf(right);
            // 尝试数值解析
            try {
                double leftNum = Double.parseDouble(leftStr);
                double rightNum = Double.parseDouble(rightStr);
                return Double.compare(leftNum, rightNum);
            } catch (NumberFormatException e) {
                return leftStr.compareTo(rightStr);
            }
        }

        private boolean toBoolean(Object value) {
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0;
            if (value instanceof String s) return "true".equalsIgnoreCase(s);
            if (value == null) return false;
            return true;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private boolean match(String expected) {
            skipWhitespace();
            if (input.startsWith(expected, pos)) {
                pos += expected.length();
                return true;
            }
            return false;
        }
    }
}
