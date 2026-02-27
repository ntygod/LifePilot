package com.lifepilot.observability.redactor;

import java.util.regex.Pattern;

/**
 * 脱敏规则 — 定义一条正则匹配 + 替换的脱敏规则。
 *
 * @param name        规则名称（唯一标识）
 * @param description 规则描述
 * @param pattern     匹配正则
 * @param replacement 替换模板（支持 $1 等反向引用）
 * @param priority    优先级（数值越大优先级越高，先执行）
 * @param enabled     是否启用
 * @author zsg
 * @since 2026-02-27
 */
public record RedactionRule(
        String name,
        String description,
        Pattern pattern,
        String replacement,
        int priority,
        boolean enabled
) {
}
