package com.lifepilot.memory.store.procedural;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板步骤 — 操作模板中的单个执行步骤。
 *
 * <p>每个步骤对应一次工具调用，{@code parameterTemplate} 中的值支持
 * {@code ${variable}} 占位符语法，通过 {@link #resolveParameters(Map)} 替换为实际值。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record TemplateStep(
        int stepOrder,
        String toolId,
        String action,
        Map<String, String> parameterTemplate,
        String description,
        boolean isOptional
) {

    /** 匹配 ${variable} 占位符的正则表达式。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** compact constructor：确保 parameterTemplate 不可变。 */
    public TemplateStep {
        parameterTemplate = parameterTemplate != null ? Map.copyOf(parameterTemplate) : Map.of();
    }

    /**
     * 用实际变量值替换 {@code ${variable}} 占位符。
     *
     * <p>遍历 {@code parameterTemplate} 的每个值，将其中所有 {@code ${variable}}
     * 占位符替换为 {@code variables} 映射中对应的值。未在 {@code variables} 中定义的
     * 占位符保持原样不变。</p>
     *
     * @param variables 变量名到实际值的映射
     * @return 替换后的参数映射（不可变）
     */
    public Map<String, String> resolveParameters(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return parameterTemplate;
        }
        var resolved = new HashMap<String, String>(parameterTemplate.size());
        for (var entry : parameterTemplate.entrySet()) {
            resolved.put(entry.getKey(), resolvePlaceholders(entry.getValue(), variables));
        }
        return Map.copyOf(resolved);
    }

    /**
     * 替换单个字符串中的所有 ${variable} 占位符。
     */
    private static String resolvePlaceholders(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.getOrDefault(varName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
