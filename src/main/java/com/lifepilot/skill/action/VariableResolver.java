package com.lifepilot.skill.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量替换引擎 — 统一处理三种占位符。
 *
 * <p>支持的占位符：
 * <ul>
 *   <li>${params.xxx} — 输入参数</li>
 *   <li>${env.XXX} — 系统环境变量</li>
 *   <li>${result.xxx} — 前序结果</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VariableResolver {

    private static final Logger log = LoggerFactory.getLogger(VariableResolver.class);

    /** 变量占位符模式：${scope.key}，scope 为 params/env/result。 */
    private static final Pattern VARIABLE_PATTERN =
            Pattern.compile("\\$\\{(params|env|result)\\.([^}]+)}");

    /** Shell 注入特殊字符。 */
    private static final Pattern SHELL_INJECTION_PATTERN = Pattern.compile("[;|&`$()]");

    /**
     * 替换字符串中的变量占位符。
     *
     * @param template 包含占位符的模板字符串
     * @param params   输入参数
     * @param result   前序结果（可为 null）
     * @return 替换后的字符串
     */
    public String resolve(String template, Map<String, Object> params,
                          @Nullable Map<String, Object> result) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        return matcher.replaceAll(matchResult -> {
            String scope = matchResult.group(1);
            String key = matchResult.group(2);
            return Matcher.quoteReplacement(resolveVariable(scope, key, params, result));
        });
    }

    /**
     * 检查字符串是否包含 Shell 注入字符（仅 ShellAction 场景使用）。
     *
     * @param value 待检查的值
     * @return true 表示包含注入字符
     */
    public boolean containsShellInjection(String value) {
        return SHELL_INJECTION_PATTERN.matcher(value).find();
    }

    /**
     * 根据作用域和键解析变量值。
     *
     * @param scope  作用域（params/env/result）
     * @param key    变量键
     * @param params 输入参数
     * @param result 前序结果
     * @return 解析后的值，未找到时保留原始占位符或使用空字符串（env 场景）
     */
    private String resolveVariable(String scope, String key,
                                   Map<String, Object> params,
                                   @Nullable Map<String, Object> result) {
        return switch (scope) {
            case "params" -> resolveFromMap(params, key, "${params." + key + "}");
            case "env" -> resolveEnv(key);
            case "result" -> resolveResult(result, key);
            default -> "${" + scope + "." + key + "}";
        };
    }

    /** 从 Map 中查找键值，不存在时返回 fallback。 */
    private String resolveFromMap(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value != null ? value.toString() : fallback;
    }

    /** 从系统环境变量中查找，不存在时记录 WARN 并返回空字符串。 */
    private String resolveEnv(String key) {
        String value = System.getenv(key);
        if (value == null) {
            log.warn("环境变量不存在: key={}", key);
            return "";
        }
        return value;
    }

    /** 从前序结果中查找，result 为 null 或键不存在时保留占位符。 */
    private String resolveResult(@Nullable Map<String, Object> result, String key) {
        if (result == null) {
            return "${result." + key + "}";
        }
        return resolveFromMap(result, key, "${result." + key + "}");
    }
}
