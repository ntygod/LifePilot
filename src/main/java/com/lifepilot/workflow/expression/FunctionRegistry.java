package com.lifepilot.workflow.expression;

import com.lifepilot.workflow.model.WorkflowException.ExpressionException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 表达式函数注册表，管理内置函数和自定义函数的注册与查找。
 *
 * <p>使用 {@link LinkedHashMap} 保证函数注册顺序确定性。
 * 通过 {@link #createWithBuiltins()} 工厂方法创建包含所有内置函数的注册表。
 *
 * <p>内置函数分四组：
 * <ul>
 *   <li>字符串函数：len, upper, lower, trim, substring, replace, contains, split, join</li>
 *   <li>日期函数：now, formatDate, parseDate, addDays, addHours, daysBetween</li>
 *   <li>集合函数：size, first, last, flatten, distinct</li>
 *   <li>数学函数：min, max, abs, round, ceil, floor</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-13
 */
public class FunctionRegistry {

    private final Map<String, ExpressionFunction> functions = new LinkedHashMap<>();

    /**
     * 注册一个函数。如果同名函数已存在，将被覆盖。
     *
     * @param name     函数名称
     * @param function 函数实现
     */
    public void register(String name, ExpressionFunction function) {
        Objects.requireNonNull(name, "函数名称不能为 null");
        Objects.requireNonNull(function, "函数实现不能为 null");
        functions.put(name, function);
    }

    /**
     * 按名称查找函数。
     *
     * @param name 函数名称
     * @return 函数实现，不存在时返回 {@link Optional#empty()}
     */
    public Optional<ExpressionFunction> find(String name) {
        return Optional.ofNullable(functions.get(name));
    }

    /**
     * 列出所有已注册的函数名称。
     *
     * @return 函数名称集合（保持注册顺序）
     */
    public Set<String> listNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(functions.keySet()));
    }

    /**
     * 创建包含所有内置函数的注册表。
     *
     * @return 已注册全部内置函数的 FunctionRegistry 实例
     */
    public static FunctionRegistry createWithBuiltins() {
        FunctionRegistry registry = new FunctionRegistry();
        registerStringFunctions(registry);
        registerDateFunctions(registry);
        registerCollectionFunctions(registry);
        registerMathFunctions(registry);
        return registry;
    }

    // ========== 字符串函数 ==========

    private static void registerStringFunctions(FunctionRegistry registry) {
        // len(str) → 字符串长度
        registry.register("len", args -> {
            checkArgCount("len", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return 0;
            return requireString("len", arg, 1).length();
        });

        // upper(str) → 转大写
        registry.register("upper", args -> {
            checkArgCount("upper", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            return requireString("upper", arg, 1).toUpperCase();
        });

        // lower(str) → 转小写
        registry.register("lower", args -> {
            checkArgCount("lower", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            return requireString("lower", arg, 1).toLowerCase();
        });

        // trim(str) → 去除首尾空白
        registry.register("trim", args -> {
            checkArgCount("trim", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            return requireString("trim", arg, 1).trim();
        });

        // substring(str, start, end) → 子字符串
        registry.register("substring", args -> {
            checkArgRange("substring", args, 2, 3);
            Object arg = args.getFirst();
            if (arg == null) return null;
            String str = requireString("substring", arg, 1);
            int start = requireInt("substring", args.get(1), 2);
            int end = args.size() == 3 ? requireInt("substring", args.get(2), 3) : str.length();
            return str.substring(Math.max(0, start), Math.min(end, str.length()));
        });

        // replace(str, target, replacement) → 替换
        registry.register("replace", args -> {
            checkArgCount("replace", args, 3);
            Object arg = args.getFirst();
            if (arg == null) return null;
            String str = requireString("replace", arg, 1);
            String target = requireString("replace", args.get(1), 2);
            String replacement = requireString("replace", args.get(2), 3);
            return str.replace(target, replacement);
        });

        // contains(str, search) → 是否包含
        registry.register("contains", args -> {
            checkArgCount("contains", args, 2);
            Object arg = args.getFirst();
            if (arg == null) return false;
            String str = requireString("contains", arg, 1);
            String search = requireString("contains", args.get(1), 2);
            return str.contains(search);
        });

        // split(str, delimiter) → 分割为列表
        registry.register("split", args -> {
            checkArgCount("split", args, 2);
            Object arg = args.getFirst();
            if (arg == null) return List.of();
            String str = requireString("split", arg, 1);
            String delimiter = requireString("split", args.get(1), 2);
            return List.of(str.split(java.util.regex.Pattern.quote(delimiter)));
        });

        // join(list, delimiter) → 列表拼接为字符串
        registry.register("join", args -> {
            checkArgCount("join", args, 2);
            Object arg = args.getFirst();
            if (arg == null) return "";
            List<?> list = requireList("join", arg, 1);
            String delimiter = requireString("join", args.get(1), 2);
            return list.stream().map(String::valueOf).collect(Collectors.joining(delimiter));
        });
    }

    // ========== 日期函数 ==========

    private static void registerDateFunctions(FunctionRegistry registry) {
        // now() → 当前时间 ISO 8601 字符串
        registry.register("now", args -> {
            checkArgCount("now", args, 0);
            return Instant.now().toString();
        });

        // formatDate(dateStr, pattern) → 格式化日期
        registry.register("formatDate", args -> {
            checkArgCount("formatDate", args, 2);
            Object dateArg = args.getFirst();
            if (dateArg == null) return null;
            String dateStr = requireString("formatDate", dateArg, 1);
            String pattern = requireString("formatDate", args.get(1), 2);
            LocalDateTime dateTime = parseToLocalDateTime(dateStr, "formatDate");
            return dateTime.format(DateTimeFormatter.ofPattern(pattern));
        });

        // parseDate(dateStr) → 解析日期字符串为 ISO 8601
        registry.register("parseDate", args -> {
            checkArgCount("parseDate", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            String dateStr = requireString("parseDate", arg, 1);
            LocalDateTime dateTime = parseToLocalDateTime(dateStr, "parseDate");
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toString();
        });

        // addDays(dateStr, days) → 增加天数
        registry.register("addDays", args -> {
            checkArgCount("addDays", args, 2);
            Object dateArg = args.getFirst();
            if (dateArg == null) return null;
            String dateStr = requireString("addDays", dateArg, 1);
            int days = requireInt("addDays", args.get(1), 2);
            LocalDateTime dateTime = parseToLocalDateTime(dateStr, "addDays");
            return dateTime.plusDays(days).atZone(ZoneId.systemDefault()).toInstant().toString();
        });

        // addHours(dateStr, hours) → 增加小时数
        registry.register("addHours", args -> {
            checkArgCount("addHours", args, 2);
            Object dateArg = args.getFirst();
            if (dateArg == null) return null;
            String dateStr = requireString("addHours", dateArg, 1);
            int hours = requireInt("addHours", args.get(1), 2);
            LocalDateTime dateTime = parseToLocalDateTime(dateStr, "addHours");
            return dateTime.plusHours(hours).atZone(ZoneId.systemDefault()).toInstant().toString();
        });

        // daysBetween(dateStr1, dateStr2) → 两个日期之间的天数
        registry.register("daysBetween", args -> {
            checkArgCount("daysBetween", args, 2);
            Object dateArg1 = args.getFirst();
            Object dateArg2 = args.get(1);
            if (dateArg1 == null || dateArg2 == null) return 0L;
            String dateStr1 = requireString("daysBetween", dateArg1, 1);
            String dateStr2 = requireString("daysBetween", dateArg2, 2);
            LocalDate date1 = parseToLocalDateTime(dateStr1, "daysBetween").toLocalDate();
            LocalDate date2 = parseToLocalDateTime(dateStr2, "daysBetween").toLocalDate();
            return ChronoUnit.DAYS.between(date1, date2);
        });
    }

    // ========== 集合函数 ==========

    private static void registerCollectionFunctions(FunctionRegistry registry) {
        // size(collection) → 集合/Map/字符串的大小
        registry.register("size", args -> {
            checkArgCount("size", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return 0;
            if (arg instanceof List<?> list) return list.size();
            if (arg instanceof Map<?, ?> map) return map.size();
            if (arg instanceof String str) return str.length();
            throw functionError("size", "第 1 个参数期望 List/Map/String 类型，实际为 " + arg.getClass().getSimpleName());
        });

        // first(list) → 列表第一个元素
        registry.register("first", args -> {
            checkArgCount("first", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            List<?> list = requireList("first", arg, 1);
            return list.isEmpty() ? null : list.getFirst();
        });

        // last(list) → 列表最后一个元素
        registry.register("last", args -> {
            checkArgCount("last", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return null;
            List<?> list = requireList("last", arg, 1);
            return list.isEmpty() ? null : list.getLast();
        });

        // flatten(listOfLists) → 展平嵌套列表
        registry.register("flatten", args -> {
            checkArgCount("flatten", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return List.of();
            List<?> list = requireList("flatten", arg, 1);
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof List<?> inner) {
                    result.addAll(inner);
                } else {
                    result.add(item);
                }
            }
            return List.copyOf(result);
        });

        // distinct(list) → 去重
        registry.register("distinct", args -> {
            checkArgCount("distinct", args, 1);
            Object arg = args.getFirst();
            if (arg == null) return List.of();
            List<?> list = requireList("distinct", arg, 1);
            return list.stream().distinct().toList();
        });
    }

    // ========== 数学函数 ==========

    private static void registerMathFunctions(FunctionRegistry registry) {
        // min(a, b) → 最小值
        registry.register("min", args -> {
            checkArgCount("min", args, 2);
            double a = requireDouble("min", args.getFirst(), 1);
            double b = requireDouble("min", args.get(1), 2);
            double result = Math.min(a, b);
            return isWholeNumber(result) ? (long) result : result;
        });

        // max(a, b) → 最大值
        registry.register("max", args -> {
            checkArgCount("max", args, 2);
            double a = requireDouble("max", args.getFirst(), 1);
            double b = requireDouble("max", args.get(1), 2);
            double result = Math.max(a, b);
            return isWholeNumber(result) ? (long) result : result;
        });

        // abs(n) → 绝对值
        registry.register("abs", args -> {
            checkArgCount("abs", args, 1);
            double n = requireDouble("abs", args.getFirst(), 1);
            double result = Math.abs(n);
            return isWholeNumber(result) ? (long) result : result;
        });

        // round(n) → 四舍五入
        registry.register("round", args -> {
            checkArgCount("round", args, 1);
            double n = requireDouble("round", args.getFirst(), 1);
            return Math.round(n);
        });

        // ceil(n) → 向上取整
        registry.register("ceil", args -> {
            checkArgCount("ceil", args, 1);
            double n = requireDouble("ceil", args.getFirst(), 1);
            return (long) Math.ceil(n);
        });

        // floor(n) → 向下取整
        registry.register("floor", args -> {
            checkArgCount("floor", args, 1);
            double n = requireDouble("floor", args.getFirst(), 1);
            return (long) Math.floor(n);
        });
    }

    // ========== 参数校验辅助方法 ==========

    private static void checkArgCount(String funcName, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw functionError(funcName,
                    "期望 " + expected + " 个参数，实际传入 " + args.size() + " 个");
        }
    }

    private static void checkArgRange(String funcName, List<Object> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw functionError(funcName,
                    "期望 " + min + "-" + max + " 个参数，实际传入 " + args.size() + " 个");
        }
    }

    private static String requireString(String funcName, Object arg, int argIndex) {
        if (arg instanceof String s) return s;
        throw functionError(funcName,
                "第 " + argIndex + " 个参数期望 String 类型，实际为 " + (arg == null ? "null" : arg.getClass().getSimpleName()));
    }

    private static int requireInt(String funcName, Object arg, int argIndex) {
        if (arg instanceof Number n) return n.intValue();
        if (arg instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                // 继续抛出类型错误
            }
        }
        throw functionError(funcName,
                "第 " + argIndex + " 个参数期望整数类型，实际为 " + (arg == null ? "null" : arg.getClass().getSimpleName()));
    }

    private static double requireDouble(String funcName, Object arg, int argIndex) {
        if (arg instanceof Number n) return n.doubleValue();
        if (arg instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                // 继续抛出类型错误
            }
        }
        throw functionError(funcName,
                "第 " + argIndex + " 个参数期望数值类型，实际为 " + (arg == null ? "null" : arg.getClass().getSimpleName()));
    }

    private static List<?> requireList(String funcName, Object arg, int argIndex) {
        if (arg instanceof List<?> list) return list;
        throw functionError(funcName,
                "第 " + argIndex + " 个参数期望 List 类型，实际为 " + (arg == null ? "null" : arg.getClass().getSimpleName()));
    }

    private static LocalDateTime parseToLocalDateTime(String dateStr, String funcName) {
        try {
            // 尝试 ISO Instant 格式（如 2026-03-13T10:00:00Z）
            Instant instant = Instant.parse(dateStr);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (DateTimeParseException e1) {
            try {
                // 尝试 ISO LocalDateTime 格式（如 2026-03-13T10:00:00）
                return LocalDateTime.parse(dateStr);
            } catch (DateTimeParseException e2) {
                try {
                    // 尝试 ISO LocalDate 格式（如 2026-03-13）
                    return LocalDate.parse(dateStr).atStartOfDay();
                } catch (DateTimeParseException e3) {
                    throw functionError(funcName,
                            "无法解析日期字符串: '" + dateStr + "'，支持格式: ISO 8601 (yyyy-MM-ddTHH:mm:ssZ / yyyy-MM-ddTHH:mm:ss / yyyy-MM-dd)");
                }
            }
        }
    }

    private static boolean isWholeNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value);
    }

    private static ExpressionEvaluationException functionError(String funcName, String message) {
        return new ExpressionEvaluationException(
                new ExpressionException(funcName + "()", "函数 " + funcName + ": " + message, 0));
    }

}
